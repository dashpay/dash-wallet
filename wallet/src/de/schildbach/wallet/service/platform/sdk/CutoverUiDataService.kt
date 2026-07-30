/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.ExchangeRatesDao
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.NotificationService
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

// ── Neutral display model (no SDK JNI types, no dashj Transaction) ────

/**
 * Lock/confirmation knowledge of one L1 transaction as the Kotlin SDK's
 * Rust core recorded it — the `transactions.context` column
 * (0=mempool, 1=instantSend, 2=inBlock, 3=inChainLockedBlock).
 */
enum class L1TxUiStatus { PENDING, INSTANT_LOCKED, IN_BLOCK, CHAINLOCKED }

/** The SDK's `transactions.direction` column (0..3), with a sign fallback. */
enum class L1TxUiDirection { INCOMING, OUTGOING, INTERNAL, COINJOIN }

/**
 * One wallet-relevant L1 transaction in a NEUTRAL display shape — the
 * post-cutover replacement for reading dashj `Transaction`s on the home
 * screen. Deliberately primitive-only so UI code never sees an SDK JNI
 * type and nothing here can be mistaken for a dashj object.
 */
data class L1TxUiRecord(
    /** DISPLAY-order (byte-reversed) txid hex — dashj's `Sha256Hash.toString()` convention. */
    val txidHex: String,
    /** Signed net effect on the wallet in duffs (positive=received, negative=sent incl. fee). */
    val netAmountDuffs: Long,
    /** Fee in duffs when the SDK knows it (self-authored sends), else null. */
    val feeDuffs: Long?,
    /** Epoch-millis of first observation (or the block timestamp), 0 when unknown. */
    val timestampMs: Long,
    val status: L1TxUiStatus,
    val direction: L1TxUiDirection
)

/**
 * Map the SDK `transactions` row's raw columns to the neutral record.
 * Pure — host-JVM unit-testable without the SDK entity class.
 * Unknown context codes read as [L1TxUiStatus.PENDING] (never claim a
 * lock the SDK didn't record); unknown direction codes fall back to the
 * net-amount sign.
 */
fun l1TxUiRecord(
    txidWireBytes: ByteArray,
    netAmountDuffs: Long,
    feeDuffs: Long?,
    contextCode: Int,
    directionCode: Int,
    firstSeenSec: Long,
    blockTimestampSec: Int
): L1TxUiRecord {
    val status = when (contextCode) {
        1 -> L1TxUiStatus.INSTANT_LOCKED
        2 -> L1TxUiStatus.IN_BLOCK
        3 -> L1TxUiStatus.CHAINLOCKED
        else -> L1TxUiStatus.PENDING
    }
    val direction = when (directionCode) {
        0 -> L1TxUiDirection.INCOMING
        1 -> L1TxUiDirection.OUTGOING
        2 -> L1TxUiDirection.INTERNAL
        3 -> L1TxUiDirection.COINJOIN
        else -> if (netAmountDuffs >= 0) L1TxUiDirection.INCOMING else L1TxUiDirection.OUTGOING
    }
    val timestampMs = when {
        firstSeenSec > 0 -> firstSeenSec * 1000L
        blockTimestampSec > 0 -> blockTimestampSec * 1000L
        else -> 0L
    }
    return L1TxUiRecord(
        txidHex = txidWireBytes.reversedArray().joinToString("") { "%02x".format(it) },
        netAmountDuffs = netAmountDuffs,
        feeDuffs = feeDuffs,
        timestampMs = timestampMs,
        status = status,
        direction = direction
    )
}

// ── Pure row planning (record → display-cache row fields) ─────────────

/**
 * How one [L1TxUiRecord] should render as a home-screen row — resource
 * IDs unresolved so the mapping stays pure/testable. Mirrors the dashj
 * [de.schildbach.wallet.ui.transactions.TxResourceMapper] rules the
 * pre-cutover pipeline applies:
 * - outgoing: title "Sending" until any lock/confirmation, then "Sent";
 *   list value excludes the fee (dashj's removeFee rule);
 * - incoming: title "Received"; secondary status "Processing" until any
 *   lock/confirmation (dashj shows no secondary status once IS-locked);
 * - internal/coinjoin: title "Internal".
 */
internal data class L1TxRowPlan(
    val rowId: String,
    val titleRes: Int,
    /** Secondary status resource, or -1 for none. */
    val statusRes: Int,
    val iconType: Int,
    val iconBgType: Int,
    val filterFlags: Int,
    val valueDuffs: Long,
    val timestampMs: Long,
    val isIncoming: Boolean
)

internal fun planL1TxRow(
    record: L1TxUiRecord,
    // The Platform-funding role of an INTERNAL/COINJOIN-classified asset lock,
    // resolved app-side before this pure planner runs (null for a plain move).
    // When present, the row renders as a SENT "…Fee" instead of "Internal".
    assetLockKind: AssetLockKind? = null
): L1TxRowPlan = when (record.direction) {
    L1TxUiDirection.OUTGOING -> L1TxRowPlan(
        rowId = record.txidHex,
        titleRes = if (record.status == L1TxUiStatus.PENDING) {
            R.string.transaction_row_status_sending
        } else {
            R.string.transaction_row_status_sent
        },
        statusRes = -1,
        iconType = TxDisplayCacheEntry.ICON_SENT,
        iconBgType = TxDisplayCacheEntry.BG_SENT,
        filterFlags = TxDisplayCacheEntry.FLAG_SENT,
        // netAmount includes the fee; the dashj list shows the amount
        // without it (TransactionRowView's removeFee: value.add(fee)).
        valueDuffs = record.netAmountDuffs + (record.feeDuffs ?: 0L),
        timestampMs = record.timestampMs,
        isIncoming = false
    )
    L1TxUiDirection.INTERNAL, L1TxUiDirection.COINJOIN -> if (assetLockKind != null) {
        // A Platform-funding asset lock the SDK recorded as an internal move —
        // render it as the SENT Platform action it funded, not "Internal".
        L1TxRowPlan(
            rowId = record.txidHex,
            titleRes = assetLockTitleRes(assetLockKind),
            statusRes = -1,
            iconType = TxDisplayCacheEntry.ICON_SENT,
            iconBgType = TxDisplayCacheEntry.BG_SENT,
            filterFlags = TxDisplayCacheEntry.FLAG_SENT,
            valueDuffs = record.netAmountDuffs,
            timestampMs = record.timestampMs,
            isIncoming = false
        )
    } else L1TxRowPlan(
        rowId = record.txidHex,
        titleRes = R.string.transaction_row_status_sent_internally,
        statusRes = -1,
        iconType = TxDisplayCacheEntry.ICON_INTERNAL,
        iconBgType = TxDisplayCacheEntry.BG_SENT,
        filterFlags = 0,
        valueDuffs = record.netAmountDuffs,
        timestampMs = record.timestampMs,
        isIncoming = false
    )
    L1TxUiDirection.INCOMING -> if (assetLockKind == AssetLockKind.UNSHIELD) {
        // The unshield/withdraw (AssetUnlock) returns pool funds to the
        // transparent wallet — the SDK records it INCOMING, but it is a
        // self-move, not an external receive. Relabel it "Unshielded" and
        // mark it NON-incoming so it never triggers the coins-received
        // notification (planL1DisplaySync's notify guard is keyed on
        // isIncoming). The row still shows the received icon + positive
        // value, since the transparent balance really does go up.
        L1TxRowPlan(
            rowId = record.txidHex,
            titleRes = assetLockTitleRes(assetLockKind),
            statusRes = if (record.status == L1TxUiStatus.PENDING) {
                R.string.transaction_row_status_processing
            } else {
                -1
            },
            iconType = TxDisplayCacheEntry.ICON_RECEIVED,
            iconBgType = TxDisplayCacheEntry.BG_RECEIVED,
            filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED,
            valueDuffs = record.netAmountDuffs,
            timestampMs = record.timestampMs,
            isIncoming = false
        )
    } else L1TxRowPlan(
        rowId = record.txidHex,
        titleRes = R.string.transaction_row_status_received,
        statusRes = if (record.status == L1TxUiStatus.PENDING) {
            R.string.transaction_row_status_processing
        } else {
            -1
        },
        iconType = TxDisplayCacheEntry.ICON_RECEIVED,
        iconBgType = TxDisplayCacheEntry.BG_RECEIVED,
        filterFlags = TxDisplayCacheEntry.FLAG_RECEIVED,
        valueDuffs = record.netAmountDuffs,
        timestampMs = record.timestampMs,
        isIncoming = true
    )
}

/** The list/detail title string for a Platform-funding asset-lock kind. */
internal fun assetLockTitleRes(kind: AssetLockKind): Int = when (kind) {
    AssetLockKind.UPGRADE -> R.string.dashpay_upgrade_fee
    AssetLockKind.TOPUP -> R.string.dashpay_topup_fee
    // Brian's exact wording: the L1 non-private invite reads "Invitation"
    // (NOT the "Invite Fee" used elsewhere), the shield-in "Shielded", the
    // unshield/withdraw "Unshielded".
    AssetLockKind.INVITE -> R.string.transaction_row_invitation
    AssetLockKind.SHIELD -> R.string.transaction_row_shielded
    AssetLockKind.UNSHIELD -> R.string.transaction_row_unshielded
}

// ── Pure display-cache sync planning ──────────────────────────────────

/**
 * What one post-cutover sync pass should do to the display cache:
 * - [inserts]: rows for SDK transactions the cache has never displayed —
 *   the "invisible receive" fix (a tx the held dashj wallet never saw).
 * - [updates]: SURGICAL fixes to existing dashj-era rows whose live
 *   status dashj can no longer learn — the "stuck Sending" fix. Only the
 *   title/status strings change; value, time, metadata, contact and
 *   service fields are preserved.
 * - [notifyIncoming]: freshly-discovered incoming transactions (subset of
 *   [inserts]) the user should get a coins-received notification for.
 */
internal data class L1DisplaySyncPlan(
    val inserts: List<TxDisplayCacheEntry>,
    val updates: List<TxDisplayCacheEntry>,
    /** (display txid, received duffs) per newly-discovered incoming tx. */
    val notifyIncoming: List<Pair<String, Long>>
)

/** Only notify receives first seen within this window (guards against re-notifying history after a cache wipe). */
internal const val L1_NOTIFY_RECENCY_WINDOW_MS = 24L * 60 * 60 * 1000

/**
 * Pure sync-pass planner. Rules:
 * - Transactions living inside a multi-tx group row ([groupedTxIds]) are
 *   never touched — group rows are dashj-era history.
 * - Unknown rowIds become full inserts rendered purely from the SDK
 *   record; incoming inserts within the recency window also notify.
 * - Existing rows are updated ONLY to reflect lock knowledge dashj is
 *   blind to post-cutover, and only when the row is a plain send/receive
 *   (no service, no gift card, no error, not CoinJoin):
 *   - title "Sending" → "Sent" once the SDK saw any lock/confirmation;
 *   - secondary "Processing" cleared once the tx is locked OR in a block
 *     (dashj never shows "Processing" for a BUILDING tx — see
 *     [de.schildbach.wallet.ui.transactions.TxResourceMapper]);
 *   - secondary "Confirming" cleared only once INSTANT_LOCKED or
 *     CHAINLOCKED (dashj keeps it while building unlocked <6 confs).
 * Everything else is left byte-identical.
 */
internal fun planL1DisplaySync(
    records: List<L1TxUiRecord>,
    existingByRowId: Map<String, TxDisplayCacheEntry>,
    groupedTxIds: Set<String>,
    resolve: (Int) -> String,
    nowMs: Long,
    notifyWindowMs: Long = L1_NOTIFY_RECENCY_WINDOW_MS,
    // Current exchange rate to stamp on FRESH INCOMING inserts, mirroring
    // BlockchainServiceImpl.onCoinsReceived. Both null when the rate is
    // unavailable (or for non-incoming inserts) — the row then carries no
    // historical rate, exactly as before this fix.
    incomingFiatCode: String? = null,
    incomingFiatValue: Long? = null,
    // Platform-funding role per (INTERNAL/COINJOIN) txid, resolved app-side
    // before this pure planner runs — turns the mislabelled "Internal" row
    // into the SENT "…Fee" it funded. Empty = no known asset locks.
    kindByTxid: Map<String, AssetLockKind> = emptyMap()
): L1DisplaySyncPlan {
    val inserts = mutableListOf<TxDisplayCacheEntry>()
    val updates = mutableListOf<TxDisplayCacheEntry>()
    val notify = mutableListOf<Pair<String, Long>>()

    for (record in records) {
        if (record.txidHex in groupedTxIds) continue
        val plan = planL1TxRow(record, kindByTxid[record.txidHex])
        val existing = existingByRowId[record.txidHex]

        if (existing == null) {
            inserts += TxDisplayCacheEntry(
                rowId = plan.rowId,
                title = resolve(plan.titleRes),
                valueSatoshis = plan.valueDuffs,
                iconType = plan.iconType,
                iconBgType = plan.iconBgType,
                statusText = if (plan.statusRes != -1) resolve(plan.statusRes) else "",
                comment = "",
                transactionAmount = 1,
                time = if (plan.timestampMs > 0) plan.timestampMs else nowMs,
                hasErrors = false,
                service = null,
                // Stamp the current fiat rate on every fresh SDK-discovered row,
                // mirroring BlockchainServiceImpl.onCoinsReceived — the held dashj
                // wallet never sees these SDK-only txs, so nothing else records
                // their rate. Bug C: the SDK send path never sets tx.exchangeRate,
                // so an OUTGOING insert previously carried no rate → the row showed
                // fiat "not available". The tx happens "now", so the current rate
                // is the correct historical rate for both directions (and internal
                // moves). Both fields stay null only when the rate is unavailable.
                exchangeRateFiatCode = incomingFiatCode,
                exchangeRateFiatValue = incomingFiatValue,
                contactUsername = null,
                contactDisplayName = null,
                contactAvatarUrl = null,
                contactUserId = null,
                filterFlags = plan.filterFlags
            )
            if (plan.isIncoming && record.netAmountDuffs > 0 &&
                record.timestampMs >= nowMs - notifyWindowMs
            ) {
                notify += record.txidHex to record.netAmountDuffs
            }
            continue
        }

        // Surgical status refresh of a dashj-era row. Never touch rows
        // with richer semantics than a plain send/receive.
        if (existing.hasErrors || existing.service != null ||
            (existing.filterFlags and TxDisplayCacheEntry.FLAG_GIFT_CARD) != 0 ||
            (existing.filterFlags and TxDisplayCacheEntry.FLAG_COINJOIN) != 0
        ) {
            continue
        }

        var updated = existing
        if (existing.title == resolve(R.string.transaction_row_status_sending) &&
            plan.titleRes == R.string.transaction_row_status_sent
        ) {
            updated = updated.copy(title = resolve(plan.titleRes))
        }
        val locked = record.status == L1TxUiStatus.INSTANT_LOCKED ||
            record.status == L1TxUiStatus.CHAINLOCKED
        // "Processing" also clears on a plain block confirmation: dashj's
        // TxResourceMapper only shows "Processing" while confidence is
        // PENDING — a BUILDING (in-block) tx shows "Confirming" or nothing.
        // Without this, a dropped islock event leaves an incoming row
        // "Processing" until a CHAINLOCK context lands (~1 extra block,
        // unbounded if chainlocks stall). "Confirming" still clears only on
        // a lock, matching dashj (BUILDING <6 confs unlocked keeps it).
        val confirmed = locked || record.status == L1TxUiStatus.IN_BLOCK
        if (updated.statusText.isNotEmpty() &&
            ((confirmed && updated.statusText == resolve(R.string.transaction_row_status_processing)) ||
                (locked && updated.statusText == resolve(R.string.transaction_row_status_confirming)))
        ) {
            updated = updated.copy(statusText = "")
        }

        // Re-stamp degenerate carried-over / pre-block rows. A dashj-era or
        // event-born row can carry value=0 (attribution not yet written) or a
        // null historical rate; once the SDK record has strictly better data,
        // refresh ONLY those degenerate fields. Idempotent — a row that already
        // has a value/rate is never rewritten, so user memo/contact/service and
        // the tax category (a separate table) are all preserved via copy().
        if (updated.valueSatoshis == 0L && plan.valueDuffs != 0L) {
            // A zero-value row may also carry the wrong direction/label, so
            // refresh the whole display shape from the plan (title/icon/flags).
            updated = updated.copy(
                valueSatoshis = plan.valueDuffs,
                iconType = plan.iconType,
                iconBgType = plan.iconBgType,
                filterFlags = plan.filterFlags,
                title = resolve(plan.titleRes)
            )
        }
        if (updated.exchangeRateFiatCode == null && incomingFiatValue != null) {
            // Stamp the current rate (same current-rate semantics the insert
            // path uses) — the SDK send path never records tx.exchangeRate, so
            // nothing else fills the rate for these SDK-only rows.
            updated = updated.copy(
                exchangeRateFiatCode = incomingFiatCode,
                exchangeRateFiatValue = incomingFiatValue
            )
        }
        // Re-label an already-cached plain internal move to the Platform-funding "…Fee"
        // once its funding kind is known. The ASSET_LOCK_TXID persist races the first
        // tx-feed insert, so the asset-lock row is usually cached as "Internal" before
        // resolveAssetLockKind can classify it; without this the ~60s re-resolve has
        // nowhere to write the UPGRADE label. Only a plain sent_internally row is re-stamped.
        if (kindByTxid[record.txidHex] != null &&
            updated.title == resolve(R.string.transaction_row_status_sent_internally)) {
            updated = updated.copy(
                title = resolve(plan.titleRes),
                iconType = plan.iconType,
                iconBgType = plan.iconBgType,
                filterFlags = plan.filterFlags
            )
        }
        if (updated != existing) updates += updated
    }
    return L1DisplaySyncPlan(inserts, updates, notify)
}

// ── Engine-event → record / row-update mapping (instant receive) ──────

/**
 * Lift a [L1TxEvent.Detected] engine event into the SAME neutral record
 * shape the Room snapshot pipeline produces, so one planner
 * ([planL1DisplaySync]) serves both feeds and dedup is structural: the
 * event-born row and the later Room-born row share the txid rowId, so the
 * second sighting is an update, never a duplicate. First-seen is stamped
 * `nowMs` — the event IS the first sighting. Pure — host-testable.
 */
internal fun l1TxUiRecordFromEvent(event: L1TxEvent.Detected, nowMs: Long): L1TxUiRecord =
    l1TxUiRecord(
        // The event carries display-order hex; the record factory takes
        // wire-order bytes — round-trip through the shared mapping so the
        // status/direction/rowId conventions stay single-sourced.
        txidWireBytes = event.txidHex
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            .reversedArray(),
        netAmountDuffs = event.netAmountDuffs,
        feeDuffs = event.feeDuffs,
        contextCode = event.contextCode,
        directionCode = event.directionCode,
        firstSeenSec = nowMs / 1000,
        blockTimestampSec = 0
    )

/**
 * The pre-block IS-lock row refresh for one [L1TxEvent.InstantLocked]:
 * the same two surgical edges [planL1DisplaySync]'s update path applies
 * once the Room row reports a lock — title "Sending" → "Sent", secondary
 * "Processing"/"Confirming" cleared — but driven directly by the engine
 * event, so the flip happens the moment the IS lock lands instead of on
 * the next Room emission. The event carries only a txid (no direction),
 * so the row itself tells us which edges apply. Same never-touch guards
 * as the planner: rows with richer semantics (service, gift card, error,
 * CoinJoin) are left byte-identical. Returns null when nothing changes.
 * Pure — host-testable.
 */
internal fun planL1InstantLockRowUpdate(
    existing: TxDisplayCacheEntry,
    resolve: (Int) -> String
): TxDisplayCacheEntry? {
    if (existing.hasErrors || existing.service != null ||
        (existing.filterFlags and TxDisplayCacheEntry.FLAG_GIFT_CARD) != 0 ||
        (existing.filterFlags and TxDisplayCacheEntry.FLAG_COINJOIN) != 0
    ) {
        return null
    }
    var updated = existing
    if (updated.title == resolve(R.string.transaction_row_status_sending)) {
        updated = updated.copy(title = resolve(R.string.transaction_row_status_sent))
    }
    if (updated.statusText.isNotEmpty() &&
        (updated.statusText == resolve(R.string.transaction_row_status_processing) ||
            updated.statusText == resolve(R.string.transaction_row_status_confirming))
    ) {
        updated = updated.copy(statusText = "")
    }
    return updated.takeIf { it != existing }
}

// ── Seam tx snapshot (post-cutover WalletDataProvider reads) ──────────

/**
 * Everything the wallet-data seam ([de.schildbach.wallet.data.WalletDataAdapter])
 * needs to serve neutral `TxInfo` reads from the SDK's L1 store post-cutover.
 * Neutral/primitive-only, like [L1TxUiRecord]; the dashj-typed conversion
 * happens at the seam ([de.schildbach.wallet.transactions.SdkTxInfoBuilder]).
 *
 * @param walletRecords wallet-relevant transactions (TXO-joined), same set
 *        as [CutoverUiSource.observeWalletTxRecords].
 * @param payloadByTxid raw serialized transaction bytes for EVERY SDK-known
 *        transaction (not just wallet-relevant ones), keyed by DISPLAY-order
 *        txid hex — the lookup that resolves an input's connected output.
 * @param mineOutpoints wallet-owned outputs as "txidHex:vout" keys (the SDK
 *        TXO set — the same output universe dashj's `isMine` covers,
 *        parity-proven by the shadow harness).
 * @param spenderByOutpoint "txidHex:vout" → display txid hex of the tx
 *        spending that output, from the TXO rows' `spendingTxid`.
 */
class SdkSeamTxSnapshot(
    val walletRecords: List<L1TxUiRecord>,
    val payloadByTxid: Map<String, ByteArray>,
    val mineOutpoints: Set<String>,
    val spenderByOutpoint: Map<String, String>
)

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK's L1 read surface for the post-cutover UI —
 * the same Room stores the [L1ShadowSyncService] parity queries read
 * ([DashSdkL1ShadowSource]), lifted into reactive flows so the home
 * screen updates the moment the SDK's SPV loop lands a change.
 */
interface CutoverUiSource {
    /** Same contract as [SdkL1SendSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Live total L1 balance of the wallet in duffs — the sum of unspent
     * TXO rows, i.e. the same output set dashj's `getBalance(ESTIMATED)`
     * sums (parity-proven by the shadow harness).
     */
    fun observeTotalDuffs(walletIdHex: String): Flow<Long>

    /**
     * ONE-SHOT authoritative total balance in duffs read straight from the
     * SDK wallet's NATIVE ledger (`ManagedPlatformWallet.balance()` =
     * confirmed + unconfirmed) — the SAME read [L1ShadowSyncService.sdkBalanceDuffs]
     * uses. Unlike [observeTotalDuffs] (which sums the Room `txos` table),
     * this reflects a self-send's native debit IMMEDIATELY: on a spend the
     * SDK debits its native ledger at once, but the spent-TXO marks are not
     * written to Room until the block/IS-lock lands, so a Room re-read would
     * return the STALE pre-send sum. Used by [balancePipeline]'s post-event /
     * ticker re-read so the header reaches 0 after a max self-send regardless
     * of Room `txos` timing.
     */
    suspend fun currentTotalDuffs(walletIdHex: String): Long

    /**
     * ONE-SHOT native-ledger balance SPLIT (confirmed vs unconfirmed) in
     * duffs, from `ManagedPlatformWallet.balance()`. `confirmed` counts
     * outputs that are in a block OR InstantSend-locked (the SDK ledger's
     * `WalletCoreBalance.confirmed`) — exactly the funds the asset-lock
     * funding selection accepts (`is_confirmed || is_instantlocked`, rust-dashcore
     * `transaction_builder.rs`), i.e. what can be shielded right now (an IS-lock
     * yields an InstantAssetLockProof at 0 block confirmations). `unconfirmed`
     * is mature mempool not yet confirmed/IS-locked — NOT shieldable until a
     * block or islock lands, so the shielded screen shows it as "pending".
     */
    suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs

    /** Live wallet-relevant transaction records, neutral shape. */
    fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>>

    /**
     * Live [SdkSeamTxSnapshot]s for the post-cutover seam reads
     * ([de.schildbach.wallet.service.platform.sdk.CutoverTxSeamService]).
     */
    fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot>
}

/**
 * Native-ledger balance split in duffs. `confirmed` = outputs in a block OR
 * InstantSend-locked (immediately shieldable — the asset-lock funding selection
 * accepts `is_confirmed || is_instantlocked`); `unconfirmed` = mature mempool not
 * yet confirmed/IS-locked (not shieldable until a block or islock lands).
 */
data class SdkBalanceSplitDuffs(val confirmed: Long, val unconfirmed: Long) {
    val total: Long get() = confirmed + unconfirmed
}

/** Production [CutoverUiSource]: the live SDK Room DB, reactive. */
internal class DashSdkCutoverUiSource(
    private val service: DashSdkService
) : CutoverUiSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private suspend fun database(): org.dashfoundation.dashsdk.persistence.DashDatabase {
        service.ensureStarted()
        return checkNotNull(service.databaseOrNull()) {
            "SDK database missing after ensureStarted()"
        }
    }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override fun observeTotalDuffs(walletIdHex: String): Flow<Long> = flow {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        emitAll(
            database().txoDao().observeUnspentByWallet(walletId)
                .map { rows -> rows.sumOf { it.amount } }
        )
    }

    override suspend fun currentTotalDuffs(walletIdHex: String): Long =
        currentBalanceSplitDuffs(walletIdHex).total

    override suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs {
        // Native ledger read — the SAME accessor L1ShadowSyncService.sdkBalanceDuffs
        // uses. A self-send debits this immediately; the Room `txos` table lags.
        val wallet = checkNotNull(manager().wallets.value[walletIdHex]) {
            "SDK wallet not loaded for native balance read"
        }
        val balance = wallet.balance()
        return SdkBalanceSplitDuffs(confirmed = balance.confirmed, unconfirmed = balance.unconfirmed)
    }

    override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> = flow {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val db = database()
        // Wallet membership is the TXO join (tx rows are not wallet-scoped;
        // this app binds a single wallet) — the same convention the parity
        // probe's distinctTxCount uses.
        emitAll(
            combine(
                db.txoDao().observeByWallet(walletId),
                db.transactionDao().observeAll()
            ) { txos, txs ->
                val walletTxids = HashSet<String>()
                for (row in txos) {
                    row.txid?.let { walletTxids += wireHex(it) }
                    row.spendingTxid?.let { walletTxids += wireHex(it) }
                }
                txs.filter { wireHex(it.txid) in walletTxids }
                    .map {
                        l1TxUiRecord(
                            txidWireBytes = it.txid,
                            netAmountDuffs = it.netAmount,
                            feeDuffs = it.fee,
                            contextCode = it.context,
                            directionCode = it.direction,
                            firstSeenSec = it.firstSeen,
                            blockTimestampSec = it.blockTimestamp
                        )
                    }
            }
        )
    }

    override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> = flow {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val db = database()
        emitAll(
            combine(
                db.txoDao().observeByWallet(walletId),
                db.transactionDao().observeAll()
            ) { txos, txs ->
                // Wallet membership via the TXO join, exactly like
                // observeWalletTxRecords (single-wallet app convention).
                val walletTxids = HashSet<String>()
                val mineOutpoints = HashSet<String>()
                val spenderByOutpoint = HashMap<String, String>()
                for (row in txos) {
                    val txidHex = row.txid?.let { displayHex(it) } ?: continue
                    walletTxids += txidHex
                    val outpointKey = "$txidHex:${row.vout}"
                    mineOutpoints += outpointKey
                    row.spendingTxid?.let { spender ->
                        val spenderHex = displayHex(spender)
                        walletTxids += spenderHex
                        spenderByOutpoint[outpointKey] = spenderHex
                    }
                }
                val payloadByTxid = HashMap<String, ByteArray>(txs.size)
                val records = ArrayList<L1TxUiRecord>()
                for (tx in txs) {
                    val txidHex = displayHex(tx.txid)
                    payloadByTxid[txidHex] = tx.transactionData
                    if (txidHex in walletTxids) {
                        records += l1TxUiRecord(
                            txidWireBytes = tx.txid,
                            netAmountDuffs = tx.netAmount,
                            feeDuffs = tx.fee,
                            contextCode = tx.context,
                            directionCode = tx.direction,
                            firstSeenSec = tx.firstSeen,
                            blockTimestampSec = tx.blockTimestamp
                        )
                    }
                }
                SdkSeamTxSnapshot(records, payloadByTxid, mineOutpoints, spenderByOutpoint)
            }
        )
    }

    private fun wireHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun displayHex(bytes: ByteArray): String =
        bytes.reversedArray().joinToString("") { "%02x".format(it) }
}

// ── The service ───────────────────────────────────────────────────────

/**
 * Phase 5d follow-up: POST-CUTOVER UI DATA SOURCE. Once the cutover is
 * COMMITTED (persisted state CUT_OVER/SETTLED — the SAME predicate the
 * send path's [SdkL1SendService.cutoverCommitted] evaluates, observed
 * reactively here), the dashj wallet is held/frozen at its cutover
 * height, so every dashj-fed home-screen surface goes stale:
 * - the balance header freezes (live bug: an SDK-received tx never moved it),
 * - the tx list never learns of SDK-side receives (live bug: "L1ParityDiff
 *   … sdk-only" tx invisible in the UI),
 * - a send stays "Sending" forever (dashj never sees the IS-lock),
 * - the coins-received notification (a dashj wallet listener) never fires.
 *
 * This service closes that gap from the SDK's own L1 view WITHOUT
 * mirroring: it never constructs dashj Transactions and never leaks SDK
 * JNI types. It feeds the EXISTING neutral seams —
 * - the total-balance flow behind
 *   [org.dash.wallet.common.WalletDataProvider.observeTotalBalance]
 *   (every consumer switches at once via [overlayTotalBalance]), and
 * - the Room display cache the tx list pages from ([TxDisplayCacheDao] —
 *   `tx_display_cache` rows ARE the app's neutral display model;
 *   Room invalidation refreshes the pager automatically),
 * plus a coins-received notification for freshly-discovered receives.
 *
 * ## Instant receives (the wait-for-block gap fix)
 *
 * The Room snapshot flow alone only surfaces a receive once the engine's
 * persistence pass lands the rows — observed live as receives appearing
 * only after a BLOCK confirmed them. The tx pipeline therefore also
 * consumes the engine's per-transaction events
 * ([L1ShadowSyncService.txEvents]: `TransactionDetected` /
 * `TransactionInstantLocked`, emitted by the dash-spv mempool tracker at
 * first sighting): a detected incoming tx is inserted as a PENDING row
 * (and notified) immediately, and the IS lock flips the row's state the
 * moment it lands — both pre-block. Every event runs through the same
 * planner keyed by txid, so the later Room/block sighting of the same tx
 * updates the SAME row (no double-appearance, no re-notification).
 *
 * ## Pre-cutover: provably inert
 *
 * [cutoverUiActive] is false for every install until a deliberate cutover
 * commit, so [sdkTotalBalance] stays null (→ [overlayTotalBalance] passes
 * dashj values through unchanged) and no pipeline, SDK call or Room write
 * ever runs. A rollback (CUT_OVER → DUAL_RUNNING) cancels the pipelines
 * and clears the balance override on the next DataStore emission.
 *
 * ## Self-authored sends
 *
 * [de.schildbach.wallet.payments.SendCoinsTaskRunner] still commits
 * self-authored SDK sends into the dashj wallet synchronously (rollback
 * coherence). That commit produces the initial "Sending" display row via
 * the dashj pipeline; THIS service flips it to "Sent" when the SDK
 * records the IS-lock — the UI no longer depends on dashj confidence.
 */
@Singleton
class CutoverUiDataService internal constructor(
    private val source: CutoverUiSource,
    private val dashPayConfig: DashPayConfig,
    private val scope: CoroutineScope,
    private val txDisplayCacheDao: TxDisplayCacheDao,
    private val txGroupCacheDao: TxGroupCacheDao,
    private val walletUIConfig: WalletUIConfig,
    /**
     * Current-rate source for stamping fresh SDK-discovered receives, the same
     * DAO BlockchainServiceImpl.onCoinsReceived reads. Nullable/default-null so
     * the snapshot-only test constructor (which asserts no rate) needs no rate DB.
     */
    private val exchangeRatesDao: ExchangeRatesDao? = null,
    private val resolveString: (Int) -> String,
    private val notifyCoinsReceived: (Long) -> Unit,
    /**
     * Classifies an INTERNAL/COINJOIN row's txid as a Platform-funding asset
     * lock (identity upgrade / top-up / invite) so the list renders the SENT
     * "…Fee" label instead of "Internal". A fast Room/DataStore probe; returns
     * null for a plain internal move. Default null-returning for the snapshot
     * tests (which assert plain send/receive behaviour only).
     */
    private val resolveAssetLockKind: suspend (String) -> AssetLockKind? = { null },
    /**
     * The engine's instant tx feed ([L1ShadowSyncService.txEvents]) —
     * mempool detections and IS locks, consumed by [txPipeline] ahead of
     * the Room snapshot so receives render pre-block. Empty by default
     * (tests that only exercise the snapshot path need no events).
     */
    private val txEvents: Flow<L1TxEvent> = emptyFlow(),
    /**
     * Whether the engine's wallet-event tap behind [txEvents] is live
     * ([L1ShadowSyncService.isTapActive]) — observability only: when the
     * cutover is committed but the tap never started (the tap is gated on
     * USE_KOTLIN_SDK_L1_SHADOW while this service gates on CUTOVER_STATE),
     * instant receives silently degrade to the Room-snapshot cadence, and
     * [runPipelines] logs the mismatch once per process. Default true so
     * the fake-fed test constructor never warns.
     */
    private val isTxFeedTapActive: () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
    private val walletBindRetryMs: Long = WALLET_BIND_RETRY_MS,
    private val txFeedRetryMs: Long = TX_FEED_RETRY_MS
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope,
        txDisplayCacheDao: TxDisplayCacheDao,
        txGroupCacheDao: TxGroupCacheDao,
        walletUIConfig: WalletUIConfig,
        exchangeRatesDao: ExchangeRatesDao,
        configuration: Configuration,
        notificationService: NotificationService,
        l1ShadowSyncService: L1ShadowSyncService,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        source = DashSdkCutoverUiSource(sdkService),
        dashPayConfig = dashPayConfig,
        scope = scope,
        txDisplayCacheDao = txDisplayCacheDao,
        txGroupCacheDao = txGroupCacheDao,
        walletUIConfig = walletUIConfig,
        exchangeRatesDao = exchangeRatesDao,
        resolveAssetLockKind = { txDisplayHex -> assetLockKindResolver.kindFor(txDisplayHex) },
        txEvents = l1ShadowSyncService.txEvents,
        isTxFeedTapActive = { l1ShadowSyncService.isTapActive },
        resolveString = { resId -> context.getString(resId) },
        notifyCoinsReceived = { duffs ->
            notificationService.showNotification(
                "sdk_coins_received",
                context.getString(
                    R.string.notification_coins_received_msg,
                    configuration.format.format(Coin.valueOf(duffs)).toString()
                ),
                null,
                null,
                MainActivity.createIntent(context),
                Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS
            )
        }
    )

    private val started = AtomicBoolean(false)

    /**
     * Once-per-process coins-received belt over the structural (row
     * already exists) dedup: the display row is the primary guard, but a
     * dashj-side cache rebuild can briefly drop event-born rows, and this
     * set keeps even that window from re-notifying an already-announced
     * txid. Only ever touched from [txPipeline]'s sequential collector.
     */
    private val notifiedTxIds = mutableSetOf<String>()

    /**
     * Directions seen per txid across engine [L1TxEvent.Detected] events —
     * the multi-account SELF-SPEND guard. The engine emits one Detected
     * per affected account (per-account net_amount/direction), so a tx
     * spending account A → paying account B of the SAME wallet produces
     * two same-txid events; without this, the Incoming sibling would fire
     * a "coins received" notification for an internal transfer. When an
     * Incoming event finds an Outgoing sibling already recorded here, its
     * notification is suppressed (the row itself is already structurally
     * deduped by txid — the Outgoing-born row wins and keeps its
     * "Sending"/"Sent" title).
     *
     * KNOWN LIMITATION (Incoming-FIRST ordering): if the engine emits the
     * Incoming sibling first, the row titles "Received" and the
     * notification fires before the Outgoing sibling is seen — with the
     * single sequential collector there is no sibling signal to wait on
     * without delaying every genuine receive, so the first-order case is
     * accepted. Reachability today is nil: the app binds a single BIP44
     * account, so no tx can touch two accounts of the same SDK wallet.
     * When CoinJoin/identity-funding accounts land, the worst case is one
     * spurious notification and a mis-titled row that the direction-aware
     * Room record does not rewrite (status-only updates) — cosmetic, never
     * a balance error. Insertion-ordered and capped ([SEEN_TX_DIRECTIONS_MAX],
     * eldest evicted) so a long-lived process cannot grow it unboundedly.
     * Only ever touched from [txPipeline]'s sequential collector.
     */
    private val seenEventDirections =
        object : LinkedHashMap<String, MutableSet<L1TxUiDirection>>() {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, MutableSet<L1TxUiDirection>>
            ): Boolean = size > SEEN_TX_DIRECTIONS_MAX
        }

    /** Once-per-process latch for the [runPipelines] tap-mismatch WARN (FIX: silent gate mismatch). */
    private val tapGapWarned = AtomicBoolean(false)

    private val _sdkTotalBalance = MutableStateFlow<Coin?>(null)

    /**
     * The SDK's live total L1 balance, or null while the cutover UI feed
     * is inactive (pre-cutover, rolled back, or no data yet).
     */
    val sdkTotalBalance: StateFlow<Coin?> = _sdkTotalBalance.asStateFlow()

    private val _sdkConfirmedBalance = MutableStateFlow<Coin?>(null)

    /**
     * The SDK's live CONFIRMED L1 balance (in-a-block OR InstantSend-locked),
     * or null while the cutover UI feed is inactive. This is the subset the
     * asset-lock funding selection will actually spend (`is_confirmed ||
     * is_instantlocked`), so the shielded-transfer screen uses it as the
     * transferable/Max limit; the total − confirmed remainder is the
     * still-"pending" (mature-but-unconfirmed) portion. Moves in lockstep with
     * [sdkTotalBalance] (both come from one `balance()` read in [refreshNativeSplit]).
     */
    val sdkConfirmedBalance: StateFlow<Coin?> = _sdkConfirmedBalance.asStateFlow()

    /** Synchronous read for [de.schildbach.wallet.WalletApplication.getWalletBalance]. */
    fun sdkBalanceOrNull(): Coin? = _sdkTotalBalance.value

    /**
     * The cutover-aware total-balance feed:
     * [de.schildbach.wallet.WalletApplication.observeTotalBalance] wraps
     * its dashj [WalletBalanceObserver][de.schildbach.wallet.transactions.WalletBalanceObserver]
     * flow with this. Pre-cutover the SDK side is permanently null, so
     * dashj values pass through unchanged (flags-off regression safety);
     * post-cutover the SDK balance wins as soon as it exists.
     */
    fun overlayTotalBalance(dashjBalance: Flow<Coin>): Flow<Coin> =
        _sdkTotalBalance.combine(dashjBalance) { sdk, dashj -> sdk ?: dashj }

    /**
     * The UI cutover gate: persisted [DashPayConfig.CUTOVER_STATE] mapped
     * through the SAME predicate as [SdkL1SendService.cutoverCommitted]
     * (`!dashjEngineMayStart`), observed reactively. Fails closed (dashj)
     * on any read error.
     */
    internal fun cutoverUiActive(): Flow<Boolean> =
        dashPayConfig.observe(DashPayConfig.CUTOVER_STATE)
            .map { stored -> !dashjEngineMayStart(CutoverState.fromStored(stored)) }
            .catch { e ->
                log.warn("failed to read the cutover state; UI stays on dashj", e)
                emit(false)
            }

    /**
     * Idempotent once-per-process start (call site:
     * [de.schildbach.wallet.service.platform.PlatformSynchronizationService]'s
     * SDK-engine kick, alongside [L1ShadowSyncService.startIfEnabled]).
     * Everything after the gate is contained — a pipeline failure logs,
     * clears the balance override and leaves dashj-fed UI in place.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            cutoverUiActive()
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) {
                        _sdkTotalBalance.value = null
                        _sdkConfirmedBalance.value = null
                        return@collectLatest
                    }
                    log.info("cutover committed — serving home-screen data from the SDK")
                    try {
                        runPipelines()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.error("cutover UI pipelines failed; balance override cleared", t)
                        _sdkTotalBalance.value = null
                        _sdkConfirmedBalance.value = null
                    }
                }
        }
    }

    private suspend fun runPipelines() = coroutineScope {
        val walletIdHex = awaitBoundWallet()
        // Observability: the instant-receive tap is gated on
        // USE_KOTLIN_SDK_L1_SHADOW ([L1ShadowSyncService.startIfEnabled])
        // while THIS service gates on CUTOVER_STATE. A committed cutover
        // with the shadow flag off (or the shadow not yet started — a
        // startup race makes this a best-effort hint, not a hard error)
        // silently degrades receives to block cadence; say so once.
        if (!isTxFeedTapActive() && tapGapWarned.compareAndSet(false, true)) {
            log.warn(
                "cutover UI active but the engine wallet-event tap is not running " +
                    "(USE_KOTLIN_SDK_L1_SHADOW off, or L1ShadowSyncService not started) — " +
                    "instant receives degrade to the Room-snapshot/block cadence"
            )
        }
        launch { balancePipeline(walletIdHex) }
        launch { txPipeline(walletIdHex) }
    }

    /** The SDK bind (SdkWalletBinder) can lag app start — poll until bound. */
    private suspend fun awaitBoundWallet(): String {
        while (true) {
            val id = try {
                source.boundWalletIdOrNull()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK wallet lookup failed; retrying in {}ms", walletBindRetryMs, t)
                null
            }
            if (id != null) return id
            delay(walletBindRetryMs)
        }
    }

    private suspend fun balancePipeline(walletIdHex: String) = coroutineScope {
        // Seed the confirmed/total split once up front so the shielded screen's
        // Max (confirmed) and pending (total − confirmed) are populated before
        // the first tx event or ticker tick.
        try {
            refreshNativeSplit(walletIdHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("initial SDK balance split read failed", t)
        }
        // Belt-and-suspenders (Bug D). The Room-observable source
        // (observeUnspentByWallet) re-emits only on `txos` Room invalidation.
        // A SELF-AUTHORED send settles by a Rust-side spent-TXO write that does
        // NOT re-fire that Room flow, so after a max-send the balance override
        // could stay stale (non-zero) even though the store already reads 0
        // (the L1Parity `.first()` probe correctly sees 0). Keep the Room source
        // AND additionally re-read a one-shot snapshot on each engine tx event
        // (a self-spend emits Detected) and each ticker tick — guaranteeing a
        // re-read after a spend regardless of Room invalidation.
        launch {
            source.observeTotalDuffs(walletIdHex)
                .distinctUntilChanged()
                .catch { e -> log.error("SDK balance flow failed; balance override frozen", e) }
                .collect { duffs -> updateSdkBalance(duffs) }
        }
        launch {
            merge(txEvents.map { }, ticker()).collect {
                try {
                    // Re-read the SDK's NATIVE ledger split, NOT the Room txos sum:
                    // a self-send debits the native ledger at once but the spent-TXO
                    // marks land in Room only on block/IS-lock, so observeTotalDuffs()
                    // .first() would return the stale pre-send sum. The native read
                    // reaches 0 immediately after a max send. On error this throws and
                    // the catch keeps the prior override values (never zeroes the
                    // header on a transient read failure).
                    refreshNativeSplit(walletIdHex)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    log.warn("SDK balance snapshot re-read failed", t)
                }
            }
        }
    }

    /**
     * Re-read the SDK's native ledger once and publish BOTH the total
     * (confirmed+unconfirmed → [sdkTotalBalance], the home header) and the
     * confirmed-only feed ([sdkConfirmedBalance], the shielded Max). One
     * `balance()` read keeps the two in lockstep.
     */
    private suspend fun refreshNativeSplit(walletIdHex: String) {
        val split = source.currentBalanceSplitDuffs(walletIdHex)
        _sdkConfirmedBalance.value = Coin.valueOf(split.confirmed)
        updateSdkBalance(split.total)
    }

    private suspend fun updateSdkBalance(duffs: Long) {
        _sdkTotalBalance.value = Coin.valueOf(duffs)
        // Keep the fast-startup seed fresh (same key the dashj
        // WalletBalanceObserver maintains).
        runCatching { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, duffs) }
            .onFailure { log.warn("failed to persist LAST_TOTAL_BALANCE", it) }
    }

    /** One unit of tx-feed work, so both feeds share ONE sequential collector. */
    private sealed class TxFeedAction {
        data class Snapshot(val records: List<L1TxUiRecord>) : TxFeedAction()
        data class EngineEvent(val event: L1TxEvent) : TxFeedAction()
    }

    private suspend fun txPipeline(walletIdHex: String) {
        // Two feeds, one sequential collector (merge preserves per-feed
        // order and never runs two actions concurrently — that serial
        // execution is what makes the insert/notify dedup race-free):
        // - the Room snapshot flow (+ periodic ticker) — the CONVERGENT
        //   feed: re-runs the idempotent sync pass so a concurrent
        //   dashj-side cache rebuild can never permanently drop SDK rows;
        // - the engine's instant tx events — the FAST feed: a mempool
        //   receive renders (and notifies) the moment the engine sees the
        //   tx, and an IS lock flips the row before any block confirms it.
        //
        // Never dies silently: one upstream exception terminates the WHOLE
        // merged flow, so a single .catch would leave the tx list frozen
        // until the cutover state flaps — instead the collection is
        // re-entered after a backoff, the same never-dies discipline as
        // the tap ([L1ShadowSyncService.tapWalletEvents]).
        while (currentCoroutineContext().isActive) {
            try {
                merge(
                    combine(source.observeWalletTxRecords(walletIdHex), ticker()) { records, _ ->
                        TxFeedAction.Snapshot(records) as TxFeedAction
                    },
                    txEvents.map { TxFeedAction.EngineEvent(it) }
                ).collect { action ->
                    when (action) {
                        is TxFeedAction.Snapshot -> syncDisplayCache(action.records)
                        is TxFeedAction.EngineEvent -> handleTxEvent(action.event)
                    }
                }
                return // both upstream feeds completed normally
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.error("SDK tx feed failed; re-collecting in {}ms", txFeedRetryMs, t)
                delay(txFeedRetryMs)
            }
        }
    }

    /**
     * Apply one engine tx event ahead of Room persistence:
     * - [L1TxEvent.Detected] → the SAME planner pass as a snapshot, fed a
     *   single event-built record. Row absent → PENDING insert (+
     *   coins-received notification for a fresh incoming tx); row present
     *   (Room got there first, or a re-emit) → at most a surgical status
     *   update — never a duplicate row, never a second notification.
     *   Multi-account self-spend guard: an Incoming event whose txid
     *   already saw an Outgoing sibling event never notifies (see
     *   [seenEventDirections] for the design and the Incoming-first
     *   limitation).
     * - [L1TxEvent.InstantLocked] → [planL1InstantLockRowUpdate] on the
     *   existing row (grouped dashj-era rows excluded, mirroring the
     *   planner). Row absent → no-op; the Room snapshot reconciles later
     *   (the lock is already in the record's context by then).
     */
    private suspend fun handleTxEvent(event: L1TxEvent) {
        when (event) {
            is L1TxEvent.Detected -> {
                log.info(
                    "engine detected tx {} pre-block (context={}, net={} duffs) — syncing display row now",
                    event.txidHex, event.contextCode, event.netAmountDuffs
                )
                val record = l1TxUiRecordFromEvent(event, nowMs())
                val siblings = seenEventDirections.getOrPut(record.txidHex) { mutableSetOf() }
                if (record.direction == L1TxUiDirection.INCOMING &&
                    L1TxUiDirection.OUTGOING in siblings
                ) {
                    // Same-wallet self-spend: the Outgoing sibling event for
                    // this txid already arrived, so this "receive" is an
                    // internal transfer — pre-claim the notification slot so
                    // neither this pass nor a later snapshot pass announces
                    // it (seeding notifiedTxIds is exactly its semantics:
                    // "this txid must never notify again this process").
                    if (notifiedTxIds.add(record.txidHex)) {
                        log.info(
                            "tx {} has an outgoing sibling event (same-wallet self-spend) — " +
                                "suppressing the coins-received notification",
                            record.txidHex
                        )
                    }
                }
                siblings += record.direction
                syncDisplayCache(listOf(record))
            }
            is L1TxEvent.InstantLocked -> applyInstantLock(event.txidHex)
        }
    }

    private suspend fun applyInstantLock(txidHex: String) {
        try {
            val grouped = txGroupCacheDao.getGroupsForTxIds(listOf(txidHex))
                .any { it.groupId != it.txId }
            if (grouped) return
            val existing = txDisplayCacheDao.getEntriesByIds(listOf(txidHex)).firstOrNull() ?: return
            val updated = planL1InstantLockRowUpdate(existing, resolveString) ?: return
            txDisplayCacheDao.insertAll(listOf(updated))
            log.info("engine IS lock for {} — display row flipped pre-block", txidHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("IS-lock display refresh failed for {}", txidHex, t)
        }
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(refreshIntervalMs)
        }
    }

    private suspend fun syncDisplayCache(records: List<L1TxUiRecord>) {
        if (records.isEmpty()) return
        try {
            val txids = records.map { it.txidHex }
            // Chunked: SQLite's IN-clause variable cap is 999.
            val grouped = mutableSetOf<String>()
            val existing = mutableMapOf<String, TxDisplayCacheEntry>()
            for (chunk in txids.chunked(500)) {
                txGroupCacheDao.getGroupsForTxIds(chunk)
                    .filter { it.groupId != it.txId }
                    .forEach { grouped += it.txId }
                txDisplayCacheDao.getEntriesByIds(chunk).forEach { existing[it.rowId] = it }
            }

            // Read the current fiat rate once per pass to stamp on any fresh
            // incoming inserts, the same way BlockchainServiceImpl.onCoinsReceived
            // does (getRateSync of the selected currency). Guarded so a null/absent
            // or unparseable rate simply leaves the row's rate null.
            val fiat = runCatching {
                exchangeRatesDao
                    ?.getRateSync(walletUIConfig.getExchangeCurrencyCodeBlocking())
                    ?.fiat
            }.getOrNull()

            // Classify INTERNAL/COINJOIN rows as Platform-funding asset locks so
            // the mislabelled "Internal" row renders the SENT "…Fee" it funded.
            // Only these directions can be asset-lock funding — a fast app-side
            // Room/DataStore probe per candidate, never blocking the pipeline.
            val kindByTxid = mutableMapOf<String, AssetLockKind>()
            for (record in records) {
                if (record.txidHex in grouped) continue
                when (record.direction) {
                    L1TxUiDirection.INTERNAL, L1TxUiDirection.COINJOIN ->
                        resolveAssetLockKind(record.txidHex)?.let { kindByTxid[record.txidHex] = it }
                    L1TxUiDirection.INCOMING ->
                        // The unshield/withdraw (AssetUnlock, transactionTypeKind
                        // == 7) is recorded INCOMING but is a self-move — relabel
                        // it "Unshielded" and suppress its coins-received
                        // notification. Only the UNSHIELD classification is
                        // accepted here; a genuine external receive stays
                        // "Received" (the resolver returns null for it).
                        if (resolveAssetLockKind(record.txidHex) == AssetLockKind.UNSHIELD) {
                            kindByTxid[record.txidHex] = AssetLockKind.UNSHIELD
                        }
                    else -> {}
                }
            }

            val plan = planL1DisplaySync(
                records, existing, grouped, resolveString, nowMs(),
                incomingFiatCode = fiat?.currencyCode,
                incomingFiatValue = fiat?.value,
                kindByTxid = kindByTxid
            )
            if (plan.inserts.isNotEmpty() || plan.updates.isNotEmpty()) {
                txDisplayCacheDao.insertAll(plan.inserts + plan.updates)
                log.info(
                    "cutover UI display sync: {} SDK records → {} inserts, {} status updates",
                    records.size, plan.inserts.size, plan.updates.size
                )
            }
            for ((txidHex, duffs) in plan.notifyIncoming) {
                if (!notifiedTxIds.add(txidHex)) continue // once per txid per process
                log.info("SDK-discovered receive {} ({} duffs) — notifying", txidHex, duffs)
                runCatching { notifyCoinsReceived(duffs) }
                    .onFailure { log.warn("coins-received notification failed for {}", txidHex, it) }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("cutover UI display sync pass failed", t)
        }
    }

    companion object {
        internal const val REFRESH_INTERVAL_MS = 60_000L
        internal const val WALLET_BIND_RETRY_MS = 5_000L
        /** Backoff before re-collecting the merged tx feed after an exception. */
        internal const val TX_FEED_RETRY_MS = 5_000L
        /** [seenEventDirections] cap — eldest-evicted; ~64 chars/txid keeps this a few KB. */
        internal const val SEEN_TX_DIRECTIONS_MAX = 1_000
        private val log = LoggerFactory.getLogger(CutoverUiDataService::class.java)
    }
}
