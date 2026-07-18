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
import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.database.entity.TxDisplayCacheEntry
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

internal fun planL1TxRow(record: L1TxUiRecord): L1TxRowPlan = when (record.direction) {
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
    L1TxUiDirection.INTERNAL, L1TxUiDirection.COINJOIN -> L1TxRowPlan(
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
    L1TxUiDirection.INCOMING -> L1TxRowPlan(
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
 *   - secondary "Processing"/"Confirming" cleared once INSTANT_LOCKED or
 *     CHAINLOCKED (dashj's own clearing edges).
 * Everything else is left byte-identical.
 */
internal fun planL1DisplaySync(
    records: List<L1TxUiRecord>,
    existingByRowId: Map<String, TxDisplayCacheEntry>,
    groupedTxIds: Set<String>,
    resolve: (Int) -> String,
    nowMs: Long,
    notifyWindowMs: Long = L1_NOTIFY_RECENCY_WINDOW_MS
): L1DisplaySyncPlan {
    val inserts = mutableListOf<TxDisplayCacheEntry>()
    val updates = mutableListOf<TxDisplayCacheEntry>()
    val notify = mutableListOf<Pair<String, Long>>()

    for (record in records) {
        if (record.txidHex in groupedTxIds) continue
        val plan = planL1TxRow(record)
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
                exchangeRateFiatCode = null,
                exchangeRateFiatValue = null,
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
        if (locked && updated.statusText.isNotEmpty() &&
            (updated.statusText == resolve(R.string.transaction_row_status_processing) ||
                updated.statusText == resolve(R.string.transaction_row_status_confirming))
        ) {
            updated = updated.copy(statusText = "")
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

    /** Live wallet-relevant transaction records, neutral shape. */
    fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>>
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

    private fun wireHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
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
    private val resolveString: (Int) -> String,
    private val notifyCoinsReceived: (Long) -> Unit,
    /**
     * The engine's instant tx feed ([L1ShadowSyncService.txEvents]) —
     * mempool detections and IS locks, consumed by [txPipeline] ahead of
     * the Room snapshot so receives render pre-block. Empty by default
     * (tests that only exercise the snapshot path need no events).
     */
    private val txEvents: Flow<L1TxEvent> = emptyFlow(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
    private val walletBindRetryMs: Long = WALLET_BIND_RETRY_MS
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
        configuration: Configuration,
        notificationService: NotificationService,
        l1ShadowSyncService: L1ShadowSyncService
    ) : this(
        source = DashSdkCutoverUiSource(sdkService),
        dashPayConfig = dashPayConfig,
        scope = scope,
        txDisplayCacheDao = txDisplayCacheDao,
        txGroupCacheDao = txGroupCacheDao,
        walletUIConfig = walletUIConfig,
        txEvents = l1ShadowSyncService.txEvents,
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

    private val _sdkTotalBalance = MutableStateFlow<Coin?>(null)

    /**
     * The SDK's live total L1 balance, or null while the cutover UI feed
     * is inactive (pre-cutover, rolled back, or no data yet).
     */
    val sdkTotalBalance: StateFlow<Coin?> = _sdkTotalBalance.asStateFlow()

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
                        return@collectLatest
                    }
                    log.info("cutover committed — serving home-screen data from the SDK")
                    try {
                        runPipelines()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        log.error("cutover UI pipelines failed; balance override cleared", t)
                        _sdkTotalBalance.value = null
                    }
                }
        }
    }

    private suspend fun runPipelines() = coroutineScope {
        val walletIdHex = awaitBoundWallet()
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

    private suspend fun balancePipeline(walletIdHex: String) {
        source.observeTotalDuffs(walletIdHex)
            .distinctUntilChanged()
            .catch { e -> log.error("SDK balance flow failed; balance override frozen", e) }
            .collect { duffs ->
                _sdkTotalBalance.value = Coin.valueOf(duffs)
                // Keep the fast-startup seed fresh (same key the dashj
                // WalletBalanceObserver maintains).
                runCatching { walletUIConfig.set(WalletUIConfig.LAST_TOTAL_BALANCE, duffs) }
                    .onFailure { log.warn("failed to persist LAST_TOTAL_BALANCE", it) }
            }
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
        merge(
            combine(source.observeWalletTxRecords(walletIdHex), ticker()) { records, _ ->
                TxFeedAction.Snapshot(records) as TxFeedAction
            },
            txEvents.map { TxFeedAction.EngineEvent(it) }
        )
            .catch { e -> log.error("SDK tx feed failed; tx list stays dashj-fed", e) }
            .collect { action ->
                when (action) {
                    is TxFeedAction.Snapshot -> syncDisplayCache(action.records)
                    is TxFeedAction.EngineEvent -> handleTxEvent(action.event)
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
                syncDisplayCache(listOf(l1TxUiRecordFromEvent(event, nowMs())))
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

            val plan = planL1DisplaySync(records, existing, grouped, resolveString, nowMs())
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
        private val log = LoggerFactory.getLogger(CutoverUiDataService::class.java)
    }
}
