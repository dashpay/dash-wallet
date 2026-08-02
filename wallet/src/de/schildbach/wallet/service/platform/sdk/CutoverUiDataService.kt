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
import de.schildbach.wallet.database.entity.TxGroupCacheEntry
import de.schildbach.wallet.service.DisplayCacheRefreshBus
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
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
        // NOTE (investigated 2026-07-31, decision: leave as-is): 2-participant
        // CoinJoin mixing rounds (testnet-only — mainnet pools require >=3
        // participants) come through as Outgoing/net=0 because the SDK's Rust
        // classifier requires >=3 inputs AND outputs to tag CoinJoin, so they
        // display as "Sent 0" instead of v11.9's "Mixing". Accepted testnet
        // cosmetic; the parity diagnostic's BALANCE_MATCH (purple) state covers
        // the corresponding tx-count deltas.
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
        txidHex = displayHexOf(txidWireBytes),
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
    assetLockKind: AssetLockKind? = null,
    // The DashPay contact this row pays to / receives from (IDENTITY only, from the
    // DIP-15 friendship match). Direction/amount do NOT come from the contact or the
    // SDK record's net (both unreliable for friendship txs) — they come from
    // [contactSignedNet].
    contact: ResolvedTxContact? = null,
    // The engine's AUTHORITATIVE signed wallet net for this tx (`net_amount` =
    // Σin−Σout over resolved wallet inputs/outputs), captured at event ingest by
    // [CutoverUiDataService] and threaded here. Negative = a send, positive = a
    // receive; its magnitude IS the display amount (fee/change already netted out).
    // Null when the engine net has not been observed for this txid (e.g. a
    // snapshot-only tx no event ever fired for) — the contact row then falls back to
    // the SDK record's own direction/value.
    contactSignedNet: Long? = null
): L1TxRowPlan {
    // A contact row's direction and amount are authored from the engine's own signed
    // wallet net — the SDK record's direction/netAmount columns are wrong for a
    // friendship send (they surface the wallet's +change output, not the −payment),
    // and the DIP-15 issued-address match proved unreliable on-device. A negative net
    // is a SEND (sent icon/flags, "Sending"/"Sent"); a positive net is a RECEIVE. The
    // signed net IS the value. When no engine net is known, fall through to the plain
    // direction planner (the contact IDENTITY is still stamped by the caller).
    if (contact != null && contactSignedNet != null) {
        return if (contactSignedNet < 0) {
            L1TxRowPlan(
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
                valueDuffs = contactSignedNet,
                timestampMs = record.timestampMs,
                isIncoming = false
            )
        } else {
            L1TxRowPlan(
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
                valueDuffs = contactSignedNet,
                timestampMs = record.timestampMs,
                isIncoming = true
            )
        }
    }
    return when (record.direction) {
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
        // The shield/unshield pair are pool self-transfers, not payments, so
        // per the product spec they carry the INTERNAL (double-arrows)
        // transfer icon; the fee-like kinds keep the sent arrow. Both keep
        // FLAG_SENT so the shield stays findable under the Sent filter.
        L1TxRowPlan(
            rowId = record.txidHex,
            titleRes = assetLockTitleRes(assetLockKind),
            statusRes = -1,
            iconType = if (assetLockKind.isPoolTransfer) {
                TxDisplayCacheEntry.ICON_INTERNAL
            } else {
                TxDisplayCacheEntry.ICON_SENT
            },
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
        // isIncoming). Per the product spec the row carries the INTERNAL
        // (double-arrows) transfer treatment — same icon+background as the
        // "Internal"/shield rows — while keeping its positive value and
        // FLAG_RECEIVED (the transparent balance really does go up, so it
        // stays findable under the Received filter).
        L1TxRowPlan(
            rowId = record.txidHex,
            titleRes = assetLockTitleRes(assetLockKind),
            statusRes = if (record.status == L1TxUiStatus.PENDING) {
                R.string.transaction_row_status_processing
            } else {
                -1
            },
            iconType = TxDisplayCacheEntry.ICON_INTERNAL,
            iconBgType = TxDisplayCacheEntry.BG_SENT,
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
}

/**
 * The shield/unshield pair moves value between the wallet and its own
 * shielded pool — a self-transfer, not a payment — so both list rows render
 * the INTERNAL (double-arrows) transfer icon instead of the sent/received
 * arrows (product spec; the fee-like kinds keep the sent arrow).
 */
internal val AssetLockKind.isPoolTransfer: Boolean
    get() = this == AssetLockKind.SHIELD || this == AssetLockKind.UNSHIELD

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

/**
 * Whether this group-cache row means "this tx is rendered as part of a MULTI-tx
 * group row" — a dashj-era CoinJoin/CrowdNode set whose display rowId is the
 * GROUP id, not the txid. Those rows are never planned/updated from an SDK
 * record (the SDK knows individual transactions, not dashj's grouping).
 *
 * ROOT CAUSE FIX (verified on-device, S22): this used to be written as
 * `groupId != txId`, which is WRONG for a plain single-tx wrapper — its
 * [TxGroupCacheEntry.groupId] is the txid in BASE58
 * ([de.schildbach.wallet.transactions.TransactionWrapperHelper.wrapTransactions],
 * `txId.toStringBase58()`) while [TxGroupCacheEntry.txId] and the display rowId
 * are the lowercase HEX txid. Every ordinary send/receive that went through a
 * dashj cache rebuild therefore looked "grouped" and was silently EXCLUDED from
 * every SDK sync pass, so a dashj misread (value 0 → green RECEIVED icon titled
 * "Sending", stuck "Processing") could never be corrected. The wrapper TYPE is
 * the exact, id-format-independent signal: [TxGroupCacheEntry.TYPE_SINGLE] is by
 * definition one tx = one row keyed by that tx's hex id.
 */
internal val TxGroupCacheEntry.isMultiTxGroupRow: Boolean
    get() = wrapperType != TxGroupCacheEntry.TYPE_SINGLE

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
    val notifyIncoming: List<Pair<String, Long>>,
    /**
     * Every rowId this pass placed under SDK AUTHORITY — the SDK has a definitive
     * `transactions` record for it and the row is not one of the never-touch
     * carve-outs (service/gift-card/error/CoinJoin group rows). Reported so the
     * caller can tell the dashj-side display writers
     * ([de.schildbach.wallet.service.TxDisplayCacheService]) never to rewrite these
     * rows' direction/value/title/status — the "SDK-stamped" signal that works for
     * NON-contact rows too (a contact row is additionally self-identifying via its
     * `contactUserId` column). Includes rows the planner left byte-identical: they
     * were VERIFIED against the SDK record this pass, which is exactly the same
     * authority claim.
     */
    val sdkAuthoritative: Set<String> = emptySet()
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
    kindByTxid: Map<String, AssetLockKind> = emptyMap(),
    // Resolved DashPay contact per txid, from the dashj DIP-15 resolver run
    // app-side before this pure planner runs — stamps the avatar/username on
    // the row's avatar/username. Empty = no contact attribution.
    contactByTxid: Map<String, ResolvedTxContact> = emptyMap(),
    // The engine's authoritative signed wallet net per txid, captured at event
    // ingest — the source of truth for a contact row's direction and amount (the SDK
    // record's own columns are wrong for a friendship send). Absent for a txid whose
    // engine net was never observed; the contact row then keeps the SDK record's
    // direction/value (and, once a row is cached correctly, is never regressed —
    // the re-plan only fires when an authoritative net is present).
    signedNetByTxid: Map<String, Long> = emptyMap(),
    // Whether [records] came from the SDK's persisted `transactions` table (the Room
    // SNAPSHOT feed) — one wallet-wide row per txid, and therefore DEFINITIVE for a
    // plain row's direction/amount. False for the engine's instant tx feed, whose
    // Detected events are PER-ACCOUNT: one multi-account self-spend emits both an
    // OUTGOING and an INCOMING event for the same txid, so an event record must never
    // re-shape an EXISTING row (the outgoing-born "Sending" row wins — see
    // [CutoverUiDataService.seenEventDirections]). Inserts and the surgical
    // status/kind/contact edges still apply to both feeds.
    restampFromDefinitiveRecord: Boolean = true
): L1DisplaySyncPlan {
    val inserts = mutableListOf<TxDisplayCacheEntry>()
    val updates = mutableListOf<TxDisplayCacheEntry>()
    val notify = mutableListOf<Pair<String, Long>>()
    val sdkAuthoritative = mutableSetOf<String>()

    // The three PLAIN direction titles. A cached row carrying one of them was
    // classified by its writer as an ordinary send/receive, so the SDK record may
    // re-author its whole display shape. Any other title means richer semantics
    // the SDK record cannot reproduce — "Internal", the CoinJoin family
    // ("Mixing"/"Create denominations"/"Mixing fee"/"Combine dust"), the
    // Platform asset-lock family ("Shielded"/"Unshielded"/"Upgrade Fee"/
    // "Invitation"/"Topup Fee"), masternode registration/update, "Mining reward"
    // and the gift-card/error labels — and is never re-stamped by the plain path.
    val plainDirectionTitles = setOf(
        resolve(R.string.transaction_row_status_sending),
        resolve(R.string.transaction_row_status_sent),
        resolve(R.string.transaction_row_status_received)
    )

    for (record in records) {
        if (record.txidHex in groupedTxIds) continue
        val contact = contactByTxid[record.txidHex]
        val contactSignedNet = signedNetByTxid[record.txidHex]
        val plan = planL1TxRow(record, kindByTxid[record.txidHex], contact, contactSignedNet)
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
                // Stamp the DIP-15 contact so the row shows the contact avatar
                // (the adapter renders it only when contact != null / the entry
                // carries a contactUsername+contactUserId). Null-safe: an
                // un-attributed SDK row keeps the direction icon, as before.
                contactUsername = contact?.username,
                contactDisplayName = contact?.displayName,
                contactAvatarUrl = contact?.avatarUrl,
                contactUserId = contact?.userId,
                filterFlags = plan.filterFlags
            )
            sdkAuthoritative += plan.rowId
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
        sdkAuthoritative += record.txidHex

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
        // Re-stamp an already-cached row's DISPLAY SHAPE once its Platform-funding
        // kind is known. Covers (a) the "Internal" → "…Fee" relabel race (the
        // ASSET_LOCK_TXID persist races the first tx-feed insert, so the asset-lock
        // row is usually cached as "Internal" before resolveAssetLockKind can
        // classify it — the ~60s re-resolve lands the UPGRADE label here); (b) an
        // unshield cached "Received" before its UNSHIELD classification resolved;
        // and (c) rows cached under an older kind treatment (e.g. shield/unshield
        // rows stamped with sent/received arrows before the transfer-icon spec).
        // Only title/icon/background/filter are touched — value, time, rate, memo
        // and contact fields are preserved via copy(). Idempotent: once the shape
        // matches the plan, nothing is written. The never-touch guards above
        // already excluded service/gift-card/error/CoinJoin rows, and kindByTxid
        // is only ever populated for INTERNAL/COINJOIN/INCOMING self-moves.
        if (kindByTxid[record.txidHex] != null) {
            val desiredTitle = resolve(plan.titleRes)
            if (updated.title != desiredTitle ||
                updated.iconType != plan.iconType ||
                updated.iconBgType != plan.iconBgType ||
                updated.filterFlags != plan.filterFlags
            ) {
                updated = updated.copy(
                    title = desiredTitle,
                    iconType = plan.iconType,
                    iconBgType = plan.iconBgType,
                    filterFlags = plan.filterFlags
                )
            }
        }
        // Re-stamp a PLAIN (non-contact, non-asset-lock) row whose cached display shape
        // DISAGREES with the SDK's definitive record. Post-cutover the SDK's
        // `transactions` row (direction + netAmount + context) is the source of truth for
        // an ordinary send/receive, and the dashj-side writers can only MISREAD an
        // SDK-authored send: with the funding inputs unconnected dashj values it at net 0,
        // which `TransactionRowView.fromTransaction` renders as a GREEN RECEIVED icon
        // titled "Sending" and stuck on "Processing" (verified on-device: a confirmed
        // −0.9645 max-send). The older repairs only covered rows carrying a contact
        // identity (or a value-0 row); a plain send fell through both, so nothing ever
        // corrected it. Carve-outs, all preserved:
        //  - service/gift-card/error/CoinJoin rows already `continue`d above;
        //  - asset-lock kinds (Shielded/Unshielded/Upgrade Fee/Invitation/Topup Fee) are
        //    excluded by kindByTxid and by [plainDirectionTitles];
        //  - contact rows are excluded here (they need the ENGINE's signed net — the SDK
        //    record's own net is the wrong +change for a friendship send) and handled by
        //    the authoritative re-plan below; the cached contact columns are checked too,
        //    so a pass whose contact resolution transiently failed still cannot regress an
        //    already-attributed row;
        //  - INTERNAL/COINJOIN records and zero-net records are not "definitive" (a
        //    2-participant testnet mixing round arrives as Outgoing/net=0), so a row is
        //    never re-labelled from one of those;
        //  - memo, exchange rate, time, transactionAmount, service and contact columns are
        //    untouched (copy() of the derived display fields only).
        // Idempotent: once the row equals the plan nothing is written, so a settled row
        // produces no update on any later pass.
        val definitiveRecord = restampFromDefinitiveRecord &&
            kindByTxid[record.txidHex] == null &&
            contact == null &&
            updated.contactUserId == null &&
            updated.contactUsername == null &&
            record.netAmountDuffs != 0L &&
            (record.direction == L1TxUiDirection.OUTGOING || record.direction == L1TxUiDirection.INCOMING)
        if (definitiveRecord && updated.title in plainDirectionTitles) {
            if (updated.iconType != plan.iconType ||
                updated.iconBgType != plan.iconBgType ||
                updated.filterFlags != plan.filterFlags ||
                updated.valueSatoshis != plan.valueDuffs
            ) {
                // A send carries no secondary status (OUTGOING plan.statusRes == -1), so
                // this clears the stale "Processing" a mislabelled RECEIVED row had
                // stamped. The one status the plan may NOT clear is "Confirming" on an
                // unlocked tx: dashj keeps it while BUILDING under 6 confirmations, and
                // the surgical rule above already owns that transition.
                val desiredStatus = if (plan.statusRes != -1) resolve(plan.statusRes) else ""
                val keepConfirming = !locked &&
                    updated.statusText == resolve(R.string.transaction_row_status_confirming)
                updated = updated.copy(
                    title = resolve(plan.titleRes),
                    iconType = plan.iconType,
                    iconBgType = plan.iconBgType,
                    filterFlags = plan.filterFlags,
                    valueSatoshis = plan.valueDuffs,
                    statusText = if (keepConfirming) updated.statusText else desiredStatus
                )
            }
        }
        if (contact != null) {
            // Always attach the contact IDENTITY the insert could not (identity/
            // contacts load after the SDK feed, so the first insert is cached
            // un-attributed). Cheap, idempotent, touches only derived fields.
            if (updated.contactUserId != contact.userId ||
                updated.contactUsername != contact.username ||
                updated.contactAvatarUrl != contact.avatarUrl ||
                updated.contactDisplayName != contact.displayName
            ) {
                updated = updated.copy(
                    contactUsername = contact.username,
                    contactDisplayName = contact.displayName,
                    contactAvatarUrl = contact.avatarUrl,
                    contactUserId = contact.userId
                )
            }
            // Re-plan a cached contact row's DIRECTION/AMOUNT/STATUS only when an
            // authoritative engine net is known ([contactSignedNet] present). A row
            // cached wrong (received / +change / stuck "Processing") — from before
            // the engine net was observed, or from an upgraded install — is corrected
            // to the send/receive shape derived from that net. Fully idempotent (once
            // corrected every field equals the plan). Gating on the authoritative net
            // is deliberate: without it we must NOT touch direction/amount, or a
            // snapshot pass (whose SDK net is the wrong +change) would REGRESS an
            // already-correct cached row. The never-touch guards above already
            // excluded service/gift-card/error/CoinJoin rows.
            if (contactSignedNet != null) {
                val desiredTitle = resolve(plan.titleRes)
                val desiredStatus = if (plan.statusRes != -1) resolve(plan.statusRes) else ""
                if (updated.iconType != plan.iconType ||
                    updated.iconBgType != plan.iconBgType ||
                    updated.filterFlags != plan.filterFlags ||
                    updated.valueSatoshis != plan.valueDuffs ||
                    updated.title != desiredTitle ||
                    updated.statusText != desiredStatus
                ) {
                    updated = updated.copy(
                        title = desiredTitle,
                        iconType = plan.iconType,
                        iconBgType = plan.iconBgType,
                        filterFlags = plan.filterFlags,
                        // A send shows the fee-excluded sent value and no "Processing"
                        // (OUTGOING plan.statusRes == -1, clearing the stale text a
                        // mislabelled received row had stamped).
                        valueSatoshis = plan.valueDuffs,
                        statusText = desiredStatus
                    )
                }
            }
        }
        if (updated != existing) updates += updated
    }
    return L1DisplaySyncPlan(inserts, updates, notify, sdkAuthoritative)
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
 * @param payloadByTxid raw serialized transaction bytes for any SDK-known
 *        transaction, keyed by DISPLAY-order txid hex — the lookup that
 *        resolves an input's connected output. CONTRACT: consumers may only
 *        `get`/`containsKey` — the production source backs this with a LAZY
 *        per-txid Room lookup + small LRU ([DashSdkCutoverUiSource]) instead
 *        of materializing every payload per emission (the multi-day-sync OOM
 *        fix), so iteration/`size` see an empty view there. Test fixtures
 *        keep passing plain maps.
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

    /**
     * ONE-SHOT post-cutover MAX-SENDABLE display figure in duffs
     * ([maxSendableDuffs] over the manager's per-account balance
     * snapshot): BIP44 spendable + Σ sweepable receival accounts'
     * confirmed net of the per-sweep fee headroom — what the send-all
     * (sweep-then-drain, [SdkL1SendService]) actually delivers, before
     * the final drain's own fee. The wallet-wide total overstates this
     * whenever a DashPay contact's receival account holds funds the
     * BIP44 drain cannot see. Null when unavailable (no snapshot / no
     * BIP44 row) — the caller falls back to the wallet-wide total.
     * Default null: sources without account-level balances.
     */
    suspend fun currentMaxSendableDuffs(walletIdHex: String): Long? = null

    /**
     * ONE-SHOT count of the wallet's UNSPENT transaction outputs — the SDK
     * analogue of dashj's `calculateAllSpendCandidates(false, false).size`
     * that [org.dash.wallet.common.WalletDataProvider.spendableUtxoCount]
     * is contracted to return, i.e. the cardinality of the same output set
     * whose amounts the total balance sums.
     *
     * Null when unavailable. Default null: sources without a TXO store.
     */
    suspend fun currentSpendableUtxoCount(walletIdHex: String): Int? = null

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
        val db = database()
        // Trigger on any txos-table change (Room re-emits the count on every
        // invalidation, value-changed or not), but SUM in SQL — the old path
        // materialized every unspent TXO entity per change just to add up one
        // column (the unbounded-read class the multi-day-sync review flagged).
        emitAll(
            db.txoDao().countByWallet(walletId).map { queryUnspentSumDuffs(db, walletId) }
        )
    }

    private suspend fun queryUnspentSumDuffs(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        walletId: ByteArray
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        db.openHelper.readableDatabase.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                // Same predicate as the DAO's observeUnspentByWallet (isSpent = 0).
                "SELECT COALESCE(SUM(amount), 0) FROM txos WHERE walletId = ? AND isSpent = 0",
                arrayOf<Any?>(walletId)
            )
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    override suspend fun currentTotalDuffs(walletIdHex: String): Long =
        currentBalanceSplitDuffs(walletIdHex).total

    override suspend fun currentSpendableUtxoCount(walletIdHex: String): Int? {
        val walletId = walletIdFromHex(walletIdHex) ?: return null
        val db = database()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    // COUNT over EXACTLY the predicate queryUnspentSumDuffs sums,
                    // so the count and the total describe the same output set —
                    // which is the invariant dashj's calculateAllSpendCandidates
                    // / getBalance(ESTIMATED) pair has, and what
                    // WalletDataProvider.spendableUtxoCount is contracted to.
                    "SELECT COUNT(*) FROM txos WHERE walletId = ? AND isSpent = 0",
                    arrayOf<Any?>(walletId)
                )
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    override suspend fun currentMaxSendableDuffs(walletIdHex: String): Long? {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        // The same per-account snapshot the send path's receival
        // enumeration reads (PlatformWalletManager.accountBalances);
        // maxSendableDuffs returns null on a missing/malformed snapshot
        // and the service then falls back to the wallet-wide total.
        return maxSendableDuffs(manager().accountBalances(walletId))
    }

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
        // probe's distinct count uses. MULTI-DAY-SYNC FIX: the old combine
        // materialized the FULL `transactions` table (observeAll, every
        // payload blob included) plus the full wallet TXO table on EVERY Room
        // change — the "too many records" failure class. Now the two tables
        // only TRIGGER (cheap COUNT flows re-emitted per invalidation), the
        // rebuild is sampled to at most one per [SNAPSHOT_SAMPLE_MS], and the
        // snapshot queries fetch ONLY wallet-relevant rows without payloads.
        emitAll(
            snapshotTriggers(db, walletId)
                .sampleLatest(SNAPSHOT_SAMPLE_MS)
                .map { queryWalletTxRecords(db, walletId) }
        )
    }

    override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> = flow {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val db = database()
        // Same trigger/sample/bounded-query discipline as
        // observeWalletTxRecords (see there). Payloads are NOT materialized
        // per emission at all: the snapshot's payloadByTxid is a lazy
        // per-txid PK lookup with a small LRU (payload bytes are immutable,
        // so a cached entry can never go stale), which drops the per-emission
        // cost from "every payload blob in the store" to zero and the
        // retained heap to the LRU.
        emitAll(
            snapshotTriggers(db, walletId)
                .sampleLatest(SNAPSHOT_SAMPLE_MS)
                .map { querySeamSnapshot(db, walletId) }
        )
    }

    /**
     * Re-emits on every `txos`/`transactions` table invalidation (Room count
     * flows re-run per invalidation whether or not the value changed — that
     * non-dedup is load-bearing: a context-only UPDATE keeps the count but
     * must still refresh the snapshot for the status/confidence consumers).
     */
    private fun snapshotTriggers(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        walletId: ByteArray
    ): Flow<Unit> =
        combine(db.txoDao().countByWallet(walletId), db.transactionDao().count()) { _, _ -> }

    /** Light projection of one wallet TXO row (refs only — no entity, no blobs beyond txids). */
    private class TxoRef(val txid: ByteArray?, val vout: Int, val spendingTxid: ByteArray?)

    /** Light projection of one wallet-relevant `transactions` row (payload deliberately excluded). */
    private class TxRow(
        val txid: ByteArray,
        val netAmount: Long,
        val fee: Long?,
        val context: Int,
        val direction: Int,
        val firstSeen: Long,
        val blockTimestamp: Int
    )

    private fun queryWalletTxoRefs(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        walletId: ByteArray
    ): List<TxoRef> =
        db.openHelper.readableDatabase.query(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT txid, vout, spendingTxid FROM txos WHERE walletId = ?",
                arrayOf<Any?>(walletId)
            )
        ).use { cursor ->
            val out = ArrayList<TxoRef>(cursor.count)
            while (cursor.moveToNext()) {
                out += TxoRef(
                    txid = if (cursor.isNull(0)) null else cursor.getBlob(0),
                    vout = cursor.getInt(1),
                    spendingTxid = if (cursor.isNull(2)) null else cursor.getBlob(2)
                )
            }
            out
        }

    /**
     * The wallet-relevant `transactions` rows (no payload column), chunked
     * under SQLite's 999-variable cap and re-sorted to the DAO's
     * `ORDER BY firstSeen DESC` so record order matches the old
     * observeAll-based snapshot exactly.
     */
    private fun queryTxRowsByTxids(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        wireTxids: Collection<ByteArray>
    ): List<TxRow> {
        val out = ArrayList<TxRow>(wireTxids.size)
        for (chunk in wireTxids.chunked(TXID_IN_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT txid, netAmount, fee, context, direction, firstSeen, blockTimestamp " +
                        "FROM transactions WHERE txid IN ($placeholders)",
                    chunk.toTypedArray<Any?>()
                )
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    out += TxRow(
                        txid = cursor.getBlob(0),
                        netAmount = cursor.getLong(1),
                        fee = if (cursor.isNull(2)) null else cursor.getLong(2),
                        context = cursor.getInt(3),
                        direction = cursor.getInt(4),
                        firstSeen = cursor.getLong(5),
                        blockTimestamp = cursor.getInt(6)
                    )
                }
            }
        }
        out.sortByDescending { it.firstSeen }
        return out
    }

    private suspend fun queryWalletTxRecords(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        walletId: ByteArray
    ): List<L1TxUiRecord> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Same membership rule as before: every tx that funded a wallet TXO
        // plus every tx that spent one (spendingTxid counted even when the
        // row's txid FK is still null — the brief insert window).
        val wireByHex = LinkedHashMap<String, ByteArray>()
        for (ref in queryWalletTxoRefs(db, walletId)) {
            ref.txid?.let { wireByHex[wireHexOf(it)] = it }
            ref.spendingTxid?.let { wireByHex[wireHexOf(it)] = it }
        }
        queryTxRowsByTxids(db, wireByHex.values).map {
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

    private suspend fun querySeamSnapshot(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        walletId: ByteArray
    ): SdkSeamTxSnapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Wallet membership via the TXO join, exactly like the old combine:
        // rows whose txid FK is still null are skipped entirely (their
        // outpoint key would be meaningless), matching the previous
        // `row.txid?.let { … } ?: continue`.
        val mineOutpoints = HashSet<String>()
        val spenderByOutpoint = HashMap<String, String>()
        val wireByHex = LinkedHashMap<String, ByteArray>()
        for (ref in queryWalletTxoRefs(db, walletId)) {
            val txid = ref.txid ?: continue
            val txidHex = displayHexOf(txid)
            wireByHex[txidHex] = txid
            val outpointKey = "$txidHex:${ref.vout}"
            mineOutpoints += outpointKey
            ref.spendingTxid?.let { spender ->
                val spenderHex = displayHexOf(spender)
                wireByHex[spenderHex] = spender
                spenderByOutpoint[outpointKey] = spenderHex
            }
        }
        val records = queryTxRowsByTxids(db, wireByHex.values).map {
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
        SdkSeamTxSnapshot(
            walletRecords = records,
            payloadByTxid = LazyPayloadMap { displayHex -> loadPayload(db, displayHex) },
            mineOutpoints = mineOutpoints,
            spenderByOutpoint = spenderByOutpoint
        )
    }

    /**
     * Read-only get/containsKey view over the lazy per-txid payload loader —
     * see the [SdkSeamTxSnapshot.payloadByTxid] contract (consumers only ever
     * `get`; iteration sees an empty view).
     */
    private class LazyPayloadMap(
        private val lookup: (String) -> ByteArray?
    ) : Map<String, ByteArray> by emptyMap() {
        override fun get(key: String): ByteArray? = lookup(key)
        override fun containsKey(key: String): Boolean = lookup(key) != null
    }

    /**
     * LRU over lazily-loaded raw payload bytes, keyed by display txid hex.
     * Payload bytes for a txid are immutable, so entries never go stale; a
     * cached null ("tx not in the SDK store") CAN heal — the loader re-probes
     * once the entry is evicted, and the seam builder's dashj fallback covers
     * the interim, same as the old snapshot-miss behavior.
     */
    private val payloadLru = object : LinkedHashMap<String, ByteArray?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray?>): Boolean =
            size > PAYLOAD_LRU_MAX
    }

    private fun loadPayload(
        db: org.dashfoundation.dashsdk.persistence.DashDatabase,
        displayHex: String
    ): ByteArray? {
        synchronized(payloadLru) {
            if (payloadLru.containsKey(displayHex)) return payloadLru[displayHex]
        }
        val wireTxid = hexToBytesOrNull(displayHex.lowercase())?.reversedArray() ?: return null
        val payload = try {
            db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT transactionData FROM transactions WHERE txid = ? LIMIT 1",
                    arrayOf<Any?>(wireTxid)
                )
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0) else null
            }
        } catch (t: Throwable) {
            log.warn("lazy payload lookup failed for {}; seam falls back to dashj", displayHex, t)
            return null
        }
        synchronized(payloadLru) { payloadLru[displayHex] = payload }
        return payload
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DashSdkCutoverUiSource::class.java)

        /**
         * Snapshot rebuild sample period: during a heavy sync the SDK store
         * changes many times a second; one rebuild per second bounds the CPU
         * while conflation guarantees the FINAL state always lands.
         */
        const val SNAPSHOT_SAMPLE_MS = 1_000L

        /** Chunk size for raw `txid IN (…)` queries (SQLite's variable cap is 999). */
        const val TXID_IN_CHUNK = 500

        /** [payloadLru] cap; ~256 typical payloads is a few hundred KB at most. */
        const val PAYLOAD_LRU_MAX = 256
    }
}

/**
 * Emit the first upstream value immediately, then at most one value per
 * [periodMs] — always the LATEST (conflated). The debounce for the Room
 * trigger→snapshot pipelines: bursts of store writes coalesce into one
 * rebuild per period, and the final state is never dropped.
 */
private fun <T> Flow<T>.sampleLatest(periodMs: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(periodMs)
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
     * Fired immediately after every display-cache write/update so the reactive readers
     * (home Paging + contact-detail merge) force-refresh even when Room's InvalidationTracker
     * misses the change. Fire-and-forget — never blocks the sync pipeline.
     */
    private val displayCacheRefreshBus: DisplayCacheRefreshBus = DisplayCacheRefreshBus(),
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
     * Resolves the DashPay contact for one L1 txid via the dashj DIP-15
     * resolver ([SdkTxContactResolver]) — the SDK's neutral L1 view is not
     * friendship-keychain aware, so without this a payment to/from a contact
     * renders with the plain direction icon (and a send can render as a
     * RECEIVED row). Returns null for a plain non-contact tx (unchanged
     * behaviour). Default null-returning for the snapshot tests.
     */
    private val resolveContact: suspend (String) -> ResolvedTxContact? = { null },
    /**
     * Busts the contact resolver's NEGATIVE cache when
     * [requestContactReResolution] fires — without this a "no friendship
     * match" verdict cached pre-restore would suppress the very re-resolution
     * the signal exists to trigger. Default no-op for the snapshot tests.
     */
    private val clearContactResolutionCaches: () -> Unit = {},
    /**
     * The RESTART-SAFE signed wallet net per txid, derived from the SDK's persisted
     * TXO table ([SdkTxContactResolver.signedNetsFor], watch-only external friendship
     * TXOs excluded) — the PREFERRED authoritative direction/amount source for a
     * CONTACT row once its tx is confirmed/IS-locked (spent-TXO marks guaranteed
     * written; also covers an already-confirmed send after restart, for which no
     * [L1TxEvent.Detected] re-fires and [engineNetByTxid] is empty). Still-pending
     * txs use the live engine net only. Default empty for the snapshot tests.
     */
    private val resolveWalletNets: suspend (Set<String>) -> Map<String, Long> = { emptyMap() },
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
    /**
     * Whether the SDK L1 scan has caught up — the gate that decides whether
     * [overlayTotalBalance] publishes the LIVE SDK figure or holds the
     * last-known one, and whether [updateSdkBalance] is allowed to persist
     * [WalletUIConfig.LAST_TOTAL_BALANCE]. Fed from the same
     * `synced || scanCaughtUpToTip` predicate as the home header's blinking
     * "Syncing balance" label. Defaults to a constant `true` so the
     * fake-fed tests keep exercising the live/persisting path.
     */
    private val l1Synced: Flow<Boolean> = flowOf(true),
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
        displayCacheRefreshBus: DisplayCacheRefreshBus,
        exchangeRatesDao: ExchangeRatesDao,
        configuration: Configuration,
        notificationService: NotificationService,
        l1ShadowSyncService: L1ShadowSyncService,
        assetLockKindResolver: AssetLockKindResolver,
        sdkTxContactResolver: SdkTxContactResolver
    ) : this(
        source = DashSdkCutoverUiSource(sdkService),
        dashPayConfig = dashPayConfig,
        scope = scope,
        txDisplayCacheDao = txDisplayCacheDao,
        txGroupCacheDao = txGroupCacheDao,
        walletUIConfig = walletUIConfig,
        displayCacheRefreshBus = displayCacheRefreshBus,
        exchangeRatesDao = exchangeRatesDao,
        resolveAssetLockKind = { txDisplayHex -> assetLockKindResolver.kindFor(txDisplayHex) },
        resolveContact = { txDisplayHex -> sdkTxContactResolver.contactFor(txDisplayHex) },
        resolveWalletNets = { txids -> sdkTxContactResolver.signedNetsFor(txids) },
        clearContactResolutionCaches = { sdkTxContactResolver.clearNegativeCache() },
        txEvents = l1ShadowSyncService.txEvents,
        isTxFeedTapActive = { l1ShadowSyncService.isTapActive },
        // Same predicate as MainViewModel.sdkL1Synced (the blinking
        // "Syncing balance" label) — see [l1Synced].
        l1Synced = l1ShadowSyncService.progress.map { it.synced || it.scanCaughtUpToTip },
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
     * The engine's AUTHORITATIVE signed wallet net per txid (`net_amount` =
     * Σin−Σout over resolved wallet inputs/outputs), captured from each
     * [L1TxEvent.Detected] at ingest. This is the ONLY reliable direction/amount
     * signal for a DashPay contact tx: the SDK's persisted `transactions` row
     * carries the WRONG net for a friendship send (it surfaces the wallet's own
     * +change output, e.g. +3.83, instead of the −0.1 payment), and the DIP-15
     * issued-address match proved unreliable on-device. The contact-row planner
     * reads this map (threaded via [planL1DisplaySync]'s `signedNetByTxid`) to author
     * the send/receive shape and value. Only ever touched from [txPipeline]'s single
     * sequential collector (both feeds funnel through it), so a plain map is safe;
     * capped eldest-evicted so a long-lived process cannot grow it unboundedly.
     */
    private val engineNetByTxid =
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
                size > SEEN_TX_DIRECTIONS_MAX
        }

    /**
     * Once-per-process coins-received belt over the structural (row
     * already exists) dedup: the display row is the primary guard, but a
     * dashj-side cache rebuild can briefly drop event-born rows, and this
     * set keeps even that window from re-notifying an already-announced
     * txid. Only ever touched from [txPipeline]'s sequential collector.
     * Capped at [SEEN_TX_DIRECTIONS_MAX] (eldest evicted) like the sibling
     * maps, so a very large multi-day restore cannot grow it unboundedly;
     * an evicted txid is >1000 notifications old, far outside any realistic
     * re-notification window (the structural row guard still covers it).
     */
    private val notifiedTxIds: MutableSet<String> = boundedSet(SEEN_TX_DIRECTIONS_MAX)

    /**
     * Once-per-process latch for the "contact tx has no authoritative signed net"
     * WARN in [syncDisplayCache] — the pass retries every tick, so without the
     * latch an unresolvable tx would log every 60 s. Only ever touched from
     * [txPipeline]'s sequential collector. Capped like [notifiedTxIds]
     * (worst case after eviction: one repeated WARN per >1000-tx window).
     */
    private val noNetWarnedTxids: MutableSet<String> = boundedSet(SEEN_TX_DIRECTIONS_MAX)

    /**
     * Txids whose rows are TERMINAL for resolution purposes — CHAINLOCKED and
     * contact-ATTRIBUTED with an authoritative signed net applied (or verified
     * consistent). The 60s sync pass skips contact/asset-lock/net re-resolution
     * for them (the resolver N+1 fix): a settled, attributed, net-verified row
     * has nothing left to learn. Cleared whenever a contact re-resolution is
     * requested ([requestContactReResolution]) so the post-restore flow
     * re-verifies everything. Only touched from [txPipeline]'s sequential
     * collector (the request itself only flips [reResolveRequested]).
     * Bounded, eldest-evicted (an evicted txid merely re-verifies — idempotent).
     */
    private val terminalResolvedTxids: MutableSet<String> = boundedSet(TERMINAL_RESOLVED_MAX)

    /**
     * Set by [requestContactReResolution] (any thread), consumed at the top of
     * the next [syncDisplayCache] pass (the sequential collector) to clear
     * [terminalResolvedTxids] — the negative caches themselves are cleared
     * synchronously in the request (they are thread-safe).
     */
    private val reResolveRequested = AtomicBoolean(false)

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

    /**
     * On-demand contact RE-RESOLUTION requests ([requestContactReResolution]) —
     * merged with the periodic ticker into [txPipeline]'s snapshot feed, so a
     * request simply re-runs the NORMAL idempotent sync/plan pass
     * ([syncDisplayCache]) over the latest SDK records with FRESH contact
     * resolution. Post-restore, display rows are planned while the DIP-15
     * friendship keychains are still being recovered, so [resolveContact]
     * returns null and the rows are cached with contactUsername=NULL; once
     * contacts/keychains are (re)established this signal closes that gap
     * immediately instead of waiting for the next ticker tick. replay=0 +
     * buffer 1 + DROP_OLDEST: requests coalesce (every pass re-reads full
     * state), tryEmit never suspends/fails, and pre-cutover (no collector)
     * signals are dropped — provably inert.
     */
    private val contactReResolveRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Ask the cutover UI pipeline to re-run its sync/plan pass with fresh
     * DashPay contact resolution — call after contacts / friendship keychains
     * are (re)established (PlatformSyncService's addedContact path, which the
     * post-restore RestoreIdentityWorker→initSync(true) flow also runs
     * through). Fire-and-forget, non-suspending, idempotent (the pass only
     * writes rows whose planned fields actually differ) and inert pre-cutover.
     */
    fun requestContactReResolution() {
        // Bust every negative/terminal cache FIRST so the re-run pass genuinely
        // re-resolves (a stale "no match" cached while friendship keychains were
        // still being recovered must never survive this signal).
        clearContactResolutionCaches()
        reResolveRequested.set(true)
        contactReResolveRequests.tryEmit(Unit)
    }

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

    private val _sdkMaxSendable = MutableStateFlow<Coin?>(null)

    /**
     * The SDK's live MAX-SENDABLE figure ([CutoverUiSource.currentMaxSendableDuffs]:
     * BIP44 spendable + sweepable receival confirmed net of per-sweep fee
     * headroom — what the sweep-then-drain send-all actually delivers,
     * gross of the final drain fee), or null while the cutover UI feed is
     * inactive OR the account-level snapshot is unavailable (in which
     * case [overlayMaxSendableBalance] falls back to [sdkTotalBalance]).
     * Refreshed with the native split ([refreshNativeSplit]: initial
     * seed, engine tx events, ticker).
     */
    val sdkMaxSendable: StateFlow<Coin?> = _sdkMaxSendable.asStateFlow()

    /** Synchronous read for [de.schildbach.wallet.WalletApplication.getWalletBalance]. */
    fun sdkBalanceOrNull(): Coin? = _sdkTotalBalance.value

    private val _sdkSpendableUtxoCount = MutableStateFlow<Int?>(null)

    /**
     * The SDK's live UNSPENT-output COUNT
     * ([CutoverUiSource.currentSpendableUtxoCount]), or null while the
     * cutover UI feed is inactive / the read is unavailable. Refreshed on
     * the same cadence as the native balance split ([refreshNativeSplit]),
     * so the count and the balance always describe the same moment.
     */
    val sdkSpendableUtxoCount: StateFlow<Int?> = _sdkSpendableUtxoCount.asStateFlow()

    /**
     * Synchronous cutover overlay for
     * [de.schildbach.wallet.WalletApplication.spendableUtxoCount], or null
     * to keep the dashj value.
     *
     * ## Why this needed an overlay at all
     *
     * Unlike the balance, this count was never overlaid, so post-cutover it
     * reported the HELD dashj wallet's frozen UTXO set. Its only consumer is
     * the shielded max-fee reserve
     * ([de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve]), which
     * sizes the reserve at ~148 bytes per input: a stale count under-reserves
     * (the max-shield retry fails again) or over-reserves (the user cannot
     * shield their full balance).
     *
     * ## Why it does NOT hold a last-known value like the balance does
     *
     * The balance holds because a climbing mid-scan partial reads as FUND
     * LOSS to the user. This number is never displayed, so that reason does
     * not apply — but the mid-scan partial is still an UNDER-count, and
     * under-reserving is the failing direction (over-reserving merely leaves
     * a little more transparent, which the change output returns).
     *
     * So instead of holding a persisted last-known count (which would need a
     * new pref and could over-reserve indefinitely after a genuine
     * consolidation), the overlay simply does not engage until the SDK scan
     * has caught up — the SAME [_l1Synced] gate the balance hold uses. Until
     * then the dashj count passes through, which on an upgrade IS the real
     * pre-cutover count (a sound over-estimate) and on a fresh restore is 0
     * exactly as today, where there is nothing to shield anyway because the
     * shielded funding gate is closed for the whole of that window.
     */
    fun sdkSpendableUtxoCountOrNull(): Int? =
        _sdkSpendableUtxoCount.value?.takeIf { _l1Synced.value }

    /**
     * Whether the SDK's L1 scan has caught up. Mirrors EXACTLY the predicate
     * behind the home header's blinking "Syncing balance" label
     * ([de.schildbach.wallet.ui.main.MainViewModel.sdkL1Synced]:
     * `synced || scanCaughtUpToTip`) so the label and the displayed figure
     * flip in the same instant — a "Syncing balance" label over a live,
     * climbing figure (or a settled figure with the label still blinking)
     * would be worse than either state alone.
     */
    private val _l1Synced = MutableStateFlow(false)

    /**
     * The LAST KNOWN total balance ([WalletUIConfig.LAST_TOTAL_BALANCE], the
     * same fast-startup seed the dashj
     * [WalletBalanceObserver][de.schildbach.wallet.transactions.WalletBalanceObserver]
     * maintains), read ONCE at pipeline start — before any SDK figure is
     * published — or null when the key has never been written (first ever
     * run). This is what the header shows while the from-scratch
     * compact-filter scan is still running; see [overlayTotalBalance].
     */
    private val _lastKnownTotalBalance = MutableStateFlow<Coin?>(null)

    /**
     * The cutover-aware total-balance feed:
     * [de.schildbach.wallet.WalletApplication.observeTotalBalance] wraps
     * its dashj [WalletBalanceObserver][de.schildbach.wallet.transactions.WalletBalanceObserver]
     * flow with this. Pre-cutover the SDK side is permanently null, so
     * dashj values pass through unchanged (flags-off regression safety);
     * post-cutover the SDK balance wins as soon as it exists.
     *
     * ## Mid-scan: the LAST KNOWN figure, not the climbing partial
     *
     * On this build every wallet — UPGRADES included — cuts over on first
     * launch, and the SDK then does a from-scratch compact-filter scan
     * (5-20 min). Publishing the live SDK figure through that window makes
     * the header start near zero and climb, which on an upgrade reads as
     * FUND LOSS. So while [_l1Synced] is false the header holds the
     * last-known figure and the blinking "Syncing balance" label carries the
     * "this is not live yet" meaning; the live SDK figure takes over the
     * moment the scan catches up.
     *
     * Two deliberate fallbacks to the live figure:
     * - no last-known value at all (first ever run — nothing to hold), and
     * - a non-positive last-known value (a wallet that was empty last launch
     *   has no balance to "lose", and holding 0 over freshly-discovered
     *   funds would be the one case where the hold is the alarming state).
     */
    fun overlayTotalBalance(dashjBalance: Flow<Coin>): Flow<Coin> =
        combine(
            _sdkTotalBalance,
            _l1Synced,
            _lastKnownTotalBalance,
            dashjBalance
        ) { sdk, synced, lastKnown, dashj ->
            when {
                sdk == null -> dashj
                synced -> sdk
                lastKnown != null && lastKnown.isPositive -> lastKnown
                else -> sdk
            }
        }

    /**
     * The cutover-aware MAX-SENDABLE feed for the send screen
     * ([de.schildbach.wallet.WalletApplication.observeMaxOutputBalance]).
     * Pre-cutover both SDK sides are permanently null, so the dashj
     * max-output value passes through unchanged (byte-identical).
     * Post-cutover the account-aware max-sendable figure wins — the
     * wallet-wide total counts DashPay receival funds at face value while
     * the send-all delivers them net of the per-account sweep-fee
     * headroom, so the total would (slightly) overstate the true Max —
     * with the wallet-wide total as the fallback whenever the
     * account-level snapshot is unavailable (never a frozen dashj 0).
     */
    fun overlayMaxSendableBalance(dashjBalance: Flow<Coin>): Flow<Coin> =
        combine(_sdkMaxSendable, _sdkTotalBalance, dashjBalance) { maxSendable, total, dashj ->
            maxSendable ?: total ?: dashj
        }

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
                        _sdkMaxSendable.value = null
                        _sdkSpendableUtxoCount.value = null
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
                        _sdkMaxSendable.value = null
                        _sdkSpendableUtxoCount.value = null
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
        // FIRST: the scan-caught-up gate the balance publication depends on,
        // so [balancePipeline]'s very first read already sees the right value.
        launch {
            l1Synced
                .distinctUntilChanged()
                .catch { e -> log.error("SDK L1 sync-state feed failed; balance stays held", e) }
                .collect { _l1Synced.value = it }
        }
        launch { balancePipeline(walletIdHex) }
        launch { txPipeline(walletIdHex) }
    }

    /**
     * The SDK bind (SdkWalletBinder) can lag app start — poll until bound.
     * [CutoverUiSource.boundWalletIdOrNull] is `singleOrNull()` over the loaded
     * wallets, so it is ALSO null while more than one wallet is loaded (the
     * post-reset/restore orphan window, until the binder's orphan prune runs) —
     * that state used to park the ENTIRE cutover UI pipeline with zero log
     * output; now it says so once a minute.
     */
    private suspend fun awaitBoundWallet(): String {
        var attempts = 0
        while (true) {
            val id = try {
                source.boundWalletIdOrNull()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("SDK wallet lookup failed; retrying in {}ms", walletBindRetryMs, t)
                null
            }
            if (id != null) return id
            if (attempts++ % 12 == 0) {
                log.warn(
                    "cutover UI pipelines waiting for a SINGLE bound SDK wallet (none, or more than " +
                        "one loaded — post-reset orphan not pruned yet?); retrying every {}ms",
                    walletBindRetryMs
                )
            }
            delay(walletBindRetryMs)
        }
    }

    private suspend fun balancePipeline(walletIdHex: String) = coroutineScope {
        // Read the LAST KNOWN balance BEFORE the first SDK figure is published:
        // it is what the header shows for the whole mid-scan window (see
        // [overlayTotalBalance]). A read failure simply leaves it null, which
        // degrades to the pre-fix behaviour (the live figure) rather than
        // freezing the header on a value we could not read.
        _lastKnownTotalBalance.value = runCatching {
            walletUIConfig.get(WalletUIConfig.LAST_TOTAL_BALANCE)?.let(Coin::valueOf)
        }.onFailure { log.warn("failed to read LAST_TOTAL_BALANCE", it) }.getOrNull()

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
        // The max-sendable figure rides the same cadence (initial seed, tx
        // events, ticker). Contained separately: a failed/unavailable
        // account-level read must not undo the split above, and it drops
        // the override to null so overlayMaxSendableBalance falls back to
        // the wallet-wide total rather than freezing a stale max.
        try {
            _sdkMaxSendable.value = source.currentMaxSendableDuffs(walletIdHex)?.let(Coin::valueOf)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK max-sendable read failed; falling back to the wallet-wide total", t)
            _sdkMaxSendable.value = null
        }
        // The UTXO COUNT rides the same cadence so it always describes the
        // same moment as the split above (the shielded max-fee reserve sizes
        // itself from the count while shielding the balance). Contained the
        // same way: a failed read drops to null so the overlay falls back to
        // dashj rather than freezing a stale count.
        try {
            _sdkSpendableUtxoCount.value = source.currentSpendableUtxoCount(walletIdHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK spendable-UTXO-count read failed; falling back to the dashj count", t)
            _sdkSpendableUtxoCount.value = null
        }
    }

    private suspend fun updateSdkBalance(duffs: Long) {
        _sdkTotalBalance.value = Coin.valueOf(duffs)
        // Keep the fast-startup seed fresh (same key the dashj
        // WalletBalanceObserver maintains) — but ONLY once the scan has
        // caught up. Persisting a mid-scan PARTIAL compounds: it poisons the
        // very "last known" figure the next launch seeds and holds
        // ([overlayTotalBalance]), so a single interrupted scan would make
        // every later launch open on a wrong (too low) balance.
        if (!_l1Synced.value) return
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
                    // The snapshot pass re-runs on: a Room records change, the periodic
                    // ticker, or an explicit contact re-resolution request (fired when
                    // contacts/friendship keychains are (re)established — see
                    // [requestContactReResolution]); combine feeds it the latest records.
                    combine(
                        source.observeWalletTxRecords(walletIdHex),
                        merge(ticker(), contactReResolveRequests)
                    ) { records, _ ->
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
                // Capture the engine's authoritative signed wallet net for this txid
                // — the source of truth for a contact row's direction/amount, since
                // the SDK's persisted `transactions.netAmount` is wrong for a
                // friendship send. Keyed by display txid to match the planner.
                //
                // NEGATIVE WINS on same-txid sibling events (verified on-device, S22
                // sender): the engine emits one Detected per affected account, and a
                // self-authored friendship send touches BOTH the funding account
                // (OUTGOING, net = change − inputs = −(amount+fee) — the display-true
                // net) AND the watch-only external friendship account (INCOMING,
                // net = +amount — the CONTACT's money, not ours). Last-write-wins let
                // the +amount sibling clobber the correct negative net and the row
                // rendered as RECEIVED +0.05. Keeping the minimum keeps the funding
                // account's net regardless of event order; genuine receives only ever
                // see one positive event, so they are unaffected.
                val priorNet = engineNetByTxid[record.txidHex]
                if (priorNet == null || event.netAmountDuffs < priorNet) {
                    engineNetByTxid[record.txidHex] = event.netAmountDuffs
                }
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
                syncDisplayCache(listOf(record), fromEngineEvent = true)
            }
            is L1TxEvent.InstantLocked -> applyInstantLock(event.txidHex)
        }
    }

    private suspend fun applyInstantLock(txidHex: String) {
        try {
            val grouped = txGroupCacheDao.getGroupsForTxIds(listOf(txidHex))
                .any { it.isMultiTxGroupRow }
            if (grouped) return
            val existing = txDisplayCacheDao.getEntriesByIds(listOf(txidHex)).firstOrNull() ?: return
            val updated = planL1InstantLockRowUpdate(existing, resolveString) ?: return
            txDisplayCacheDao.insertAll(listOf(updated))
            displayCacheRefreshBus.markSdkAuthoritative(setOf(txidHex))
            // Force the reactive readers to refresh even if Room's tracker misses the flip.
            displayCacheRefreshBus.signalChanged()
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

    /**
     * @param fromEngineEvent true when [records] were lifted from the engine's instant
     *        PER-ACCOUNT tx feed rather than the SDK's persisted (wallet-wide, definitive)
     *        `transactions` snapshot — see [planL1DisplaySync]'s
     *        `restampFromDefinitiveRecord`.
     */
    private suspend fun syncDisplayCache(
        records: List<L1TxUiRecord>,
        fromEngineEvent: Boolean = false
    ) {
        if (records.isEmpty()) return
        try {
            // A contact re-resolution request voids every terminal-skip verdict:
            // the post-restore flow must re-resolve rows we previously deemed
            // settled (the negative caches were already busted in the request).
            if (reResolveRequested.compareAndSet(true, false)) {
                terminalResolvedTxids.clear()
            }
            val txids = records.map { it.txidHex }
            // Chunked: SQLite's IN-clause variable cap is 999.
            val grouped = mutableSetOf<String>()
            val existing = mutableMapOf<String, TxDisplayCacheEntry>()
            for (chunk in txids.chunked(500)) {
                txGroupCacheDao.getGroupsForTxIds(chunk)
                    .filter { it.isMultiTxGroupRow }
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
                // Terminal rows (CHAINLOCKED + attributed + net-verified — see
                // [terminalResolvedTxids]) have nothing left to learn: skip
                // every resolver probe for them.
                if (record.txidHex in terminalResolvedTxids) continue
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

            // Resolve the DIP-15 DashPay contact per row so the list shows the
            // contact avatar (and the SENT badge for a send the SDK marked
            // INCOMING) instead of a bare direction icon — the SDK L1 view is not
            // friendship-keychain aware. A fast app-side probe (held-wallet lookup
            // + the dashj resolver + one Room read), positive-cached in the
            // resolver so an already-attributed row is not re-walked each tick.
            // Asset-lock funding rows are Platform self-moves, never contact
            // payments, so they are skipped. Fail-soft: a null keeps the plain row.
            val contactByTxid = mutableMapOf<String, ResolvedTxContact>()
            for (record in records) {
                if (record.txidHex in grouped || record.txidHex in kindByTxid) continue
                if (record.txidHex in terminalResolvedTxids) continue
                // Live DIP-15 resolution first; when it fails (post-restore the
                // friendship keychains lag behind — identity not loaded yet, tx not
                // yet in the SDK store, keychain not re-provisioned) fall back to the
                // contact identity STAMPED ON THE CACHED ROW: a row that already
                // carries contactUserId is proof of attribution (a tx's contact never
                // changes), and without this fallback the whole authoritative-contact
                // re-plan below was silently skipped after a restore/restart — the
                // verified on-device hole that left dashj's fee-only −260 rewrite of
                // a −0.4 contact send uncorrected for minutes.
                val contact = resolveContact(record.txidHex)
                    ?: existing[record.txidHex]?.let { row ->
                        val userId = row.contactUserId
                        val username = row.contactUsername
                        if (userId != null && username != null) {
                            ResolvedTxContact(
                                username = username,
                                displayName = row.contactDisplayName,
                                avatarUrl = row.contactAvatarUrl,
                                userId = userId
                            )
                        } else {
                            null
                        }
                    }
                contact?.let { contactByTxid[record.txidHex] = it }
            }

            // Authoritative signed wallet net per CONTACT txid — the source of truth
            // for its direction/amount (the SDK record's own net is wrong for a
            // friendship send). Two sources, picked per tx by CONFIRMATION state:
            // - CONFIRMED/IS-locked tx → the RESTART-SAFE TXO-derived net (spent-TXO
            //   marks are guaranteed written by then, and signedNetsFor excludes the
            //   watch-only external friendship account, so it is immune to the
            //   +payment pollution a stale engine event could carry). This also
            //   re-corrects rows after an app restart, when the engine map is empty.
            // - Still-PENDING tx → the LIVE engine net only (pre-lock the TXO table
            //   may hold the +change output while the spent marks are missing, so a
            //   TXO net computed now could read POSITIVE for a send — never trust it
            //   before the lock/block lands).
            // Non-contact rows are never given a net (planL1TxRow only consults it
            // when a contact is present), so they are unaffected. A contact tx with
            // NO resolvable net is logged (once per txid per process): its cached
            // direction/amount cannot be verified or corrected this pass.
            val signedNetByTxid = HashMap<String, Long>()
            val statusByTxid = records.associate { it.txidHex to it.status }
            val confirmedContactTxids = contactByTxid.keys.filterTo(mutableSetOf()) {
                statusByTxid[it] != L1TxUiStatus.PENDING
            }
            if (confirmedContactTxids.isNotEmpty()) {
                resolveWalletNets(confirmedContactTxids)
                    .forEach { (txid, net) -> signedNetByTxid[txid] = net }
            }
            for (txid in contactByTxid.keys) {
                val txoNet = signedNetByTxid[txid] // TXO-derived (confirmed txs only)
                val liveNet = engineNetByTxid[txid]
                when {
                    // Both known → take the MORE NEGATIVE. For a send both equal
                    // −(amount+fee) so this is a no-op; it only bites when one side
                    // is polluted positive (a stale +payment engine sibling, or a
                    // TXO snapshot caught mid-write with the spent marks missing) —
                    // the funding-side negative net is the display-true one either
                    // way. A genuine receive has both positive and equal.
                    txoNet != null && liveNet != null ->
                        signedNetByTxid[txid] = minOf(txoNet, liveNet)
                    txoNet != null -> { /* already stored */ }
                    liveNet != null -> signedNetByTxid[txid] = liveNet
                    else -> if (noNetWarnedTxids.add(txid)) {
                        log.warn(
                            "contact tx {} has no authoritative signed net (no TXO-derived net, no " +
                                "live engine event) — cached row (value={} duffs, iconType={}) cannot " +
                                "be verified or corrected this pass; will keep retrying",
                            txid, existing[txid]?.valueSatoshis, existing[txid]?.iconType
                        )
                    }
                }
            }

            val plan = planL1DisplaySync(
                records, existing, grouped, resolveString, nowMs(),
                incomingFiatCode = fiat?.currencyCode,
                incomingFiatValue = fiat?.value,
                kindByTxid = kindByTxid,
                contactByTxid = contactByTxid,
                signedNetByTxid = signedNetByTxid,
                restampFromDefinitiveRecord = !fromEngineEvent
            )
            // Claim SDK AUTHORITY over every row this pass planned or verified, so the
            // dashj-side rebuild writers ([TxDisplayCacheService]) can never rewrite their
            // direction/value/title/status back to a dashj misread. Marked BEFORE the write
            // so a concurrent dashj rebuild cannot slip in between. Purely in-memory and
            // pre-cutover inert (this pass never runs); a fresh process simply re-claims
            // each row on its first sync pass.
            displayCacheRefreshBus.markSdkAuthoritative(plan.sdkAuthoritative)
            if (plan.inserts.isNotEmpty() || plan.updates.isNotEmpty()) {
                txDisplayCacheDao.insertAll(plan.inserts + plan.updates)
                // Force the reactive readers to refresh even if Room's tracker misses the write.
                displayCacheRefreshBus.signalChanged()
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

            // Mark rows now TERMINAL for resolution: CHAINLOCKED, contact-
            // attributed AND direction/amount verified against an authoritative
            // signed net this pass (the re-plan block just ran for them, so the
            // cached row equals the plan). Later passes skip their resolver
            // probes entirely; requestContactReResolution clears the set.
            for (record in records) {
                if (record.status != L1TxUiStatus.CHAINLOCKED) continue
                if (record.txidHex !in contactByTxid) continue
                if (signedNetByTxid.containsKey(record.txidHex)) {
                    terminalResolvedTxids += record.txidHex
                }
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

        /** [terminalResolvedTxids] cap — eldest-evicted (an evicted row just re-verifies, idempotent). */
        internal const val TERMINAL_RESOLVED_MAX = 4_096

        /** Insertion-ordered set that evicts its eldest entry beyond [maxSize]. */
        private fun boundedSet(maxSize: Int): MutableSet<String> =
            java.util.Collections.newSetFromMap(
                object : LinkedHashMap<String, Boolean>() {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
                        size > maxSize
                }
            )

        private val log = LoggerFactory.getLogger(CutoverUiDataService::class.java)
    }
}
