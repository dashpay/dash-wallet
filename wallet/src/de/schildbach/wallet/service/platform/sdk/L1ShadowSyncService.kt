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
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.wallet.Wallet.BalanceType
import org.dash.wallet.common.WalletDataProvider
import org.dashfoundation.dashsdk.wallet.SpvSyncProgressData
import org.dashfoundation.dashsdk.wallet.SpvSyncState
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ── App-side progress shape ───────────────────────────────────────────

/**
 * Which stage of the shadow SPV pipeline is currently the bottleneck, in
 * the Rust client's phase order (headers → filter headers → masternodes →
 * filters).
 */
enum class ShadowSyncPhase { IDLE, CONNECTING, HEADERS, FILTER_HEADERS, MASTERNODES, FILTERS, SYNCED, ERROR }

/**
 * Small app-side view of the SDK's
 * [org.dashfoundation.dashsdk.wallet.SpvSyncProgressData], for logging and
 * a future debug UI. Header heights track the chain tip; filter heights
 * track how far the wallet-relevant compact-filter scan has advanced.
 */
data class ShadowSyncProgress(
    val phase: ShadowSyncPhase,
    val overallPercent: Double,
    val headerHeight: Long,
    val headerTarget: Long,
    val filterHeight: Long,
    val filterTarget: Long
) {
    /** The shadow chain is fully synced — parity mismatches count as real from here. */
    val synced: Boolean get() = phase == ShadowSyncPhase.SYNCED

    companion object {
        val IDLE = ShadowSyncProgress(ShadowSyncPhase.IDLE, 0.0, 0, 0, 0, 0)
    }
}

/**
 * Map the SDK's SPV progress snapshot to the app-side shape. Pure — the
 * SDK type is a plain data class, so this is host-JVM unit-testable.
 * While the overall state is SYNCING, the phase is the FIRST pipeline
 * stage that has not reached SYNCED yet.
 */
internal fun toShadowSyncProgress(data: SpvSyncProgressData): ShadowSyncProgress {
    fun pending(sub: org.dashfoundation.dashsdk.wallet.SpvSubProgress?): Boolean =
        sub != null && sub.state != SpvSyncState.SYNCED

    val phase = when (data.overallState) {
        SpvSyncState.WAIT_FOR_EVENTS -> ShadowSyncPhase.IDLE
        SpvSyncState.WAITING_FOR_CONNECTIONS -> ShadowSyncPhase.CONNECTING
        SpvSyncState.SYNCED -> ShadowSyncPhase.SYNCED
        SpvSyncState.ERROR -> ShadowSyncPhase.ERROR
        SpvSyncState.SYNCING -> when {
            pending(data.headers) -> ShadowSyncPhase.HEADERS
            pending(data.filterHeaders) -> ShadowSyncPhase.FILTER_HEADERS
            pending(data.masternodes) -> ShadowSyncPhase.MASTERNODES
            else -> ShadowSyncPhase.FILTERS
        }
    }
    return ShadowSyncProgress(
        phase = phase,
        overallPercent = data.overallPercentage,
        headerHeight = data.headers?.currentHeight ?: 0,
        headerTarget = data.headers?.targetHeight ?: 0,
        filterHeight = data.filters?.currentHeight ?: 0,
        filterTarget = data.filters?.targetHeight ?: 0
    )
}

/** The throttled `L1Shadow` one-line progress summary. Pure for tests. */
internal fun shadowProgressLine(p: ShadowSyncProgress): String = String.format(
    Locale.US,
    "L1Shadow phase=%s %.1f%% headers %d/%d filters %d/%d",
    p.phase, p.overallPercent, p.headerHeight, p.headerTarget, p.filterHeight, p.filterTarget
)

// ── Parity probe shapes ───────────────────────────────────────────────

/**
 * One parity measurement between the SDK's shadow SPV wallet and the dashj
 * wallet — the Phase 5a verification instrument for the L1 cutover.
 *
 * Primary comparison ([balancesMatch]): SDK confirmed+unconfirmed
 * ([sdkDuffs]) vs dashj `getBalance(ESTIMATED)` ([dashjDuffs]) — both
 * include pending funds. The confirmed-only variant
 * ([confirmedBalancesMatch]: [sdkConfirmedDuffs] vs dashj
 * `getBalance(AVAILABLE)` in [dashjAvailableDuffs]) is carried and logged
 * alongside because the two stacks classify "pending" on different edges
 * (mempool visibility, InstantSend locks), so a transient estimated-only
 * divergence with matching confirmed balances usually means "one side saw
 * the mempool first", not a scan bug.
 *
 * [sdkSynced] gates interpretation, not measurement: until the shadow SPV
 * reaches SYNCED the SDK side is still scanning (the app wallet was bound
 * with `birthHeight = 0`, so the first pass covers the whole chain) and a
 * mismatch is expected, logged as `MISMATCH-PRESYNC`.
 */
data class ParityReport(
    val balancesMatch: Boolean,
    val sdkDuffs: Long,
    val dashjDuffs: Long,
    val sdkTxCount: Int,
    val dashjTxCount: Int,
    val sdkSynced: Boolean,
    val timestampMs: Long,
    val confirmedBalancesMatch: Boolean,
    val sdkConfirmedDuffs: Long,
    val dashjAvailableDuffs: Long
) {
    val txCountsMatch: Boolean get() = sdkTxCount == dashjTxCount

    /** Everything this probe can check agrees. */
    val fullMatch: Boolean get() = balancesMatch && confirmedBalancesMatch && txCountsMatch
}

/**
 * Assemble a [ParityReport] from the two stacks' raw numbers — the pure
 * comparison core of the probe, unit-testable without any wallet.
 */
internal fun buildParityReport(
    sdkConfirmedDuffs: Long,
    sdkUnconfirmedDuffs: Long,
    dashjEstimatedDuffs: Long,
    dashjAvailableDuffs: Long,
    sdkTxCount: Int,
    dashjTxCount: Int,
    sdkSynced: Boolean,
    timestampMs: Long
): ParityReport {
    val sdkTotal = sdkConfirmedDuffs + sdkUnconfirmedDuffs
    return ParityReport(
        balancesMatch = sdkTotal == dashjEstimatedDuffs,
        sdkDuffs = sdkTotal,
        dashjDuffs = dashjEstimatedDuffs,
        sdkTxCount = sdkTxCount,
        dashjTxCount = dashjTxCount,
        sdkSynced = sdkSynced,
        timestampMs = timestampMs,
        confirmedBalancesMatch = sdkConfirmedDuffs == dashjAvailableDuffs,
        sdkConfirmedDuffs = sdkConfirmedDuffs,
        dashjAvailableDuffs = dashjAvailableDuffs
    )
}

/**
 * The `L1Parity` one-liner: MATCH when every comparison agrees,
 * MISMATCH-PRESYNC while the shadow chain is still scanning (expected —
 * see [ParityReport]), MISMATCH once it is synced (real — the exact class
 * of bug this harness exists to catch, e.g. CoinJoin-account funds the
 * SDK's derivation misses). Both balance variants and both tx counts are
 * always in the line (duffs).
 */
internal fun parityLogLine(r: ParityReport): String {
    val verdict = when {
        r.fullMatch -> "MATCH"
        r.sdkSynced -> "MISMATCH"
        else -> "MISMATCH-PRESYNC"
    }
    return "L1Parity $verdict synced=${r.sdkSynced}" +
        " estimated sdk=${r.sdkDuffs} dashj=${r.dashjDuffs}" +
        " confirmed sdk=${r.sdkConfirmedDuffs} dashj=${r.dashjAvailableDuffs}" +
        " tx sdk=${r.sdkTxCount} dashj=${r.dashjTxCount}"
}

// ── Outpoint-level diff (the MISMATCH evidence instrument) ────────────

/**
 * One unspent L1 output, normalized for cross-stack comparison:
 * [txidHex] is the DISPLAY-order (byte-reversed) transaction hash hex —
 * dashj's `Sha256Hash.toString()` convention — so both sides key
 * identically. The SDK's Room rows store wire-order bytes and are
 * reversed on read (see [DashSdkL1ShadowSource.sdkUnspentUtxos]).
 */
data class L1Utxo(val txidHex: String, val vout: Int, val valueDuffs: Long) {
    val outpoint: String get() = "$txidHex:$vout"
}

/**
 * Outpoint-level difference between the SDK's unspent TXO rows and
 * dashj's unspent outputs, computed by [computeL1OutpointDiff] when a
 * synced-state balance MISMATCH needs explaining. [duplicateSdkOutpoints]
 * is the suspected-bug detector: the same outpoint appearing more than
 * once in the SDK set (impossible at the Room layer — `outpoint` is the
 * primary key — so a hit means the list was assembled from a corrupt
 * upstream source and is hard evidence for the SDK bug report).
 */
internal data class L1OutpointDiff(
    val sdkCount: Int,
    val dashjCount: Int,
    val sdkTotalDuffs: Long,
    val dashjTotalDuffs: Long,
    /** Outpoints only the SDK considers unspent (deduped; duffs summed over duplicates). */
    val sdkOnly: List<L1Utxo>,
    /** Outpoints only dashj considers unspent. */
    val dashjOnly: List<L1Utxo>,
    /** Outpoints both sides hold but at different values: (outpoint, sdkDuffs, dashjDuffs). */
    val valueMismatched: List<Triple<String, Long, Long>>,
    /** SDK outpoints appearing more than once, as "outpoint xN". */
    val duplicateSdkOutpoints: List<String>
)

/**
 * Pure symmetric difference of the two unspent sets, keyed by outpoint.
 * Duplicated SDK rows are flagged AND collapsed (values summed) before
 * diffing, so a duplicate shows up both in [L1OutpointDiff.duplicateSdkOutpoints]
 * and — via its doubled value — in [L1OutpointDiff.valueMismatched].
 */
internal fun computeL1OutpointDiff(sdk: List<L1Utxo>, dashj: List<L1Utxo>): L1OutpointDiff {
    val duplicates = sdk.groupingBy { it.outpoint }.eachCount()
        .filterValues { it > 1 }
        .map { (outpoint, n) -> "$outpoint x$n" }
        .sorted()
    val sdkByOutpoint = LinkedHashMap<String, L1Utxo>()
    for (utxo in sdk) {
        sdkByOutpoint.merge(utxo.outpoint, utxo) { a, b -> a.copy(valueDuffs = a.valueDuffs + b.valueDuffs) }
    }
    val dashjByOutpoint = dashj.associateBy { it.outpoint }
    val sdkOnly = sdkByOutpoint.values.filter { it.outpoint !in dashjByOutpoint }
    val dashjOnly = dashj.filter { it.outpoint !in sdkByOutpoint }
    val valueMismatched = sdkByOutpoint.values.mapNotNull { s ->
        val d = dashjByOutpoint[s.outpoint] ?: return@mapNotNull null
        if (s.valueDuffs != d.valueDuffs) Triple(s.outpoint, s.valueDuffs, d.valueDuffs) else null
    }
    return L1OutpointDiff(
        sdkCount = sdk.size,
        dashjCount = dashj.size,
        sdkTotalDuffs = sdk.sumOf { it.valueDuffs },
        dashjTotalDuffs = dashj.sumOf { it.valueDuffs },
        sdkOnly = sdkOnly,
        dashjOnly = dashjOnly,
        valueMismatched = valueMismatched,
        duplicateSdkOutpoints = duplicates
    )
}

/**
 * Multi-line `L1ParityDiff` log body for a computed diff — the evidence
 * block for the SDK bug report. Each list is capped at [maxEntries]
 * entries (the totals always cover the full sets).
 */
internal fun l1OutpointDiffLog(diff: L1OutpointDiff, maxEntries: Int = DIFF_LOG_MAX_ENTRIES): String {
    fun capped(items: List<String>): String =
        if (items.isEmpty()) {
            "none"
        } else {
            val shown = items.take(maxEntries).joinToString(", ")
            if (items.size > maxEntries) "$shown … (+${items.size - maxEntries} more)" else shown
        }
    return buildString {
        append("L1ParityDiff unspent sdk=${diff.sdkCount} (${diff.sdkTotalDuffs} duffs)")
        append(" dashj=${diff.dashjCount} (${diff.dashjTotalDuffs} duffs)")
        append(" delta=${diff.sdkTotalDuffs - diff.dashjTotalDuffs}")
        append("\n  DUPLICATE sdk rows: ${capped(diff.duplicateSdkOutpoints)}")
        append("\n  sdk-only (${diff.sdkOnly.size}): ")
        append(capped(diff.sdkOnly.map { "${it.outpoint}=${it.valueDuffs}" }))
        append("\n  dashj-only (${diff.dashjOnly.size}): ")
        append(capped(diff.dashjOnly.map { "${it.outpoint}=${it.valueDuffs}" }))
        append("\n  value-mismatch (${diff.valueMismatched.size}): ")
        append(capped(diff.valueMismatched.map { (o, s, d) -> "$o sdk=$s dashj=$d" }))
    }
}

/** Per-list cap of [l1OutpointDiffLog] (the totals still cover everything). */
internal const val DIFF_LOG_MAX_ENTRIES = 50

// ── Auto-reset decision (pure) ────────────────────────────────────────

/**
 * Decides when a persistent INFLATED-SDK parity mismatch warrants an
 * automatic [L1ShadowSyncService.resetShadowState], from the probe stream
 * alone.
 *
 * ## Why `sdk > dashj` only
 *
 * During the migration transition dashj is the source of truth for L1 —
 * it is the engine users spend from, and its view survives every release
 * to date. An SDK view showing MORE funds than dashj is therefore never
 * legitimate: the extra duffs can only be stale/duplicated shadow state
 * (e.g. unreconciled TXO rows after an SPV re-scan following an unclean
 * shutdown). A DEFICIT (`sdk < dashj`), by contrast, can be a real SDK
 * scan gap (e.g. a derivation path the SDK misses) — exactly the bug
 * class the harness must SURFACE, not erase — so it never auto-resets.
 *
 * ## Decision table (evaluated per probe, in order)
 *
 * | synced | balancesMatch | sdk vs dashj | consecutive | resetDone | decision |
 * |--------|---------------|--------------|-------------|-----------|----------|
 * | no     | —             | —            | reset to 0  | —         | NONE     |
 * | yes    | yes           | —            | reset to 0  | —         | NONE     |
 * | yes    | no            | sdk < dashj  | reset to 0  | —         | NONE     |
 * | yes    | no            | sdk > dashj  | < threshold | —         | NONE     |
 * | yes    | no            | sdk > dashj  | ≥ threshold | no        | RESET    |
 * | yes    | no            | sdk > dashj  | ≥ threshold | yes       | CORRUPT_AFTER_RESET (once), then NONE |
 *
 * RESET fires at most once per process (per decider instance). After a
 * reset the shadow chain re-scans, so `synced=false` probes zero the
 * streak; only a FULL post-reset resync that still shows an inflated
 * mismatch for [requiredConsecutiveProbes] probes reaches
 * CORRUPT_AFTER_RESET — the "shadow state corrupt after reset, stand
 * down" signal.
 */
internal class ShadowResetDecider(
    private val requiredConsecutiveProbes: Int = RESET_CONSECUTIVE_PROBES
) {
    enum class Decision { NONE, RESET, CORRUPT_AFTER_RESET }

    private var consecutiveInflated = 0
    private var resetIssued = false
    private var corruptReported = false

    fun onProbe(report: ParityReport): Decision {
        val inflated = report.sdkSynced && !report.balancesMatch && report.sdkDuffs > report.dashjDuffs
        if (!inflated) {
            consecutiveInflated = 0
            return Decision.NONE
        }
        consecutiveInflated++
        if (consecutiveInflated < requiredConsecutiveProbes) return Decision.NONE
        return when {
            !resetIssued -> {
                resetIssued = true
                consecutiveInflated = 0
                Decision.RESET
            }
            !corruptReported -> {
                corruptReported = true
                Decision.CORRUPT_AFTER_RESET
            }
            else -> Decision.NONE
        }
    }

    companion object {
        /** Synced inflated-mismatch probes required before acting. */
        internal const val RESET_CONSECUTIVE_PROBES = 3
    }
}

/**
 * Distinct wallet-relevant transaction count from the SDK's TXO rows:
 * every tx that FUNDED one of the wallet's TXOs plus every tx that SPENT
 * one. Pure (hex-keyed dedup — ByteArray has identity equality) so the
 * counting is unit-testable; mirrors what dashj's `getTransactions(false)`
 * set contains for a wallet with no dead transactions.
 */
internal fun distinctTxCount(txids: List<ByteArray?>, spendingTxids: List<ByteArray?>): Int {
    val seen = HashSet<String>()
    for (id in txids) {
        if (id != null) seen += id.joinToString("") { "%02x".format(it) }
    }
    for (id in spendingTxids) {
        if (id != null) seen += id.joinToString("") { "%02x".format(it) }
    }
    return seen.size
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over both sides of the parity comparison — the Kotlin SDK's SPV
 * surface ([org.dashfoundation.dashsdk.wallet.PlatformWalletManager]'s SPV
 * section + Room TXO store) and the dashj wallet — so the flag/lifecycle/
 * probe orchestration in [L1ShadowSyncService] is host-JVM unit-testable.
 */
interface L1ShadowSource {
    /** Same contract as [ShieldedSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    suspend fun isSpvRunning(): Boolean

    /**
     * Start the Rust SPV client with storage rooted at [dataDir]. Peer
     * discovery and user agent follow the SDK defaults (the example app's
     * convention: empty peer list unless explicitly overridden, null
     * userAgent → FFI default).
     */
    suspend fun startSpv(dataDir: String)

    suspend fun stopSpv()

    /** The manager's 1 Hz SPV progress feed (live while SPV runs). */
    fun spvProgress(): Flow<SpvSyncProgressData>

    /** (confirmed, unconfirmed) duffs from the SDK wallet's lock-free L1 balance. */
    suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long>

    /** Distinct wallet-relevant tx count from the SDK's TXO store. */
    suspend fun sdkTxCount(walletIdHex: String): Int

    /** (ESTIMATED, AVAILABLE) duffs from the dashj wallet, or null when it isn't loaded. */
    suspend fun dashjBalanceDuffs(): Pair<Long, Long>?

    /** dashj wallet tx count (`getTransactions(false)`), or null when it isn't loaded. */
    suspend fun dashjTxCount(): Int?

    /** Every unspent TXO row of the wallet from the SDK's Room store, normalized. */
    suspend fun sdkUnspentUtxos(walletIdHex: String): List<L1Utxo>

    /**
     * dashj's unspent outputs — `calculateAllSpendCandidates(false, false)`,
     * the exact set `getBalance(ESTIMATED)` sums (all keychains, including
     * legacy CoinJoin-derivation funds) — or null when the wallet isn't
     * loaded.
     */
    suspend fun dashjUnspentUtxos(): List<L1Utxo>?

    /** Clear the Rust SPV client's persisted storage (headers, filters, state). */
    suspend fun clearSpvStorage()

    /**
     * Delete the SDK wallet's persisted L1 view — its TXO rows and the
     * transaction rows they reference — so the next scan rebuilds them.
     */
    suspend fun clearSdkL1Rows(walletIdHex: String)
}

/** Production [L1ShadowSource]: boots the SDK on demand; reads dashj via [WalletDataProvider]. */
internal class DashSdkL1ShadowSource(
    private val service: DashSdkService,
    private val walletData: WalletDataProvider
) : L1ShadowSource {

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

    override suspend fun isSpvRunning(): Boolean = manager().isSpvRunning()

    override suspend fun startSpv(dataDir: String) =
        // Example-app conventions (SyncStatusScreen.startSync): default
        // peer discovery (no peer override in this app), default user
        // agent, no height override — Rust resumes from its persisted
        // state in dataDir.
        manager().startSpv(dataDir = dataDir)

    override suspend fun stopSpv() = manager().stopSpv()

    override fun spvProgress(): Flow<SpvSyncProgressData> =
        flow { emitAll(manager().spvProgress) }

    override suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long> {
        val wallet = checkNotNull(manager().wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val balance = wallet.balance()
        return balance.confirmed to balance.unconfirmed
    }

    override suspend fun sdkTxCount(walletIdHex: String): Int {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val txos = database().txoDao().observeByWallet(walletId).first()
        return distinctTxCount(txos.map { it.txid }, txos.map { it.spendingTxid })
    }

    override suspend fun dashjBalanceDuffs(): Pair<Long, Long>? =
        walletData.wallet?.let {
            it.getBalance(BalanceType.ESTIMATED).value to it.getBalance(BalanceType.AVAILABLE).value
        }

    override suspend fun dashjTxCount(): Int? =
        walletData.wallet?.getTransactions(false)?.size

    override suspend fun sdkUnspentUtxos(walletIdHex: String): List<L1Utxo> {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val rows = database().txoDao().observeUnspentByWallet(walletId).first()
        return rows.map { row ->
            // Room stores wire-order (little-endian) txid bytes; the row's
            // 36-byte outpoint PK is txid + vout-LE, the fallback when the
            // txid FK is still null (brief insert window).
            val rawTxid = row.txid ?: row.outpoint.copyOfRange(0, minOf(32, row.outpoint.size))
            L1Utxo(
                txidHex = rawTxid.reversedArray().joinToString("") { "%02x".format(it) },
                vout = row.vout,
                valueDuffs = row.amount
            )
        }
    }

    override suspend fun dashjUnspentUtxos(): List<L1Utxo>? =
        // The exact output set getBalance(ESTIMATED) sums — see the seam doc.
        walletData.wallet?.calculateAllSpendCandidates(false, false)?.map { output ->
            L1Utxo(
                txidHex = output.parentTransactionHash?.toString() ?: "detached",
                vout = output.index,
                valueDuffs = output.value.value
            )
        }

    override suspend fun clearSpvStorage() = manager().clearSpvStorage()

    /**
     * Raw Room deletes, because the SDK exposes NO narrower reset op (survey
     * of `PlatformWalletManager` / `WalletManagerNative`): `clearSpvStorage`
     * covers only headers/filters/SPV state; `platformAddressSyncReset` is
     * the Platform-address loop; `shieldedClear` is the shielded store; and
     * `removeWallet` runs the FULL persistence cascade (wallet row, keys,
     * addresses — a teardown, not a rescan). So the L1 view is cleared at
     * the persistence layer the SDK itself rehydrates wallets from
     * (`onLoadWalletList` → `buildUtxoRestoreData` reads exactly these
     * rows): delete the wallet's `txos` rows plus the `transactions` rows
     * they reference (tx rows are not wallet-scoped; membership is the txo
     * join — this app binds a single wallet). Deleting a transaction row
     * CASCADEs its txos, and `deleteByWallet` sweeps any rows whose tx FK
     * was still null.
     */
    override suspend fun clearSdkL1Rows(walletIdHex: String) {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val db = database()
        val txos = db.txoDao().observeByWallet(walletId).first()
        val txids = HashMap<String, ByteArray>()
        for (row in txos) {
            for (id in listOfNotNull(row.txid, row.spendingTxid)) {
                txids[id.joinToString("") { "%02x".format(it) }] = id
            }
        }
        for (txid in txids.values) {
            db.transactionDao().deleteByTxid(txid)
        }
        db.txoDao().deleteByWallet(walletId)
    }
}

// ── The shadow-sync service ───────────────────────────────────────────

/**
 * Phase 5a of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`): the L1 SHADOW-SYNC PARITY
 * HARNESS. The Kotlin SDK's Rust SPV client syncs ALONGSIDE dashj — into
 * its own storage directory, touching nothing dashj owns — and a probe
 * loop continuously measures balance/transaction parity between the two
 * stacks, logging `L1Parity` one-liners and exposing the latest
 * [ParityReport] for a future debug screen. This is the verification
 * instrument for the eventual L1 cutover; it changes NOTHING user-facing.
 *
 * ## Battery / cost warning (debug-only instrumentation)
 *
 * Shadow mode runs TWO full SPV engines — dashj's block-header/bloom sync
 * AND the Rust compact-filter sync — doubling network, CPU and battery
 * cost while active. [DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW] defaults
 * OFF and is seeded ON only by the DEBUG-build init block in
 * [DashPayConfig]; it must never ship enabled to production.
 *
 * ## Lifecycle
 *
 * - **Inert while off**: [startIfEnabled] re-reads the flag first and
 *   returns before touching [L1ShadowSource] (no native call, no SPV
 *   storage, no loops) — verified by unit test.
 * - **Requires a bound wallet**: the SDK must already hold the app wallet
 *   ([SdkWalletBinder]); if not, the pass reports false and the next
 *   trigger retries. This service never prompts and never sees the seed.
 * - **Single-flight + latch**: a [Mutex] serializes passes; while running,
 *   later calls no-op (`true`). [stop] cancels the loops, stops the Rust
 *   client, and clears the latch; safe to call when not running.
 * - **Never throws** (except cancellation): failures are logged and
 *   swallowed — dashj sync is never affected by the shadow.
 *
 * ## Storage
 *
 * SPV state lives under `filesDir/l1_shadow_spv/<network>` — disjoint from
 * dashj's block stores (`getDir("blockstore")`) and from the SDK example
 * app's `filesDir/spv/<network>` convention, so a later real cutover can
 * start from a clean directory decision.
 *
 * ## The probe (every 60s while running, including after SYNCED)
 *
 * Compares (a) SDK confirmed+unconfirmed L1 balance vs dashj
 * `getBalance(ESTIMATED)` — plus the confirmed-only variant vs
 * `getBalance(AVAILABLE)` — and (b) SDK distinct TXO-derived tx count vs
 * dashj `getTransactions(false).size`. Interpretation caveats live on
 * [ParityReport]; the headline one: the bound SDK wallet scans from
 * `birthHeight = 0`, so nothing is a real mismatch until the shadow chain
 * reports SYNCED. CoinJoin-derivation funds (`m/9'` account paths dashj
 * spends from) MUST show up in the SDK totals — a persistent synced
 * balance gap is exactly the class of bug this harness exists to catch
 * before any cutover.
 */
@Singleton
class L1ShadowSyncService internal constructor(
    private val source: L1ShadowSource,
    private val dashPayConfig: DashPayConfig,
    private val scope: CoroutineScope,
    private val spvDataDirPath: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val parityIntervalMs: Long = PARITY_INTERVAL_MS,
    private val progressLogIntervalMs: Long = PROGRESS_LOG_INTERVAL_MS
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        walletData: WalletDataProvider,
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope
    ) : this(
        source = DashSdkL1ShadowSource(sdkService, walletData),
        dashPayConfig = dashPayConfig,
        scope = scope,
        // Lazy: Constants/native untouched at construction (inertness).
        spvDataDirPath = {
            val network = toSdkNetwork(Constants.NETWORK_PARAMETERS)
            File(context.filesDir, "l1_shadow_spv/${network.networkName}").absolutePath
        }
    )

    /** Serializes [startIfEnabled]/[stop] — the single-flight guarantee. */
    private val mutex = Mutex()

    /** Running latch: the wallet id the probe compares, null when stopped. */
    private val runningWalletIdHex = MutableStateFlow<String?>(null)

    private var monitorJob: Job? = null
    private var parityJob: Job? = null

    private val _progress = MutableStateFlow(ShadowSyncProgress.IDLE)

    /** Live shadow SPV progress ([ShadowSyncProgress.IDLE] while stopped). */
    val progress: StateFlow<ShadowSyncProgress> = _progress.asStateFlow()

    private val _latestParity = MutableStateFlow<ParityReport?>(null)

    /** The most recent parity measurement, for a future debug UI. */
    val latestParity: StateFlow<ParityReport?> = _latestParity.asStateFlow()

    /**
     * Fingerprints of synced-MISMATCH states whose outpoint diff was
     * already logged — once per process per distinct state, so a stable
     * mismatch doesn't re-dump the diff every 60s probe.
     */
    private val loggedDiffFingerprints = HashSet<String>()

    /** The auto-reset decision state (see [ShadowResetDecider] for the table). */
    private val resetDecider = ShadowResetDecider()

    /** Fire-and-forget [startIfEnabled] for call sites that must not wait. */
    fun startInBackground(): Job = scope.launch { startIfEnabled() }

    /**
     * Start the shadow SPV sync + parity probe if (and only if) the flag
     * is on and the app wallet is bound to the SDK. Never throws (see
     * class KDoc); returns whether the shadow is running afterwards.
     */
    suspend fun startIfEnabled(): Boolean {
        if (!isEnabled()) return false
        return try {
            mutex.withLock {
                if (runningWalletIdHex.value != null) return true

                val walletIdHex = source.boundWalletIdOrNull()
                if (walletIdHex == null) {
                    log.info("L1 shadow sync not started: app wallet not bound to the SDK yet")
                    return false
                }

                val dataDir = File(spvDataDirPath()).apply { mkdirs() }
                if (!source.isSpvRunning()) {
                    source.startSpv(dataDir.absolutePath)
                }
                runningWalletIdHex.value = walletIdHex
                monitorJob = scope.launch { monitorProgress() }
                parityJob = scope.launch { parityLoop(walletIdHex) }
                log.info(
                    "L1 shadow SPV started for SDK wallet {}… (dataDir={}, default peer discovery); " +
                        "debug-only instrumentation — two SPV engines are now running",
                    walletIdHex.take(8), dataDir.absolutePath
                )
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("L1 shadow sync start failed; dashj behavior unchanged", t)
            false
        }
    }

    /**
     * Stop the probe loops and the Rust SPV client; SPV storage stays on
     * disk so the next start resumes. Safe to call when not running.
     */
    suspend fun stop() {
        mutex.withLock {
            if (runningWalletIdHex.value == null) return
            runningWalletIdHex.value = null
            monitorJob?.cancel()
            monitorJob = null
            parityJob?.cancel()
            parityJob = null
            runCatching { source.stopSpv() }
                .onFailure { log.warn("failed to stop the shadow SPV client", it) }
            _progress.value = ShadowSyncProgress.IDLE
            log.info("L1 shadow sync stopped")
        }
    }

    /**
     * Mirror the manager's 1 Hz progress feed into [progress], logging the
     * one-line summary at most every [progressLogIntervalMs] — except the
     * one-time transitions into SYNCED/ERROR, which always log (they are
     * the events the harness exists to observe).
     */
    private suspend fun monitorProgress() {
        var lastLogMs = 0L
        var lastPhase = ShadowSyncPhase.IDLE
        source.spvProgress().collect { data ->
            val mapped = toShadowSyncProgress(data)
            _progress.value = mapped
            val now = nowMs()
            val terminalTransition = mapped.phase != lastPhase &&
                (mapped.phase == ShadowSyncPhase.SYNCED || mapped.phase == ShadowSyncPhase.ERROR)
            if (terminalTransition || now - lastLogMs >= progressLogIntervalMs) {
                log.info(shadowProgressLine(mapped))
                lastLogMs = now
            }
            lastPhase = mapped.phase
        }
    }

    private suspend fun parityLoop(walletIdHex: String) {
        while (currentCoroutineContext().isActive) {
            try {
                probeParity(walletIdHex)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1Parity probe failed; will retry on the next tick", t)
            }
            delay(parityIntervalMs)
        }
    }

    /**
     * One parity measurement (see class KDoc for semantics). Internal so
     * tests drive single probes without the 60s loop. Returns null when
     * the dashj wallet is not available (probe skipped, nothing published).
     */
    internal suspend fun probeParity(walletIdHex: String): ParityReport? {
        val (sdkConfirmed, sdkUnconfirmed) = source.sdkBalanceDuffs(walletIdHex)
        val sdkTxCount = source.sdkTxCount(walletIdHex)
        val dashjBalances = source.dashjBalanceDuffs()
        val dashjTxCount = source.dashjTxCount()
        if (dashjBalances == null || dashjTxCount == null) {
            log.info("L1Parity probe skipped: dashj wallet not available")
            return null
        }
        val report = buildParityReport(
            sdkConfirmedDuffs = sdkConfirmed,
            sdkUnconfirmedDuffs = sdkUnconfirmed,
            dashjEstimatedDuffs = dashjBalances.first,
            dashjAvailableDuffs = dashjBalances.second,
            sdkTxCount = sdkTxCount,
            dashjTxCount = dashjTxCount,
            sdkSynced = _progress.value.synced,
            timestampMs = nowMs()
        )
        _latestParity.value = report
        log.info(parityLogLine(report))

        if (report.sdkSynced && !report.balancesMatch) {
            maybeLogOutpointDiff(walletIdHex, report)
        }
        when (resetDecider.onProbe(report)) {
            ShadowResetDecider.Decision.RESET -> {
                log.warn(
                    "L1Parity inflated MISMATCH persisted for {} consecutive synced probes " +
                        "(sdk={} > dashj={} duffs) — an inflated SDK view is never legitimate " +
                        "(dashj is the L1 source of truth); resetting the shadow state",
                    ShadowResetDecider.RESET_CONSECUTIVE_PROBES, report.sdkDuffs, report.dashjDuffs
                )
                resetShadowState()
            }
            ShadowResetDecider.Decision.CORRUPT_AFTER_RESET ->
                log.error(
                    "shadow state corrupt after reset — SDK bug: the inflated L1 mismatch " +
                        "(sdk={} > dashj={} duffs) survived a full post-reset resync; standing " +
                        "down (no further automatic resets this process; the in-memory Rust " +
                        "wallet state is only fully rebuilt on the next app start)",
                    report.sdkDuffs, report.dashjDuffs
                )
            ShadowResetDecider.Decision.NONE -> Unit
        }
        return report
    }

    /**
     * Log the outpoint-level SDK-vs-dashj diff for a synced MISMATCH, once
     * per process per distinct (balances, tx counts) state — the evidence
     * block for the SDK bug report. Failures propagate to [parityLoop]'s
     * catch (best-effort diagnostics must not kill the probe).
     */
    private suspend fun maybeLogOutpointDiff(walletIdHex: String, report: ParityReport) {
        val fingerprint =
            "${report.sdkDuffs}:${report.dashjDuffs}:${report.sdkTxCount}:${report.dashjTxCount}"
        if (!loggedDiffFingerprints.add(fingerprint)) return
        val dashjUtxos = source.dashjUnspentUtxos()
        if (dashjUtxos == null) {
            log.info("L1ParityDiff skipped: dashj wallet not available")
            loggedDiffFingerprints.remove(fingerprint) // retry next probe
            return
        }
        val sdkUtxos = source.sdkUnspentUtxos(walletIdHex)
        log.warn(l1OutpointDiffLog(computeL1OutpointDiff(sdkUtxos, dashjUtxos)))
    }

    /**
     * Tear down and rebuild the shadow SPV's PERSISTED state: stop the
     * Rust client, delete the SDK wallet's L1 TXO/transaction rows (see
     * [L1ShadowSource.clearSdkL1Rows] for why raw Room deletes are the
     * only SDK surface for this), clear the SPV header/filter storage,
     * then restart SPV for a fresh full scan (the scan start comes from
     * the wallet's stored birth height Rust-side — no height override).
     *
     * Called automatically by the probe when an inflated-SDK mismatch
     * persists (see [ShadowResetDecider] for the decision table and the
     * once-per-process guarantee) and callable directly for a future
     * debug screen. NOTE: the live Rust wallet's in-memory TXO view is
     * NOT rebuilt by this call (the SDK offers no in-process reload short
     * of `removeWallet`'s destructive cascade); the persisted rows are
     * clean immediately and the in-memory view is rebuilt from them on
     * the next app start.
     *
     * Returns whether the reset ran (false when the shadow isn't running
     * or a step failed; failures are logged, never thrown).
     */
    suspend fun resetShadowState(): Boolean {
        return try {
            mutex.withLock {
                val walletIdHex = runningWalletIdHex.value
                if (walletIdHex == null) {
                    log.info("L1 shadow reset skipped: shadow sync not running")
                    return false
                }
                log.warn("L1 shadow reset: stopping SPV, clearing L1 rows + SPV storage, rescanning")
                runCatching { source.stopSpv() }
                    .onFailure { log.warn("shadow reset: SPV stop failed; continuing", it) }
                _progress.value = ShadowSyncProgress.IDLE
                source.clearSdkL1Rows(walletIdHex)
                source.clearSpvStorage()
                val dataDir = File(spvDataDirPath()).apply { mkdirs() }
                source.startSpv(dataDir.absolutePath)
                log.info(
                    "L1 shadow SPV restarted after reset (dataDir={}, fresh full scan from the " +
                        "wallet's stored birth height)",
                    dataDir.absolutePath
                )
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("L1 shadow reset failed; the shadow may need a manual restart", t)
            false
        }
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_L1_SHADOW; treating as off", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(L1ShadowSyncService::class.java)

        /** Parity probe cadence while the shadow runs (including after SYNCED). */
        internal const val PARITY_INTERVAL_MS = 60_000L

        /** Progress one-liner throttle (the SDK feed itself ticks at 1 Hz). */
        internal const val PROGRESS_LOG_INTERVAL_MS = 30_000L
    }
}
