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
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.wallet.Wallet.BalanceType
import de.schildbach.wallet.data.WalletData
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
 * User-observable verdict of the L1 funding-verification harness — the
 * coarse state behind [L1ShadowSyncService.verificationStatus], consumed
 * by the shielded-transfer screen's "Verifying your balance…" toast.
 *
 * - [UNKNOWN]: no evidence yet (shadow not running / flag off) — UIs fall
 *   back to their static copy.
 * - [SCANNING]: the shadow SPV chain is still syncing ([progress]
 *   [L1ShadowSyncService.progress] carries the live block counts).
 * - [PROBING]: the chain is synced but parity with dashj has not been
 *   confirmed yet (first probe pending, or a mismatch that is not — or
 *   not yet — terminal, e.g. mid-recovery).
 * - [VERIFIED]: the latest synced probe matched on BOTH balance variants —
 *   the same evidence [evaluateWalletFundingGate] requires, so the funding
 *   gate opens on the next check. A later mismatching probe moves back to
 *   [PROBING] (the gate closes again).
 * - [FAILED]: the harness stood down — a persistent parity mismatch that
 *   SURVIVED the one-time full SDK-wallet rebuild self-heal
 *   ([ShadowResetDecider.Decision.STAND_DOWN] — a deterministic SDK ledger
 *   bug needing an upstream fix), or a stalled probe loop past its one
 *   watchdog restart. Terminal for the process: verification will not
 *   recover without an app restart, so the UI tells the tester to flag it
 *   (Report an Issue). NOTE: the FIRST persistent mismatch does NOT land
 *   here — it triggers [ShadowResetDecider.Decision.REBUILD_WALLET] (the
 *   probe stays [PROBING] through the rebuild + resync); only a mismatch
 *   that outlives the rebuild reaches FAILED.
 */
enum class L1VerificationStatus { UNKNOWN, SCANNING, PROBING, VERIFIED, FAILED }

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
    val filterTarget: Long,
    /**
     * Masternode-list sync height (the masternodes sub-progress's current
     * height), 0 when the snapshot carries none. Feeds the post-cutover
     * `BlockchainState.mnlistHeight` derivation (kill-list Step B —
     * [SdkBlockchainStateService]); defaulted so the pre-existing
     * positional constructions stay valid. NOTE: being part of the data
     * class, this field is in [equals] — the progress flow now emits on
     * masternode-height-only changes it previously conflated, pre-cutover
     * too (verified benign: every collector dedups downstream).
     */
    val mnListHeight: Long = 0,
    /**
     * The wallet's COMMITTED scan cursor — the height through which the
     * engine's block-download/tx-processing pipeline has actually DRAINED,
     * not merely the filter scan position. 0 = unknown (no evidence yet).
     *
     * Fed by [L1ShadowSyncService] from the engine's
     * `WalletEvent::SyncHeightAdvanced` events ("the filter pipeline
     * committed a batch covering blocks up to height" — the commit lands
     * AFTER the batch's matched blocks were processed through the wallet),
     * seeded at shadow start from the SDK's durable `WalletEntity.syncedHeight`
     * watermark. This is the only Kotlin-visible view of the engine's
     * requested-vs-processed block state: the typed FFI progress
     * ([SpvSyncProgressData]) carries headers/filterHeaders/filters/
     * masternodes but NOT the dash-spv `BlocksProgress` sub-phase, whose
     * churn the field incident hid behind a "synced" filter scan.
     * LATEST-WINS, not monotonic: an armed rescan rewinds the cursor and
     * the follow-up events legitimately re-climb from the rewound floor.
     */
    val walletSyncedHeight: Long = 0
) {
    /** The shadow chain is fully synced — parity mismatches count as real from here. */
    val synced: Boolean get() = phase == ShadowSyncPhase.SYNCED

    /**
     * The header chain AND the wallet-relevant compact-filter scan both
     * report a non-zero target that has been reached — the "nothing left
     * to scan" signal the reset-aftermath detector requires (see
     * [ShadowResetDecider]). Deliberately stricter than [synced]: a SYNCED
     * snapshot whose sub-progress blocks are absent (all-zero heights)
     * does NOT count, because it cannot prove the filter scan actually
     * covered the chain.
     */
    val scanLooksComplete: Boolean get() =
        headerTarget > 0 && headerHeight >= headerTarget &&
            filterTarget > 0 && filterHeight >= filterTarget

    /**
     * The wallet-relevant compact-filter scan has caught up to the header
     * chain tip — the L1 funding-gate ([evaluateWalletFundingGate])
     * readiness signal for a LIVE shadow SPV.
     *
     * WHY THIS EXISTS (never-SYNCED root cause): the SDK only reports
     * [ShadowSyncPhase.SYNCED] when its OWN overall-state latches SYNCED,
     * but a live shadow that co-exists with the dashj engine is perpetually
     * chasing the moving chain tip — a new block arrives roughly every
     * 2.5 min, bumps the filter/header targets, and the overall state falls
     * back to SYNCING before it ever latches. On a real post-cutover wallet
     * this means [synced] reads `false` forever even though headers are at
     * the tip and the filter scan has processed the whole chain (observed
     * on device: `L1Parity … synced=false` throughout the session). A gate
     * that required [synced]/[scanLooksComplete] would therefore stay
     * closed permanently — the "Verifying your balance…" toast that blocks
     * the wallet→shielded transfer.
     *
     * So readiness is defined on CAUGHT-UP, not on the SDK's SYNCED flag:
     * the header chain is at a known tip ([headerTarget] > 0 and
     * [headerHeight] there) AND the filter scan has reached within
     * [SCAN_TIP_TOLERANCE_BLOCKS] of that tip (a non-zero [filterTarget]
     * proves the filter sub-progress is real, not an all-zero snapshot).
     *
     * The tolerance absorbs the live chase: the header target can lead the
     * filter scan by a block or two for a few seconds after each new block,
     * so exact equality ([scanLooksComplete]) is a fleeting instant a
     * one-shot preflight rarely catches. It is funds-safe because
     * `shieldFromWallet` and the SDK send spend from ChainLocked / confirmed
     * UTXOs that are many blocks deep — a filter scan that trails the tip by
     * ≤ [SCAN_TIP_TOLERANCE_BLOCKS] has already covered them. A GENUINE
     * mid-scan (filters thousands of blocks behind, or the engine
     * IDLE/ERROR/still on headers) trails far outside the tolerance and
     * keeps the gate closed (fail-closed).
     *
     * ALSO requires the block/tx pipeline not to be PROVABLY lagging
     * ([blockPipelineLagging]) — the field incident this closes: the
     * filters sub-progress reported Synced (scan position at tip) one
     * minute into a three-hour replay while the engine's block
     * download/processing pipeline was still churning through the matched
     * blocks, so the app declared l1Synced=true and persisted a partial
     * 48.86 DASH as the last-known balance.
     */
    val scanCaughtUpToTip: Boolean get() =
        headerTarget > 0 && headerHeight >= headerTarget &&
            filterTarget > 0 &&
            headerTarget - filterHeight <= SCAN_TIP_TOLERANCE_BLOCKS &&
            !blockPipelineLagging

    /**
     * The engine's block-download/tx-processing pipeline DEMONSTRABLY
     * trails the header tip: the wallet's committed scan cursor
     * ([walletSyncedHeight]) is known AND more than
     * [SCAN_TIP_TOLERANCE_BLOCKS] behind [headerTarget]. Evidence-based on
     * purpose — an UNKNOWN cursor (0, e.g. before the first
     * `SyncHeightAdvanced` event of a session whose durable watermark seed
     * failed) does NOT count as lagging, so the predicate can never
     * deadlock "synced" when the signal is missing; during any real
     * replay/scan the engine commits batches continuously, so the cursor
     * is live within seconds and the lag is visible for the churn's whole
     * duration.
     */
    val blockPipelineLagging: Boolean get() =
        headerTarget > 0 && walletSyncedHeight > 0 &&
            headerTarget - walletSyncedHeight > SCAN_TIP_TOLERANCE_BLOCKS

    /**
     * The shadow SPV's best knowledge of the NETWORK chain tip, 0 when the
     * snapshot carries no header heights: the reference height the
     * dashj-caught-up gate ([isDashjChainCaughtUp]) compares dashj's chain
     * head against. Uses the larger of current/target — while syncing the
     * target IS the network tip; once synced the two agree.
     */
    val bestKnownTipHeight: Long get() = maxOf(headerHeight, headerTarget)

    companion object {
        val IDLE = ShadowSyncProgress(ShadowSyncPhase.IDLE, 0.0, 0, 0, 0, 0)

        /**
         * How far the wallet-relevant filter scan may trail the header tip
         * and still count as caught up ([scanCaughtUpToTip]). Sized to
         * absorb the live-shadow chase (the header target leading the
         * filter scan by a block or two for a few seconds after each new
         * block); far below any genuine mid-scan gap. Funds-safe: shielding
         * and SDK sends spend ChainLocked/confirmed UTXOs many blocks deep,
         * so the last couple of tip blocks are irrelevant to the spendable
         * set even if not yet filter-scanned.
         */
        const val SCAN_TIP_TOLERANCE_BLOCKS = 2L
    }
}

/**
 * Compact "Kotlin N%" home-screen label (debug builds), or null to hide
 * (shadow idle: flag off or not started). The percent is the COMBINED
 * header+filter progress — (headerHeight + filterHeight) over
 * (headerTarget + filterTarget) — one monotonic number across the whole
 * pipeline; the SDK's own [ShadowSyncProgress.overallPercent] is
 * deliberately NOT used (it under-reports during the filter scan —
 * observed live: 1.0% at filters 1402000/1514660). Renders side by side
 * with the "DashJ N%" status in the sync header, so it stays short.
 * English-only like the rest of the debug instrumentation. Pure —
 * host-testable.
 */
fun kotlinSyncLabel(progress: ShadowSyncProgress, status: L1VerificationStatus): String? =
    when (progress.phase) {
        ShadowSyncPhase.IDLE -> null
        ShadowSyncPhase.CONNECTING -> "Kotlin 0%"
        ShadowSyncPhase.HEADERS, ShadowSyncPhase.FILTER_HEADERS,
        ShadowSyncPhase.MASTERNODES, ShadowSyncPhase.FILTERS -> {
            val done = progress.headerHeight + progress.filterHeight
            val target = progress.headerTarget + progress.filterTarget
            // Cap below 100 while scanning: only the SYNCED phase may claim 100%.
            "Kotlin ${syncPct(done, target).coerceAtMost(99)}%"
        }
        ShadowSyncPhase.SYNCED -> when (status) {
            // The scan is done AND the latest parity probe matched dashj.
            L1VerificationStatus.VERIFIED -> "Kotlin 100% ✓"
            // Terminal harness stand-down — the tester should send logs.
            L1VerificationStatus.FAILED -> "Kotlin 100% (verification failed)"
            else -> "Kotlin 100%"
        }
        ShadowSyncPhase.ERROR -> "Kotlin: error"
    }

/** Integer percent of h/t, clamped to 0..100; 0 while the target is unknown. */
private fun syncPct(h: Long, t: Long): Int =
    if (t <= 0) 0 else ((h * 100) / t).toInt().coerceIn(0, 100)

/**
 * The SDK L1 scan progress as a single 0..100 percent, for the home-screen
 * "Syncing N%" header AFTER cutover (Phase 5d) when the SDK owns L1 and the
 * dashj percent no longer advances. Same combined header+filter metric as
 * [kotlinSyncLabel] (the SDK's own `overallPercent` under-reports during the
 * filter scan); only the SYNCED phase may claim 100%. Pure — host-testable.
 */
fun shadowSyncPercent(progress: ShadowSyncProgress): Int = when (progress.phase) {
    ShadowSyncPhase.IDLE, ShadowSyncPhase.CONNECTING -> 0
    ShadowSyncPhase.SYNCED -> 100
    ShadowSyncPhase.ERROR -> 0
    // A live shadow SPV never latches SYNCED (a new testnet block every
    // ~2.5 min bumps the targets and drops the overall state back to
    // SYNCING — see [ShadowSyncProgress.scanCaughtUpToTip]). So an active
    // scan claims 100% once the filter scan has caught up to a real header
    // tip within [SCAN_TIP_TOLERANCE_BLOCKS]; a genuine mid-scan still caps
    // at 99%. Fail-closed for IDLE/CONNECTING/ERROR above.
    else -> if (progress.scanCaughtUpToTip) {
        100
    } else {
        syncPct(
            progress.headerHeight + progress.filterHeight,
            progress.headerTarget + progress.filterTarget
        ).coerceAtMost(99)
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
        filterTarget = data.filters?.targetHeight ?: 0,
        mnListHeight = data.masternodes?.currentHeight ?: 0
    )
}

/**
 * The throttled `L1Shadow` one-line progress summary. Pure for tests.
 * [ShadowSyncProgress.overallPercent] is the SDK's 0..1 fraction, so it is
 * scaled ×100 here (the line used to print the raw fraction — the field
 * log's "phase=FILTERS 0.7%" was really 70%). Also carries the committed
 * wallet cursor ([ShadowSyncProgress.walletSyncedHeight]) so a
 * filters-at-tip-but-blocks-churning state is visible in one line.
 */
internal fun shadowProgressLine(p: ShadowSyncProgress): String = String.format(
    Locale.US,
    "L1Shadow phase=%s %.1f%% headers %d/%d filters %d/%d wallet %d",
    p.phase, p.overallPercent * 100, p.headerHeight, p.headerTarget, p.filterHeight, p.filterTarget,
    p.walletSyncedHeight
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
 * Whether dashj's initial sync is GENUINELY complete for the purpose of
 * the inflated-mismatch auto-reset rule — pure, host-testable.
 *
 * ## Why the rule needs this gate (live incident, 02:38–02:42)
 *
 * The probe's `synced=` bit ([ParityReport.sdkSynced]) is the SDK SHADOW
 * chain's own SPV state ([ShadowSyncProgress.synced]) — the rule never
 * consulted any dashj-side sync signal at all. After a restore-from-seed
 * the SDK correctly discovered the wallet's full 12.08713251 DASH / 1044+
 * txs in ~3 minutes while dashj was still REPLAYING its chain download
 * (balance climbing from ~0), so `sdk > dashj` held for 3 consecutive
 * probes and the decider hard-reset a CORRECT SDK state — wiping tx
 * records and stranding the balance row (the watermark-strand state that
 * later forced a full wallet re-creation).
 *
 * ## The signal chosen
 *
 * dashj's in-memory chain head (`Wallet.lastBlockSeenHeight`, stamped on
 * every best-chain block dashj processes — it sits at the replay position
 * during a replay/initial download) compared against the shadow SPV's own
 * best knowledge of the network tip
 * ([ShadowSyncProgress.bestKnownTipHeight] — live SYNCED snapshots carry
 * header heights; the [ShadowResetDecider] reset-aftermath detector
 * already relies on that). Both engines see the same network, so when
 * both are genuinely synced the two heights agree within a block or two;
 * during a dashj replay dashj sits thousands of blocks behind. Chosen
 * over the Room-persisted `BlockchainState.replaying/percentageSync`
 * because it needs no new collaborator (the [L1ShadowSource] seam already
 * wraps the dashj wallet), cannot be stale (no persistence round-trip),
 * and directly measures the very quantity the rule mis-read: how far
 * dashj's view of the chain actually reaches.
 *
 * Conservative on missing evidence: an unknown dashj head or an SDK
 * snapshot without header heights returns false — suppressing the reset
 * (it only DELAYS a genuine reset until the next height-carrying probe,
 * whereas firing early destroys correct state, the exact live failure).
 * Streak stability across the 3-probe window is the caller's existing
 * consecutive-probe counter: any not-caught-up probe zeroes the streak.
 */
internal fun isDashjChainCaughtUp(
    dashjChainHeadHeight: Int?,
    sdkBestKnownTipHeight: Long,
    toleranceBlocks: Int = DASHJ_TIP_TOLERANCE_BLOCKS
): Boolean =
    dashjChainHeadHeight != null && sdkBestKnownTipHeight > 0 &&
        dashjChainHeadHeight >= sdkBestKnownTipHeight - toleranceBlocks

/**
 * How far dashj's chain head may trail the shadow SPV's network tip and
 * still count as caught up ([isDashjChainCaughtUp]): both engines track
 * the same network tip when synced, but block propagation and probe
 * timing skew them by a block or so momentarily.
 */
internal const val DASHJ_TIP_TOLERANCE_BLOCKS = 2

/**
 * What a parity probe is allowed to do right now.
 *
 * @property probe run the comparison at all (publish [ParityReport],
 *   record the streak, log the ticker).
 * @property driveVerification let the result move
 *   [L1ShadowSyncService.verificationStatus] (which the shielded-transfer
 *   screen renders and the funding gate reads).
 * @property allowSelfHeal let [ShadowResetDecider] act on the result — up
 *   to and including a full SDK-wallet rebuild.
 */
internal data class ParityProbePolicy(
    val probe: Boolean,
    val driveVerification: Boolean,
    val allowSelfHeal: Boolean
)

/**
 * WHEN PARITY IS MEANINGFUL. The harness compares the SDK's L1 view
 * against dashj's; that only says anything while dashj is a live, syncing
 * engine.
 *
 * - NOT COMMITTED (dual-run): dashj is the primary engine and the wallet
 *   of record. Parity is the whole point — it gates the cutover — so
 *   everything is allowed, exactly as before.
 * - COMMITTED + diagnostic ON: the tester deliberately un-held dashj
 *   (`DASHJ_SYNC_DIAGNOSTIC`), so the comparison is real and worth
 *   showing in Tools — but dashj is a BACKUP, catching up from behind,
 *   and the SDK is the wallet of record. So the probe runs and publishes,
 *   while a disagreement must NOT move the user-facing verification
 *   status and must NOT trigger a self-heal that would destroy the
 *   authoritative SDK ledger on the word of a still-syncing observer.
 * - COMMITTED + diagnostic OFF: dashj is HELD. Its balance is frozen at
 *   the cutover snapshot, so the first receive after the flip makes the
 *   verdict permanently MISMATCH. Probing here measures nothing: it
 *   pinned `verificationStatus` at PROBING forever (the shielded screen's
 *   "Almost done" that never clears) and fed a guaranteed-failing
 *   comparison to the rebuild decider. Do not probe.
 *
 * Pure — host-testable.
 */
internal fun parityProbePolicy(
    cutoverCommitted: Boolean,
    dashjDiagnosticEnabled: Boolean
): ParityProbePolicy = when {
    !cutoverCommitted -> ParityProbePolicy(probe = true, driveVerification = true, allowSelfHeal = true)
    dashjDiagnosticEnabled -> ParityProbePolicy(probe = true, driveVerification = false, allowSelfHeal = false)
    else -> ParityProbePolicy(probe = false, driveVerification = false, allowSelfHeal = false)
}

/**
 * Decides when a persistent parity mismatch warrants a ONE-TIME automatic
 * SELF-HEAL — a full SDK-wallet REBUILD
 * ([L1ShadowSyncService.recoverByRecreatingWallet]) — from the probe
 * stream plus one context bit
 * ([scanLooksComplete][ShadowSyncProgress.scanLooksComplete]).
 *
 * ## Why the rebuild, not the SPV-only reset (the device evidence)
 *
 * The predecessor of this rule hard-reset the shadow SPV on an inflated
 * mismatch (stop SPV, delete the dataDir, clear the L1 rows, rescan from
 * birth). On device that reset RAN and the +0.01 DASH inflation SURVIVED
 * it ("shadow state corrupt after reset — SDK bug"). That is the decisive
 * clue: the bad balance does NOT live in the SPV scan data (headers /
 * filters / TXO rows) the reset wipes — it lives in the SDK's WALLET
 * LEDGER (the Rust key-wallet / persisted `WalletMetadata` state), which
 * the SPV-only reset leaves untouched (its own log even said "the
 * in-memory Rust wallet state is only fully rebuilt on the next app
 * start"). The corrective action therefore has to rebuild the WALLET, not
 * just the scan:
 *
 * ## The self-heal: one full SDK-wallet rebuild, then stand down
 *
 * On a persistent mismatch (either direction) the decider fires
 * [Decision.REBUILD_WALLET] exactly ONCE per process. The service runs
 * [L1ShadowSyncService.recoverByRecreatingWallet]: unbind the SDK wallet
 * and clear ALL SDK-side persistence for it (the full `removeAppWallet`
 * cascade — Room wallet/identity/TXO/address/shielded rows INCLUDING the
 * `syncedHeight` watermark, the Keystore-backed `WalletStorage` COPY of
 * the seed, and the native wallet handle), delete the SPV dataDir, then
 * RE-BIND from the RETAINED wallet seed ([SdkWalletBinder.bindInBackground]
 * with the same non-interactive unlock as startup) — `createWallet`
 * re-derives the SAME deterministic wallet id from the same seed and
 * re-scans from birth into genuinely fresh ledger state. This runs on the
 * ordinary parity-probe loop, so a user who simply INSTALLS the updated
 * build self-heals automatically — no manual reset / restore / debug
 * broadcast needed.
 *
 * NON-DESTRUCTIVE to user data: every step touches SDK-owned state ONLY.
 * The dashj wallet (the L1 source of truth holding the user's funds), the
 * user's seed, and their keys are never reached by any collaborator on
 * this path ([recoverByRecreatingWallet]'s KDoc has the full safety
 * proof) — the canonical seed lives encrypted in the dashj wallet and the
 * rebind re-stores the SDK's copy from freshly-decrypted words.
 *
 * If the inflation/deficit SURVIVES the rebuild too (a fresh
 * post-rebuild resync still mismatches for [requiredConsecutiveProbes]
 * probes), the decider fires [Decision.STAND_DOWN] once: this is a
 * DETERMINISTIC SDK ledger bug that no APK-side self-heal can fix — the
 * service logs LOUDLY (upstream rust dash-spv / key-wallet ticket), marks
 * verification FAILED, and does NOT rebuild again (dashj stays primary;
 * the parity gate already blocks the cutover — the correct safety). No
 * churn.
 *
 * The rebuild latch is SHARED across both mismatch directions: one full
 * wallet rebuild fixes the ledger regardless of which way it was wrong,
 * and a second rebuild would only churn. A plain DEFICIT (`sdk < dashj`
 * with `sdkTxCount > 0` or an incomplete scan) is the classic SDK
 * scan-gap bug class the harness must SURFACE, not act on — it only logs
 * the MISMATCH (Decision.NONE), unchanged.
 *
 * ## Decision table (evaluated per probe; `empty deficit` = synced
 * mismatch with sdk < dashj AND sdkTxCount == 0 AND scanLooksComplete)
 *
 * | probe state                     | consecutive | prior action this process | decision |
 * |---------------------------------|-------------|----------------------------|----------|
 * | not synced                      | streaks → 0 | —                          | NONE |
 * | synced, balances match          | streaks → 0 | —                          | NONE |
 * | sdk > dashj, dashj NOT caught up| streaks → 0 | —                          | NONE (dashj mid-sync/replay — see [isDashjChainCaughtUp]) |
 * | sdk > dashj, recent self-spend  | streaks → 0 | —                          | NONE (legitimate self-spend inflation) |
 * | inflated (sdk > dashj)          | < threshold | —                          | NONE |
 * | inflated                        | ≥ threshold | no rebuild yet             | REBUILD_WALLET (once) |
 * | inflated                        | ≥ threshold | rebuild ran               | STAND_DOWN (once), then NONE |
 * | deficit, sdkTx > 0 or scan open | streaks → 0 | —                          | NONE (MISMATCH log only) |
 * | empty deficit                   | < threshold | —                          | NONE |
 * | empty deficit                   | ≥ threshold | no rebuild yet             | REBUILD_WALLET (once) |
 * | empty deficit                   | ≥ threshold | rebuild ran               | STAND_DOWN (once), then NONE |
 *
 * Each acting verdict fires at most once per process (per decider
 * instance): one REBUILD_WALLET (shared across directions), one
 * STAND_DOWN. After the rebuild the shadow re-scans, so `synced=false`
 * probes zero both streaks; only a FULL post-rebuild resync that still
 * shows the mismatch for [requiredConsecutiveProbes] probes reaches the
 * stand-down rows.
 */
internal class ShadowResetDecider(
    private val requiredConsecutiveProbes: Int = RESET_CONSECUTIVE_PROBES
) {
    enum class Decision { NONE, REBUILD_WALLET, STAND_DOWN }

    private var consecutiveInflated = 0
    private var consecutiveEmptyDeficit = 0
    private var rebuildIssued = false
    private var standDownReported = false

    fun onProbe(
        report: ParityReport,
        scanLooksComplete: Boolean = false,
        // A flag-gated SDK L1 send (Phase 5b, SdkL1SendService) was broadcast recently.
        // A self-spend legitimately INFLATES the SDK view for minutes: the SDK's
        // compact-filter SPV only applies the spend once it is MINED and filter-scanned,
        // while dashj's bloom filters see the mempool tx within seconds and drop its
        // ESTIMATED balance immediately — so sdk > dashj until the next block lands.
        // Rebuilding healthy shadow state on that evidence would be wrong, so inflated
        // streaks are zeroed while the marker is fresh. The DEFICIT direction needs no
        // guard: the empty-deficit signature requires sdkTxCount == 0, which is impossible
        // right after a send from a wallet whose parity-gated (non-zero, TXO-backed)
        // balance just funded the spend.
        recentSelfSpendMarker: Boolean = false,
        // dashj's initial sync is GENUINELY complete (chain head at the network tip — see
        // isDashjChainCaughtUp). The INFLATED direction is meaningless while dashj is still
        // replaying/downloading: its balance is climbing toward the truth, so a correct SDK
        // view trivially reads sdk > dashj (the live 02:38 incident — a hard reset wiped a
        // CORRECT SDK state mid-replay). Not-caught-up probes zero the inflated streak, which
        // also enforces stability across the consecutive-probe window. Defaults to true so
        // callers with no dashj sync signal keep the pre-gate semantics (a genuinely inflated
        // view with both engines synced must still self-heal). The DEFICIT direction is NOT
        // gated: its empty-deficit signature is about SDK-side scan state, not dashj's.
        dashjChainCaughtUp: Boolean = true
    ): Decision {
        val mismatch = report.sdkSynced && !report.balancesMatch
        val inflated = mismatch && report.sdkDuffs > report.dashjDuffs &&
            !recentSelfSpendMarker && dashjChainCaughtUp
        val emptyDeficit = mismatch && report.sdkDuffs < report.dashjDuffs &&
            report.sdkTxCount == 0 && scanLooksComplete

        if (!inflated) consecutiveInflated = 0
        if (!emptyDeficit) consecutiveEmptyDeficit = 0

        if (inflated) {
            consecutiveInflated++
            if (consecutiveInflated < requiredConsecutiveProbes) return Decision.NONE
            return rebuildOrStandDown()
        }
        if (emptyDeficit) {
            consecutiveEmptyDeficit++
            if (consecutiveEmptyDeficit < requiredConsecutiveProbes) return Decision.NONE
            return rebuildOrStandDown()
        }
        return Decision.NONE
    }

    /**
     * One full SDK-wallet REBUILD self-heal per process, then STAND DOWN if
     * the mismatch survives it — the shared once-per-process latch for both
     * mismatch directions.
     */
    private fun rebuildOrStandDown(): Decision = when {
        !rebuildIssued -> {
            rebuildIssued = true
            // The rebuild stops this probe loop and re-scans from birth, so
            // both streaks must restart clean — a fresh post-rebuild resync
            // is what earns the STAND_DOWN verdict.
            consecutiveInflated = 0
            consecutiveEmptyDeficit = 0
            Decision.REBUILD_WALLET
        }
        !standDownReported -> {
            standDownReported = true
            Decision.STAND_DOWN
        }
        else -> Decision.NONE
    }

    companion object {
        /** Synced mismatch probes required before acting (either direction). */
        internal const val RESET_CONSECUTIVE_PROBES = 3
    }
}

/**
 * Once-per-process restart decision for the parity-probe watchdog: the
 * probe loop was observed dying silently in the field (no `L1Parity` log
 * for an hour — an uncaught loop error or a cancelled scope). If the
 * heartbeat ([lastHeartbeatMs], stamped at the top of every probe-loop
 * iteration) goes stale for [stallThresholdMs] while the service believes
 * it is running, the watchdog restarts the loop ONCE per process
 * ([Decision.RESTART]); a second stall reports [Decision.EXHAUSTED] once
 * (ERROR, stand down) and everything after that is silent. Pure, so the
 * restart-once semantics are host-JVM unit-testable.
 */
internal class ProbeWatchdogDecider(
    private val stallThresholdMs: Long = L1ShadowSyncService.PROBE_STALL_THRESHOLD_MS
) {
    enum class Decision { NONE, RESTART, EXHAUSTED }

    private var restartIssued = false
    private var exhaustedReported = false

    fun onCheck(nowMs: Long, lastHeartbeatMs: Long): Decision {
        if (nowMs - lastHeartbeatMs < stallThresholdMs) return Decision.NONE
        return when {
            !restartIssued -> {
                restartIssued = true
                Decision.RESTART
            }
            !exhaustedReported -> {
                exhaustedReported = true
                Decision.EXHAUSTED
            }
            else -> Decision.NONE
        }
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
        if (id != null) seen += wireHexOf(id)
    }
    for (id in spendingTxids) {
        if (id != null) seen += wireHexOf(id)
    }
    return seen.size
}

// ── Engine wallet-event tap (the instant-receive feed) ────────────────

/**
 * Neutral projection of the Rust engine's per-transaction `WalletEvent`s,
 * for the pre-block receive pipeline ([CutoverUiDataService]'s tx feed).
 *
 * ## Where these come from (the exact binding surface)
 *
 * The shared dash-spv engine's mempool tracker (ON by default —
 * `enable_mempool_tracking`) calls `process_mempool_transaction`, which
 * emits `WalletEvent::TransactionDetected` on the first off-chain
 * sighting of a wallet-relevant tx and `WalletEvent::TransactionInstantLocked`
 * when an IS lock lands on a previously-seen one
 * (`key-wallet-manager/src/process_block.rs`). Every `WalletEvent` is
 * forwarded through the FFI event-handler vtable as its Rust `Debug`
 * string (`rs-platform-wallet-ffi/src/event_handler.rs on_wallet_event`:
 * `format!("{:?}", event)`) → JNI trampoline →
 * `org.dashfoundation.dashsdk.ffi.NativeWalletEventBridge.onWalletEvent(String)`
 * → `PlatformWalletManager.syncEvents`
 * (`SharedFlow<WalletSyncEvent>`, variant `WalletSyncEvent.Generic(debug)`).
 * That debug-string `Generic` is the ONLY surface the pinned AAR exposes
 * for these two events (no typed callback slot exists), so
 * [parseL1TxEvent] extracts the display fields from it.
 */
sealed class L1TxEvent {
    /**
     * First off-chain sighting of a wallet-relevant tx: mempool
     * ([contextCode] 0) or IS-lock-first ([contextCode] 1 — the record was
     * born with `TransactionContext::InstantSend`).
     *
     * [netAmountDuffs] is the engine's own wallet-side computation —
     * `total_received - total_sent` over resolved wallet inputs/outputs
     * (the same Σin−Σout the iOS app computes app-side; here the engine
     * already did it, so no raw-byte fallback is needed).
     */
    data class Detected(
        /** DISPLAY-order (byte-reversed) txid hex — dashj `Sha256Hash.toString()` convention. */
        val txidHex: String,
        val netAmountDuffs: Long,
        /** Fee in duffs when the engine knows it (self-authored sends), else null. */
        val feeDuffs: Long?,
        /** `transactions.context` code: 0=mempool, 1=instantSend, 2=inBlock, 3=chainLocked. */
        val contextCode: Int,
        /** `transactions.direction` code: 0=in, 1=out, 2=internal, 3=coinjoin. */
        val directionCode: Int
    ) : L1TxEvent()

    /** An InstantSend lock was applied to a previously-seen tx. */
    data class InstantLocked(val txidHex: String) : L1TxEvent()
}

/*
 * Anchored field extractors for the `WalletEvent` Debug string. Anchoring
 * matters: `txid:` also appears inside the record's `Transaction` (every
 * input's `OutPoint { txid: .., vout: .. }`) and inside `InstantLock`
 * (`txid: .., cyclehash: ..`), so the record's OWN txid is matched by its
 * unique following field (`account_type` / `instant_lock`). `Txid`'s Debug
 * is `{:#}` LowerHex — `0x` + 64 display-order hex chars (dash hashes
 * print byte-reversed), which is exactly the app's rowId convention.
 */
private val TX_EVENT_RECORD_TXID = Regex("""\btxid: (?:0x)?([0-9a-fA-F]{64}), account_type:""")
private val TX_EVENT_AMOUNTS = Regex("""\bnet_amount: (-?\d+), fee: (?:Some\((\d+)\)|None)""")
private val TX_EVENT_DIRECTION = Regex("""\bdirection: (Incoming|Outgoing|Internal|CoinJoin)\b""")
private val TX_EVENT_CONTEXT = Regex("""\bcontext: (Mempool|InstantSend|InBlock|InChainLockedBlock)\b""")
private val TX_EVENT_ISLOCK_TXID = Regex("""\btxid: (?:0x)?([0-9a-fA-F]{64}), instant_lock:""")

/**
 * Parse one engine wallet-event Debug string into an [L1TxEvent], or null
 * for every other event kind (`BlockProcessed`, `SyncHeightAdvanced`,
 * `ChainLockProcessed`, …) and for anything that fails to parse — a
 * malformed event must degrade to "no instant feed" (the Room snapshot
 * pipeline still converges), never to a wrong row. Pure — host-testable.
 */
fun parseL1TxEvent(eventDebug: String): L1TxEvent? = when {
    eventDebug.startsWith("TransactionDetected") -> {
        val txid = TX_EVENT_RECORD_TXID.find(eventDebug)?.groupValues?.get(1)
        val amounts = TX_EVENT_AMOUNTS.find(eventDebug)
        val net = amounts?.groupValues?.get(1)?.toLongOrNull()
        val direction = TX_EVENT_DIRECTION.find(eventDebug)?.groupValues?.get(1)
        val context = TX_EVENT_CONTEXT.find(eventDebug)?.groupValues?.get(1)
        if (txid == null || net == null || direction == null || context == null) {
            null
        } else {
            L1TxEvent.Detected(
                txidHex = txid.lowercase(Locale.US),
                netAmountDuffs = net,
                feeDuffs = amounts.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull(),
                contextCode = when (context) {
                    "Mempool" -> 0
                    "InstantSend" -> 1
                    "InBlock" -> 2
                    else -> 3
                },
                directionCode = when (direction) {
                    "Incoming" -> 0
                    "Outgoing" -> 1
                    "Internal" -> 2
                    else -> 3
                }
            )
        }
    }
    eventDebug.startsWith("TransactionInstantLocked") ->
        TX_EVENT_ISLOCK_TXID.find(eventDebug)?.let {
            L1TxEvent.InstantLocked(it.groupValues[1].lowercase(Locale.US))
        }
    else -> null
}

/**
 * The `ChainLock`'s own `block_height` inside a `WalletEvent` Debug
 * string. `block_height` is the field name of
 * `dashcore::ephemerealdata::chain_lock::ChainLock` ONLY — a transaction's
 * own block height is `BlockInfo { height: .. }` and `BlockProcessed`'s is
 * `height: ..`, so this cannot match a non-chainlock number. Anchored on
 * the `chain_lock:` field so it also cannot drift onto some future field
 * that happens to be called `block_height`; `\s*` (not a literal space)
 * tolerates a pretty-printed `{:#?}` should the FFI ever switch.
 */
private val CHAIN_LOCK_HEIGHT = Regex("""\bchain_lock:\s*(?:Some\(\s*)?ChainLock\s*\{\s*block_height:\s*(\d+)""")

/**
 * The GLOBAL best-chainlocked block height carried by one engine
 * wallet-event Debug string, or null when the event carries no chainlock.
 *
 * Two variants carry one (`key-wallet-manager/src/events.rs`, both
 * `#[derive(Debug)]`):
 * - `ChainLockProcessed { .., chain_lock: ChainLock { block_height: H, .. }, .. }`
 *   — emitted by `WalletManager::apply_chain_lock` whenever the wallet's
 *   `last_applied_chain_lock` ADVANCED (replays of the same chainlock are
 *   silent). `dash-spv`'s `spawn_chainlock_wallet_dispatch` only forwards
 *   VALIDATED chainlocks, buffering the highest one during the initial
 *   sync cycle and applying it at `SyncComplete { cycle: 0 }`;
 * - `BlockProcessed { .., chain_lock: Some(ChainLock { block_height: H, .. }), .. }`
 *   — a block processed while already chainlocked.
 *
 * H is a real quorum-signed chainlock height (the proof travels with it),
 * so it is authoritative, not an inference from wallet transactions.
 *
 * LOWER BOUND, by construction: the app only learns of chainlocks the
 * engine applied while this process was running and collecting, so the
 * value trails the true network chainlock tip (it is 0 until the first
 * event of a session). Every consumer must treat "below the reported
 * height" as proven-chainlocked and everything above as merely UNKNOWN —
 * under-reporting is conservative, over-reporting would not be. Pure —
 * host-testable.
 */
fun parseL1ChainLockHeight(eventDebug: String): Int? =
    CHAIN_LOCK_HEIGHT.find(eventDebug)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

/**
 * The `height` of a `WalletEvent::SyncHeightAdvanced` Debug string —
 * "the wallet's scan cursor advanced because the filter pipeline committed
 * a batch covering blocks up to `height`" (key-wallet-manager events.rs).
 * The commit lands AFTER the batch's matched blocks were processed through
 * the wallet, so this height is the drained-through watermark the
 * caught-up predicate needs ([ShadowSyncProgress.walletSyncedHeight]).
 * Anchored on the variant name: `height:` also appears in `BlockProcessed`
 * / `BlockInfo`, which must not feed the cursor (a block's own height says
 * nothing about the batch being committed). Null for every other event and
 * for anything that fails to parse. Pure — host-testable.
 */
fun parseL1SyncHeightAdvanced(eventDebug: String): Long? =
    if (eventDebug.startsWith("SyncHeightAdvanced")) {
        SYNC_HEIGHT_ADVANCED_HEIGHT.find(eventDebug)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it > 0 }
    } else {
        null
    }

/** `SyncHeightAdvanced { wallet_id: .., height: N }` — the only `height:` field in that variant. */
private val SYNC_HEIGHT_ADVANCED_HEIGHT = Regex("""\bheight:\s*(\d+)""")

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

    /**
     * The manager's hot wallet-event feed as raw Rust `Debug` strings —
     * `PlatformWalletManager.syncEvents` filtered to
     * `WalletSyncEvent.Generic` (see [L1TxEvent] for the full delivery
     * chain). Default empty so test fakes and the fake-source seam stay
     * source-compatible; the production source overrides.
     */
    fun walletEventStrings(): Flow<String> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * The wallet's DURABLE filter-scan watermark (`WalletEntity.syncedHeight`
     * from the SDK's Room `wallets` row), or null when unknown — the seed
     * for [ShadowSyncProgress.walletSyncedHeight] before the session's
     * first `SyncHeightAdvanced` event lands. Default null so test fakes
     * stay source-compatible (null = no evidence, never treated as lagging).
     */
    suspend fun sdkWalletSyncedHeight(walletIdHex: String): Long? = null

    /** (confirmed, unconfirmed) duffs from the SDK wallet's lock-free L1 balance. */
    suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long>

    /** Distinct wallet-relevant tx count from the SDK's TXO store. */
    suspend fun sdkTxCount(walletIdHex: String): Int

    /** (ESTIMATED, AVAILABLE) duffs from the dashj wallet, or null when it isn't loaded. */
    suspend fun dashjBalanceDuffs(): Pair<Long, Long>?

    /** dashj wallet tx count (`getTransactions(false)`), or null when it isn't loaded. */
    suspend fun dashjTxCount(): Int?

    /**
     * dashj's chain-head height (`Wallet.lastBlockSeenHeight` — the last
     * best-chain block dashj processed; sits at the replay position during
     * an initial sync/replay), or null when the wallet isn't loaded. Feeds
     * the [isDashjChainCaughtUp] gate on the inflated auto-reset rule.
     */
    suspend fun dashjChainHeadHeight(): Int?

    /** Every unspent TXO row of the wallet from the SDK's Room store, normalized. */
    suspend fun sdkUnspentUtxos(walletIdHex: String): List<L1Utxo>

    /**
     * dashj's unspent outputs — `calculateAllSpendCandidates(false, false)`,
     * the exact set `getBalance(ESTIMATED)` sums (all keychains, including
     * legacy CoinJoin-derivation funds) — or null when the wallet isn't
     * loaded.
     */
    suspend fun dashjUnspentUtxos(): List<L1Utxo>?

    /**
     * Clear the Rust SPV client's persisted storage (headers, filters,
     * state) via the SDK.
     *
     * ## KNOWN SDK LIMITATION — do not rely on this for a reset
     *
     * Traced through the SDK/Rust sources (`PlatformWalletManager.clearSpvStorage`
     * → `platform_wallet_manager_spv_clear_storage` →
     * `rs-platform-wallet/src/spv/runtime.rs SpvRuntime::clear_storage`):
     * the storage manager that knows the [startSpv] `dataDir` lives INSIDE
     * the running `DashSpvClient`, and `SpvRuntime::stop()` drops that
     * client (`self.client.take()`). `clear_storage()` then errors with
     * "SPV Client not started" and touches NO files — the runtime does not
     * remember the configured dataDir once stopped. So the natural
     * stop→clear→restart sequence is guaranteed to leave the on-disk
     * header store and scan watermark intact (the live incident: a
     * post-reset "full rescan" that went IDLE→SYNCED in six seconds and
     * left `sdk=0`). Clearing while RUNNING instead races the client's 5s
     * persistence worker. SDK-issue material: `clearSpvStorage` should
     * honor the configured dataDir when the client is stopped. Until then
     * [L1ShadowSyncService.resetShadowState] hard-deletes the dataDir at
     * the filesystem level and only the legacy soft path calls this.
     */
    suspend fun clearSpvStorage()

    /**
     * Delete the SDK wallet's persisted L1 view — its TXO rows and the
     * transaction rows they reference — so the next scan rebuilds them.
     */
    suspend fun clearSdkL1Rows(walletIdHex: String)
}

/** Production [L1ShadowSource]: boots the SDK on demand; reads dashj via [WalletData]. */
internal class DashSdkL1ShadowSource(
    private val service: DashSdkService,
    private val walletData: WalletData
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

    override fun walletEventStrings(): Flow<String> = flow {
        emitAll(
            manager().syncEvents
                .filterIsInstance<org.dashfoundation.dashsdk.wallet.WalletSyncEvent.Generic>()
                .map { it.debug }
        )
    }

    override suspend fun sdkWalletSyncedHeight(walletIdHex: String): Long? {
        val walletId = walletIdFromHex(walletIdHex) ?: return null
        return database().walletDao().getByWalletId(walletId)?.syncedHeight?.toLong()
    }

    override suspend fun sdkBalanceDuffs(walletIdHex: String): Pair<Long, Long> {
        val wallet = checkNotNull(manager().wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        val balance = wallet.balance()
        return balance.confirmed to balance.unconfirmed
    }

    override suspend fun sdkTxCount(walletIdHex: String): Int {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        // SQL-side distinct count (multi-day-sync fix): the old
        // `observeByWallet().first()` + per-byte hex dedup materialized the
        // ENTIRE wallet `txos` table on every parity probe — O(n) entities +
        // 2n hex strings each tick on a very large wallet. The AAR's DAO has
        // no such query, so count on the Room handle directly; UNION dedups
        // the funding/spending txid sets exactly like [distinctTxCount]
        // (which stays as the pure reference implementation for tests/fakes).
        val db = database()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT COUNT(*) FROM (" +
                        "SELECT txid AS t FROM txos WHERE walletId = ? AND txid IS NOT NULL " +
                        "UNION " +
                        "SELECT spendingTxid AS t FROM txos WHERE walletId = ? AND spendingTxid IS NOT NULL)",
                    arrayOf<Any?>(walletId, walletId)
                )
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    override suspend fun dashjBalanceDuffs(): Pair<Long, Long>? =
        walletData.wallet?.let {
            it.getBalance(BalanceType.ESTIMATED).value to it.getBalance(BalanceType.AVAILABLE).value
        }

    override suspend fun dashjTxCount(): Int? =
        walletData.wallet?.getTransactions(false)?.size

    override suspend fun dashjChainHeadHeight(): Int? =
        walletData.wallet?.lastBlockSeenHeight

    override suspend fun sdkUnspentUtxos(walletIdHex: String): List<L1Utxo> {
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        val rows = database().txoDao().observeUnspentByWallet(walletId).first()
        return rows.map { row ->
            // Room stores wire-order (little-endian) txid bytes; the row's
            // 36-byte outpoint PK is txid + vout-LE, the fallback when the
            // txid FK is still null (brief insert window).
            val rawTxid = row.txid ?: row.outpoint.copyOfRange(0, minOf(32, row.outpoint.size))
            L1Utxo(
                txidHex = displayHexOf(rawTxid),
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
        // Distinct txid blobs straight from SQL — the old path materialized
        // every full TXO entity (all 19 columns) just to collect the two txid
        // columns, the same unbounded-read class the parity-probe fix removed.
        val txids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = ArrayList<ByteArray>()
            db.openHelper.readableDatabase.query(
                androidx.sqlite.db.SimpleSQLiteQuery(
                    "SELECT txid AS t FROM txos WHERE walletId = ? AND txid IS NOT NULL " +
                        "UNION " +
                        "SELECT spendingTxid AS t FROM txos WHERE walletId = ? AND spendingTxid IS NOT NULL",
                    arrayOf<Any?>(walletId, walletId)
                )
            ).use { cursor ->
                while (cursor.moveToNext()) out += cursor.getBlob(0)
            }
            out
        }
        for (txid in txids) {
            db.transactionDao().deleteByTxid(txid)
        }
        db.txoDao().deleteByWallet(walletId)
    }
}

// ── Wallet-recreation seam ────────────────────────────────────────────

/**
 * Seam over the collaborators of the DEFINITIVE shadow recovery —
 * full SDK-wallet re-creation
 * ([L1ShadowSyncService.recoverByRecreatingWallet]) — so the recovery
 * orchestration is host-JVM unit-testable. Everything here is SDK-side
 * only; no implementation can reach dashj state (see the recovery KDoc's
 * safety contract).
 */
interface ShadowWalletRecreator {
    /**
     * Stop the shielded-balance runtime (no-op when not running) so
     * nothing touches the wallet's shielded rows mid-removal — the
     * removal cascade deletes them ([DashSdkService.removeAppWallet]).
     */
    suspend fun stopShieldedSync()

    /** The full wallet-removal cascade — [DashSdkService.removeAppWallet]. */
    suspend fun removeSdkWallet(walletIdHex: String)

    /**
     * Clear the binder's success latch + bound-id cache
     * ([SdkWalletBinder.resetForWalletRecreation]) so the next bind pass
     * re-creates instead of latching on stale state.
     */
    suspend fun resetBinderLatch()

    /**
     * Fire-and-forget full bind pass — `createWallet` (now with the
     * checkpoint-mapped birth height) + identity discovery + key heal —
     * using the same non-interactive unlock recipe as the startup bind
     * ([NonInteractiveWalletUnlock]). Never throws; failures are logged
     * inside the binder.
     */
    fun rebindInBackground(): Job
}

/** Production [ShadowWalletRecreator]: the real SDK service, binder and shielded runtime. */
internal class DashSdkShadowWalletRecreator(
    private val sdkService: DashSdkService,
    private val binder: SdkWalletBinder,
    /** Lazy: breaks the Dagger cycle (ShieldedBalanceServiceImpl injects L1ShadowSyncService). */
    private val shielded: () -> ShieldedBalanceService,
    private val unlock: NonInteractiveWalletUnlock
) : ShadowWalletRecreator {
    override suspend fun stopShieldedSync() = shielded().stop()

    override suspend fun removeSdkWallet(walletIdHex: String) =
        sdkService.removeAppWallet(walletIdHex)

    override suspend fun resetBinderLatch() = binder.resetForWalletRecreation()

    override fun rebindInBackground(): Job = binder.bindInBackground(unlock::unlockOrNull)
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
 * ## The probe (every 60s while running, including after SYNCED, plus
 * one immediate probe on each transition into SYNCED — see
 * [syncedEdgeSignal])
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
    /**
     * The RELAXED probe cadence once the fast cadence has done its job (the
     * cutover committed, or the first sustained caught-up MATCH streak
     * completed — see [slowParityCadenceActive]). The 10s fast cadence exists
     * only to fill [CutoverPolicy.MIN_PARITY_STREAK] promptly (~20-30s to the
     * auto-commit); on a MULTI-DAY sync of a large wallet it otherwise keeps
     * paying the full probe cost every 10s forever.
     */
    private val paritySlowIntervalMs: Long = PARITY_SLOW_INTERVAL_MS,
    private val progressLogIntervalMs: Long = PROGRESS_LOG_INTERVAL_MS,
    private val watchdogIntervalMs: Long = WATCHDOG_INTERVAL_MS,
    private val probeStallThresholdMs: Long = PROBE_STALL_THRESHOLD_MS,
    /** Wallet-recreation collaborators; null (tests' default) disables [recoverByRecreatingWallet]. */
    private val recreator: ShadowWalletRecreator? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        walletData: WalletData,
        dashPayConfig: DashPayConfig,
        scope: CoroutineScope,
        sdkWalletBinder: SdkWalletBinder,
        // Provider breaks the Dagger cycle: ShieldedBalanceServiceImpl's
        // @Inject constructor takes L1ShadowSyncService (funding gate).
        shieldedBalanceService: javax.inject.Provider<ShieldedBalanceService>,
        nonInteractiveWalletUnlock: NonInteractiveWalletUnlock
    ) : this(
        source = DashSdkL1ShadowSource(sdkService, walletData),
        dashPayConfig = dashPayConfig,
        scope = scope,
        // Lazy: Constants/native untouched at construction (inertness).
        spvDataDirPath = {
            val network = toSdkNetwork(Constants.NETWORK_PARAMETERS)
            File(context.filesDir, "l1_shadow_spv/${network.networkName}").absolutePath
        },
        recreator = DashSdkShadowWalletRecreator(
            sdkService = sdkService,
            binder = sdkWalletBinder,
            shielded = { shieldedBalanceService.get() },
            unlock = nonInteractiveWalletUnlock
        )
    )

    /** Serializes [startIfEnabled]/[stop] — the single-flight guarantee. */
    private val mutex = Mutex()

    /**
     * Serializes [recoverByRecreatingWallet] passes. Separate from [mutex]
     * (which is NOT reentrant and is taken by the [stop] and locked steps
     * INSIDE a recovery pass); lock order is always recoveryMutex → mutex.
     */
    private val recoveryMutex = Mutex()

    /** Running latch: the wallet id the probe compares, null when stopped. */
    private val runningWalletIdHex = MutableStateFlow<String?>(null)

    private var monitorJob: Job? = null
    private var parityJob: Job? = null
    private var watchdogJob: Job? = null
    private var eventTapJob: Job? = null

    /**
     * Parsed per-transaction engine events ([L1TxEvent]), live while the
     * shadow runs — the INSTANT receive feed [CutoverUiDataService]'s tx
     * pipeline consumes to insert mempool receives / flip IS-lock state
     * without waiting for a block (or for the engine's Room persistence
     * pass). Replay 0: consumers back-fill from the Room snapshot flow,
     * so a pre-subscription event is never lost, only slower.
     */
    private val _txEvents = MutableSharedFlow<L1TxEvent>(
        replay = 0,
        extraBufferCapacity = TX_EVENT_BUFFER_CAPACITY
    )

    /** Hot stream of wallet-relevant engine tx events (see [_txEvents]). */
    val txEvents: SharedFlow<L1TxEvent> = _txEvents.asSharedFlow()

    private val _chainLockHeight = MutableStateFlow(0)

    /**
     * The highest GLOBAL chainlock height the SDK L1 engine has applied
     * this session ([parseL1ChainLockHeight]), 0 before the first
     * chainlock event lands. MONOTONIC — a later event never lowers it.
     *
     * This is the post-cutover replacement for dashj's
     * `chainLockHandler.bestChainLockBlockHeight`, which stops advancing
     * the moment the peergroup is held: [SdkBlockchainStateService] carries
     * it into the persisted `BlockchainState.chainlockHeight` so
     * [de.schildbach.wallet.payments.ChainLockedCoinSelector] and the
     * transaction-status readouts keep working. A LOWER BOUND on the true
     * chainlock tip (see [parseL1ChainLockHeight]) — safe because every
     * consumer treats it as "proven chainlocked at or below this height".
     */
    val chainLockHeight: StateFlow<Int> = _chainLockHeight.asStateFlow()

    /**
     * The wallet's committed scan cursor for
     * [ShadowSyncProgress.walletSyncedHeight]: seeded from the durable
     * `WalletEntity.syncedHeight` at shadow start
     * ([L1ShadowSource.sdkWalletSyncedHeight]), then LATEST-WINS updated
     * from `SyncHeightAdvanced` events ([parseL1SyncHeightAdvanced]) — not
     * monotonic, because an armed rescan rewinds the engine cursor and the
     * follow-up events re-climb from the rewound floor (a max() here would
     * hide exactly the replay churn the drain predicate exists to see).
     * 0 = unknown. Reset on [stop]; re-seeded on the next start.
     */
    private val _engineWalletSyncedHeight = MutableStateFlow(0L)

    /**
     * Whether the wallet-event tap coroutine feeding [txEvents] is live.
     * Observability seam for [CutoverUiDataService]: the tap is gated on
     * USE_KOTLIN_SDK_L1_SHADOW ([startIfEnabled]) while the cutover UI
     * gates on CUTOVER_STATE, so a committed cutover with the shadow flag
     * off would silently degrade instant receives to block cadence — the
     * consumer checks this once and WARNs on the mismatch.
     */
    val isTapActive: Boolean get() = eventTapJob?.isActive == true

    /**
     * Wakes [parityLoop] early on the progress feed's transition INTO
     * SYNCED. The funding gate needs a FRESH parity report
     * (ShieldedBalanceServiceImpl's evaluateWalletFundingGate), and after
     * every dashj idle-restart the shadow re-syncs in seconds while the
     * next [parityIntervalMs] tick was up to a minute away — measured 53s
     * of gate-closed per idle cycle. CONFLATED: at most one ping is ever
     * pending, so a flapping phase can never queue a probe storm.
     */
    private val syncedEdgeSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Heartbeat: wall-clock time of the last parity-loop iteration START
     * (stamped even when the probe itself fails — the watchdog measures
     * loop LIVENESS, not probe success).
     */
    @Volatile
    private var lastProbeHeartbeatMs = 0L

    /** The once-per-process probe-loop restart state (see [ProbeWatchdogDecider]). */
    private val watchdogDecider = ProbeWatchdogDecider(probeStallThresholdMs)

    /**
     * Wall-clock ms of the last app-initiated SDK L1 SELF-SPEND broadcast
     * ([SdkL1SendService], Phase 5b), 0 when none. While fresh
     * ([SELF_SPEND_GRACE_MS]) the reset decider must not act on an
     * INFLATED parity mismatch — the spend legitimately inflates the SDK
     * view until it is mined and filter-scanned (see the
     * [ShadowResetDecider.onProbe] `recentSelfSpendMarker` doc).
     */
    @Volatile
    private var lastSelfSpendMs = 0L

    /**
     * Record that the app just broadcast an L1 transaction THROUGH THE SDK
     * spending the shared wallet's UTXOs ([SdkL1SendService]). In-memory
     * only: after a process death the SDK's own state already reflects the
     * spend, so no cross-process marker is needed.
     */
    fun noteSelfSpendBroadcast() {
        lastSelfSpendMs = nowMs()
    }

    private val _progress = MutableStateFlow(ShadowSyncProgress.IDLE)

    /** Live shadow SPV progress ([ShadowSyncProgress.IDLE] while stopped). */
    val progress: StateFlow<ShadowSyncProgress> = _progress.asStateFlow()

    private val _latestParity = MutableStateFlow<ParityReport?>(null)

    /** The most recent parity measurement, for a future debug UI. */
    val latestParity: StateFlow<ParityReport?> = _latestParity.asStateFlow()

    /**
     * Phase 5d: bounded parity-observation window feeding the cutover
     * readiness evaluator ([evaluateCutoverReadiness]) — recorded once per
     * probe on the MONOTONIC clock, cleared on shadow reset so pre-reset
     * evidence never counts toward a cutover.
     */
    val parityStreakRecorder = ParityStreakRecorder()

    /**
     * The current [parityProbePolicy], re-resolved at the top of every
     * [parityLoop] iteration (the config reads are DataStore I/O, far too
     * expensive for the 1 Hz progress monitor to repeat). Starts at the
     * pre-cutover "everything allowed" value so behaviour is unchanged for
     * the fraction of a second before the loop's first — immediate —
     * iteration lands.
     */
    @Volatile
    private var parityPolicy = ParityProbePolicy(
        probe = true,
        driveVerification = true,
        allowSelfHeal = true
    )

    /**
     * Resolve [parityProbePolicy] from persisted state. Fails OPEN to the
     * pre-cutover policy on a read error — probing when it turns out to be
     * meaningless only costs a wasted comparison, whereas silently not
     * probing during a real dual-run would starve the cutover streak.
     */
    private suspend fun resolveParityPolicy(): ParityProbePolicy {
        val committed = try {
            !dashjEngineMayStart(CutoverState.fromStored(dashPayConfig.get(DashPayConfig.CUTOVER_STATE)))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("parity policy: cutover state unreadable; assuming dual-run", t)
            false
        }
        val diagnostic = try {
            dashPayConfig.getDashjSyncDiagnostic()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
        return parityProbePolicy(committed, diagnostic)
    }

    private val _verificationStatus = MutableStateFlow(L1VerificationStatus.UNKNOWN)

    /**
     * The coarse user-observable verification verdict (see
     * [L1VerificationStatus]). Fed by the progress monitor (SCANNING ↔
     * PROBING), the parity probe (VERIFIED/PROBING once synced) and the
     * decider/watchdog stand-downs (FAILED — terminal per process).
     */
    val verificationStatus: StateFlow<L1VerificationStatus> = _verificationStatus.asStateFlow()

    /** FAILED is terminal per process; everything else may transition freely. */
    private fun updateVerificationStatus(next: L1VerificationStatus) {
        if (_verificationStatus.value == L1VerificationStatus.FAILED) return
        _verificationStatus.value = next
    }

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
                // Seed the committed-cursor tracker from the durable
                // watermark so the drain predicate has evidence before the
                // session's first SyncHeightAdvanced event; a failed read
                // leaves 0 (= unknown, never treated as lagging).
                _engineWalletSyncedHeight.value = runCatching {
                    source.sdkWalletSyncedHeight(walletIdHex) ?: 0L
                }.getOrElse { t ->
                    log.warn("durable syncedHeight seed read failed; cursor starts unknown", t)
                    0L
                }
                lastProbeHeartbeatMs = nowMs()
                monitorJob = scope.launch { monitorProgress() }.logCompletion("progress monitor")
                parityJob = scope.launch { parityLoop(walletIdHex) }.logCompletion("parity probe loop")
                watchdogJob = scope.launch { watchdogLoop() }.logCompletion("probe watchdog")
                eventTapJob = scope.launch { tapWalletEvents() }.logCompletion("wallet-event tap")
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
            watchdogJob?.cancel()
            watchdogJob = null
            eventTapJob?.cancel()
            eventTapJob = null
            runCatching { source.stopSpv() }
                .onFailure { log.warn("failed to stop the shadow SPV client", it) }
            _progress.value = ShadowSyncProgress.IDLE
            _engineWalletSyncedHeight.value = 0L // re-seeded on the next start
            log.info("L1 shadow sync stopped")
        }
    }

    /** Whether the Rust SPV client is currently up (independent of the running latch). */
    suspend fun isShadowSpvRunning(): Boolean = source.isSpvRunning()

    /**
     * Ensure the shadow SPV client is running so a shield-from-wallet
     * broadcast has a live SPV to submit its L1 asset lock through.
     *
     * ## Why a shield depends on this (interim, SDK issue #4065)
     *
     * `ShieldedBalanceServiceImpl.shieldFromWallet` builds and broadcasts
     * the L1 asset lock via the SDK's own SPV peers (`SpvBroadcaster`).
     * In this half-migrated app the SDK's SPV runs ONLY as this service's
     * debug shadow sync — there is no separate SDK wallet engine yet. The
     * funding-gate evidence proves the shadow is SYNCED at probe time, but
     * the SPV can still be stopped by a lifecycle teardown ([stop]) or a
     * recovery window ([resetShadowState]/[recoverByRecreatingWallet])
     * between the last probe and the broadcast — the live
     * "Transaction broadcast failed: SPV client not started" failure. A
     * shield therefore calls this immediately before spending to guarantee
     * the SPV is up. Post-cutover the SDK's SPV is the real wallet engine
     * and always running, so this coupling disappears.
     *
     * Idempotent and inert while the flag is off (returns false, touches
     * nothing — a shield already requires the L1-shadow flag on). Shares
     * [mutex] with [stop]/[startIfEnabled]/[resetShadowState] so it can
     * never interleave a teardown mid-check; the residual window between
     * this returning true and the caller's broadcast is covered by the
     * broadcaster's NotBroadcast/retryable classification of a stopped-SPV
     * failure. Never throws (except cancellation).
     *
     * @return true when the SPV is running afterwards; false when the flag
     *   is off, no wallet is bound, or a (re)start failed.
     */
    suspend fun ensureSpvRunning(): Boolean {
        if (!isEnabled()) return false
        // Idempotent bring-up: starts the SPV + probe loops when the latch
        // is unset, no-ops (true) when already latched. Covers the common
        // "a lifecycle teardown cleared everything before the shield" case.
        if (!startIfEnabled()) return false
        // The latch being set does not itself prove the Rust client is up:
        // a reset stops+restarts it under [mutex], and an external teardown
        // can have stopped it while the latch survived. Verify and restart
        // in place (NOT a reset — that would wipe the scan state) under the
        // same mutex stop()/reset take, so this cannot race a teardown.
        return try {
            mutex.withLock {
                if (runningWalletIdHex.value == null) return@withLock false
                if (source.isSpvRunning()) return@withLock true
                val dataDir = File(spvDataDirPath()).apply { mkdirs() }
                source.startSpv(dataDir.absolutePath)
                log.info("ensureSpvRunning: restarted the shadow SPV client for a shield-from-wallet broadcast")
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("ensureSpvRunning: failed to restart the shadow SPV client for a shield", t)
            false
        }
    }

    /**
     * Mirror the manager's 1 Hz progress feed into [progress], logging the
     * one-line summary at most every [progressLogIntervalMs] — except the
     * one-time transitions into SYNCED/ERROR, which always log (they are
     * the events the harness exists to observe).
     *
     * Never dies silently: a failing upstream flow is logged and
     * re-collected after [LOOP_RETRY_DELAY_MS] (the probe/scan loops were
     * observed going dark for an hour in the field — see [watchdogLoop]).
     */
    private suspend fun monitorProgress() {
        var lastLogMs = 0L
        var lastPhase = ShadowSyncPhase.IDLE
        while (currentCoroutineContext().isActive) {
            try {
                source.spvProgress().collect { data ->
                    // Carry the committed wallet cursor (seeded durable
                    // watermark, live SyncHeightAdvanced events) so the
                    // caught-up predicate can see block/tx-pipeline churn
                    // the typed SPV progress hides (fed at ≤1s staleness —
                    // this feed ticks at 1 Hz while SPV runs).
                    val mapped = toShadowSyncProgress(data)
                        .copy(walletSyncedHeight = _engineWalletSyncedHeight.value)
                    _progress.value = mapped
                    // Verification verdict from the chain state: still
                    // scanning until SYNCED, then "probing" until a parity
                    // probe upgrades to VERIFIED (which progress ticks must
                    // not downgrade — only a probe result may).
                    if (!mapped.synced) {
                        updateVerificationStatus(L1VerificationStatus.SCANNING)
                    } else if (_verificationStatus.value != L1VerificationStatus.VERIFIED) {
                        // PROBING means "synced, waiting on a parity verdict".
                        // When no meaningful verdict is coming — the cutover has
                        // committed, so dashj is either held (frozen, guaranteed
                        // mismatch) or a still-catching-up diagnostic backup —
                        // the chain state IS the whole verdict. Leaving it at
                        // PROBING is what pinned the shielded screen's "Almost
                        // done" toast forever.
                        updateVerificationStatus(
                            if (parityPolicy.driveVerification) {
                                L1VerificationStatus.PROBING
                            } else {
                                L1VerificationStatus.VERIFIED
                            }
                        )
                    }
                    if (mapped.phase == ShadowSyncPhase.SYNCED && lastPhase != ShadowSyncPhase.SYNCED) {
                        // One ping per edge into SYNCED: the parity loop
                        // probes immediately instead of leaving the funding
                        // gate closed until its next interval tick.
                        syncedEdgeSignal.trySend(Unit)
                    }
                    val now = nowMs()
                    val terminalTransition = mapped.phase != lastPhase &&
                        (mapped.phase == ShadowSyncPhase.SYNCED || mapped.phase == ShadowSyncPhase.ERROR)
                    if (terminalTransition || now - lastLogMs >= progressLogIntervalMs) {
                        log.info(shadowProgressLine(mapped))
                        lastLogMs = now
                    }
                    lastPhase = mapped.phase
                }
                return // upstream flow completed normally
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1Shadow progress monitor failed; re-collecting in ${LOOP_RETRY_DELAY_MS}ms", t)
                delay(LOOP_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Mirror the manager's wallet-event feed into [txEvents], dropping
     * every event kind [parseL1TxEvent] does not recognize. Same
     * never-dies-silently re-collect discipline as [monitorProgress]:
     * losing this feed only degrades receives back to the Room-snapshot
     * cadence, but it should not do so silently.
     */
    private suspend fun tapWalletEvents() {
        while (currentCoroutineContext().isActive) {
            try {
                source.walletEventStrings().collect { debug ->
                    // Chainlock feed first: it rides the SAME event stream but
                    // on OTHER variants (ChainLockProcessed / BlockProcessed),
                    // which parseL1TxEvent drops. Monotonic — a replayed or
                    // out-of-order event must never lower the reported height
                    // (consumers read it as "proven chainlocked up to here").
                    parseL1ChainLockHeight(debug)?.let { height ->
                        val previous = _chainLockHeight.value
                        if (height > previous) {
                            _chainLockHeight.value = height
                            log.info("L1 engine chainlock height {} -> {}", previous, height)
                        }
                    }
                    // Committed-cursor feed for the drain predicate.
                    // LATEST-WINS (see [_engineWalletSyncedHeight]): after an
                    // armed rescan the first event is legitimately LOWER than
                    // the tracked value and must replace it.
                    parseL1SyncHeightAdvanced(debug)?.let { height ->
                        _engineWalletSyncedHeight.value = height
                    }
                    val event = parseL1TxEvent(debug) ?: return@collect
                    log.info("L1 engine tx event: {}", event)
                    if (!_txEvents.tryEmit(event)) {
                        log.warn("L1 tx-event buffer full; dropped {} (Room snapshot will reconcile)", event)
                    }
                }
                return // upstream flow completed normally
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1 wallet-event tap failed; re-collecting in ${LOOP_RETRY_DELAY_MS}ms", t)
                delay(LOOP_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun parityLoop(walletIdHex: String) {
        // A ping left over from a previous run (or a pre-start edge) must
        // not double the startup probe below.
        while (syncedEdgeSignal.tryReceive().isSuccess) { /* drain */ }
        var loggedSuspension = false
        while (currentCoroutineContext().isActive) {
            lastProbeHeartbeatMs = nowMs()
            try {
                // Re-resolved every tick so the Tools DASHJ_SYNC_DIAGNOSTIC
                // toggle (and the cutover commit itself) take effect on a live
                // loop without a restart.
                val policy = resolveParityPolicy()
                parityPolicy = policy
                if (policy.probe) {
                    loggedSuspension = false
                    probeParity(walletIdHex)
                } else if (!loggedSuspension) {
                    loggedSuspension = true
                    log.info(
                        "L1Parity probing suspended: the cutover is committed and the dashj " +
                            "engine is held, so its balance is frozen at the cutover snapshot — " +
                            "any comparison against it is a guaranteed MISMATCH that measures " +
                            "nothing. Turn on the Tools 'dashj sync (diagnostic)' toggle to " +
                            "un-hold dashj and resume probing."
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1Parity probe failed; will retry on the next tick", t)
            }
            // The interval tick OR one SYNCED-edge ping, whichever comes
            // first — the schedule is otherwise unchanged. ADAPTIVE cadence:
            // fast ([parityIntervalMs]) only while the streak/commit still
            // needs feeding, relaxed ([paritySlowIntervalMs]) after — a
            // multi-day large-wallet sync must not pay the probe every 10s
            // forever (see [slowParityCadenceActive]).
            val interval = if (slowParityCadenceActive()) paritySlowIntervalMs else parityIntervalMs
            withTimeoutOrNull(interval) { syncedEdgeSignal.receive() }
        }
    }

    /**
     * Once-per-process latch: the fast parity cadence has served its purpose.
     * See [slowParityCadenceActive].
     */
    @Volatile
    private var slowParityCadenceLatched = false

    /**
     * Whether the probe may relax to [paritySlowIntervalMs]. The 10s fast
     * cadence exists ONLY to fill the cutover auto-commit streak quickly
     * ([CutoverPolicy.MIN_PARITY_STREAK] consecutive caught-up MATCH probes ≈
     * 20-30s at 10s cadence — see the [PARITY_INTERVAL_MS] KDoc). So the
     * cadence relaxes as soon as EITHER latch condition holds, once per
     * process:
     * - the cutover is already COMMITTED (the streak's consumer fired — every
     *   probe after that is pure monitoring), or
     * - the first sustained caught-up MATCH streak completed (the evidence
     *   window is full; [CutoverPolicy.MAX_PARITY_AGE_MILLIS] = 5 min keeps
     *   60s-cadence evidence comfortably fresh for the auto-commit observer).
     *
     * Deliberately NOT reverted on a later mismatch (spec'd behavior — one
     * latch, no flapping): the only cost is that a NEW persistent mismatch
     * takes ~3 min of consecutive slow probes to reach the rebuild decider
     * instead of ~30s, which only delays a self-heal, never skips it. The
     * SYNCED-edge ping still forces an immediate probe on every re-sync edge.
     * Fail-open to the FAST cadence on any read error (probing too often is
     * safe; probing too rarely could starve the streak).
     */
    private suspend fun slowParityCadenceActive(): Boolean {
        if (slowParityCadenceLatched) return true
        val committed = try {
            !dashjEngineMayStart(
                CutoverState.fromStored(dashPayConfig.get(DashPayConfig.CUTOVER_STATE))
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            false
        }
        val streakDone = !committed && parityStreakRecorder.snapshot()
            .takeLastWhile { it.caughtUp && it.match }
            .size >= CutoverPolicy.MIN_PARITY_STREAK
        if (committed || streakDone) {
            slowParityCadenceLatched = true
            log.info(
                "L1Parity cadence relaxing {}ms -> {}ms ({})",
                parityIntervalMs, paritySlowIntervalMs,
                if (committed) "cutover already committed" else "first sustained MATCH streak complete"
            )
            return true
        }
        return false
    }

    /**
     * The probe-loop watchdog. The parity loop died silently in the field
     * (no `L1Parity` line for an hour; cause unknown — an uncaught error
     * or a cancelled scope). Every [watchdogIntervalMs] this loop checks
     * the heartbeat [lastProbeHeartbeatMs] the parity loop stamps at each
     * iteration; if it goes stale for [probeStallThresholdMs] while the
     * service believes it is running, it logs ERROR and relaunches the
     * probe loop — ONCE per process ([ProbeWatchdogDecider]). A stall is
     * possible without the loop being dead (a probe call hung inside the
     * SDK); cancelling the old job before relaunching covers both.
     */
    private suspend fun watchdogLoop() {
        while (currentCoroutineContext().isActive) {
            delay(watchdogIntervalMs)
            try {
                checkProbeHeartbeat()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1Shadow watchdog check failed; will retry on the next tick", t)
            }
        }
    }

    private suspend fun checkProbeHeartbeat() {
        if (runningWalletIdHex.value == null) return
        val decision = watchdogDecider.onCheck(nowMs(), lastProbeHeartbeatMs)
        if (decision == ProbeWatchdogDecider.Decision.NONE) return
        mutex.withLock {
            val walletIdHex = runningWalletIdHex.value ?: return
            when (decision) {
                ProbeWatchdogDecider.Decision.RESTART -> {
                    log.error(
                        "L1Shadow watchdog: no parity probe for {}ms while the shadow is " +
                            "supposedly running — the probe loop died or hung silently; " +
                            "restarting it (once per process)",
                        nowMs() - lastProbeHeartbeatMs
                    )
                    parityJob?.cancel()
                    lastProbeHeartbeatMs = nowMs()
                    parityJob = scope.launch { parityLoop(walletIdHex) }
                        .logCompletion("parity probe loop (watchdog restart)")
                }
                ProbeWatchdogDecider.Decision.EXHAUSTED -> {
                    // No probe loop means parity can never be confirmed:
                    // surface the stand-down to the verification consumers.
                    _verificationStatus.value = L1VerificationStatus.FAILED
                    log.error(
                        "L1Shadow watchdog: the probe loop stalled again after its one " +
                            "watchdog restart — standing down (no further automatic " +
                            "restarts this process)"
                    )
                }
                ProbeWatchdogDecider.Decision.NONE -> Unit
            }
        }
    }

    /**
     * Attach a completion logger so no shadow loop ever dies silently:
     * normal completion and cancellation log at INFO, an escaped failure
     * logs the cause at WARN (loop bodies catch-and-continue, so a WARN
     * here means the loop machinery itself broke).
     */
    private fun Job.logCompletion(name: String): Job = apply {
        invokeOnCompletion { cause ->
            when (cause) {
                null -> log.info("L1Shadow {} completed", name)
                is CancellationException ->
                    log.info("L1Shadow {} cancelled ({})", name, cause.message ?: "no reason given")
                else -> log.warn("L1Shadow $name DIED with an escaped failure", cause)
            }
        }
    }

    /**
     * One parity measurement (see class KDoc for semantics). Internal so
     * tests drive single probes without the 10s ([PARITY_INTERVAL_MS])
     * loop. Returns null when
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
        // The cutover parity streak counts an observation only when the SDK's
        // filter scan has caught up to the tip — NOT the never-latching SYNCED
        // flag (see ParityObservation.caughtUp / ShadowSyncProgress.scanCaughtUpToTip).
        parityStreakRecorder.record(
            report,
            caughtUp = _progress.value.scanCaughtUpToTip,
            atElapsedMillis = android.os.SystemClock.elapsedRealtime()
        )
        if (BuildConfig.DEBUG) {
            // Verbose parity ticker — debug/QA builds only (owner's decision:
            // the shadow FEATURE ships in release, its chatty logging doesn't).
            // Everything above (latestParity, streak, verification status)
            // still runs in ALL builds — release consumers depend on it.
            log.info(parityLogLine(report))
        }
        // Post-cutover the SDK is the wallet of record and dashj is at best a
        // catching-up diagnostic backup, so a disagreement is evidence about
        // DASHJ, not about the SDK — it must not move the user-facing verdict
        // (see parityProbePolicy). Pre-cutover this is the original behaviour.
        if (report.sdkSynced && parityPolicy.driveVerification) {
            // The same evidence the funding gate requires: BOTH balance
            // variants matching (see evaluateWalletFundingGate). A synced
            // mismatch stays PROBING unless the decider below makes it
            // terminal (FAILED).
            updateVerificationStatus(
                if (report.balancesMatch && report.confirmedBalancesMatch) {
                    L1VerificationStatus.VERIFIED
                } else {
                    L1VerificationStatus.PROBING
                }
            )
        }

        if (BuildConfig.DEBUG && report.sdkSynced && !report.balancesMatch) {
            // The outpoint-level dump exists purely as log evidence for the
            // SDK bug report — debug/QA builds only, like the ticker above.
            maybeLogOutpointDiff(walletIdHex, report)
        }
        if (!parityPolicy.allowSelfHeal) {
            // Post-cutover the SDK wallet IS the ledger of record. Rebuilding
            // it (or standing down to FAILED) because a diagnostic dashj
            // engine that is still catching up disagrees would destroy the
            // authoritative side on the word of the unauthoritative one.
            // The report/streak/logging above still publish — the diagnostic
            // stays fully useful, it just cannot act.
            return report
        }
        // The inflated auto-reset rule only means anything once dashj's own
        // initial sync is genuinely complete (see isDashjChainCaughtUp — the
        // live 02:38 incident: a dashj chain replay made a CORRECT SDK view
        // read as inflated and the hard reset destroyed it).
        val progressSnapshot = _progress.value
        val dashjChainHead = source.dashjChainHeadHeight()
        val dashjCaughtUp = isDashjChainCaughtUp(dashjChainHead, progressSnapshot.bestKnownTipHeight)
        if (report.sdkSynced && !report.balancesMatch &&
            report.sdkDuffs > report.dashjDuffs && !dashjCaughtUp
        ) {
            log.info(
                "L1Parity inflated-mismatch self-heal suppressed: dashj chain head {} vs SDK " +
                    "tip {} (must be within {} blocks) — dashj is still mid-initial-sync/replay, " +
                    "so sdk > dashj is expected until it catches up; not counted toward the " +
                    "rebuild streak",
                dashjChainHead, progressSnapshot.bestKnownTipHeight, DASHJ_TIP_TOLERANCE_BLOCKS
            )
        }
        val decision = resetDecider.onProbe(
            report,
            scanLooksComplete = progressSnapshot.scanLooksComplete,
            recentSelfSpendMarker =
                lastSelfSpendMs != 0L && nowMs() - lastSelfSpendMs <= SELF_SPEND_GRACE_MS,
            dashjChainCaughtUp = dashjCaughtUp
        )
        when (decision) {
            ShadowResetDecider.Decision.REBUILD_WALLET -> {
                // The SPV-only hard reset this used to run left the +0.01
                // inflation intact on device (it lives in the SDK WALLET
                // ledger, not the SPV scan data). Self-heal with a ONE-TIME
                // full SDK-wallet REBUILD instead — unbind + clear ALL
                // SDK-side persistence, then re-bind from the RETAINED seed
                // and re-scan. Fire-and-forget: recovery stops this probe
                // loop. SDK-side ONLY — dashj/seed/keys are untouched.
                val direction = if (report.sdkDuffs > report.dashjDuffs) "INFLATED" else "DEFICIT"
                log.warn(
                    "L1Parity {} MISMATCH persisted for {} consecutive synced probes " +
                        "(sdk={} vs dashj={} duffs, delta={}) — the SDK L1 WALLET LEDGER " +
                        "disagrees with dashj (an SPV-only reset already proved it does NOT " +
                        "live in the scan data); self-healing with ONE full SDK-wallet rebuild " +
                        "(unbind + clear SDK persistence, re-bind from the retained seed, " +
                        "re-scan). SDK-side only — dashj, seed and keys are untouched",
                    direction, ShadowResetDecider.RESET_CONSECUTIVE_PROBES,
                    report.sdkDuffs, report.dashjDuffs, report.sdkDuffs - report.dashjDuffs
                )
                recreateWalletInBackground()
            }
            ShadowResetDecider.Decision.STAND_DOWN -> {
                _verificationStatus.value = L1VerificationStatus.FAILED
                log.error(
                    "L1Parity MISMATCH SURVIVED the full SDK-wallet rebuild (sdk={} vs " +
                        "dashj={} duffs, delta={}) — this is a DETERMINISTIC SDK ledger bug that " +
                        "no APK-side self-heal can fix; it must be fixed upstream (rust dash-spv " +
                        "/ key-wallet). Standing down: NO further automatic rebuilds this process, " +
                        "dashj stays primary and the parity gate keeps the cutover blocked " +
                        "(the correct safety). Verification marked FAILED",
                    report.sdkDuffs, report.dashjDuffs, report.sdkDuffs - report.dashjDuffs
                )
            }
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
     * Tear down and rebuild the shadow SPV's PERSISTED state, then restart
     * SPV for a fresh full scan (the scan start comes from the wallet's
     * stored birth height Rust-side — no height override). Sequencing:
     *
     * 1. stop the Rust client;
     * 2. destroy the SPV storage —
     *    - `hard = true` (the default and the only reliable mode): delete
     *      the shadow dataDir (`filesDir/l1_shadow_spv/<network>`)
     *      RECURSIVELY at the filesystem level, logging the directory and
     *      deleted file count. This is app-owned storage nothing else
     *      touches, and the client was just stopped (Rust unlocks the dir
     *      on stop), so the delete is safe;
     *    - `hard = false` (legacy): the SDK's `clearSpvStorage`, which is
     *      a GUARANTEED no-op at this point in the sequence — the Rust
     *      runtime drops the client (and with it the only reference to
     *      the dataDir) on stop, so the call errors without touching a
     *      file and the surviving header store + scan watermark suppress
     *      the rescan (the live sdk=0 deficit incident; full trace on
     *      [L1ShadowSource.clearSpvStorage]). Kept only so a future fixed
     *      SDK can be exercised;
     * 3. delete the SDK wallet's L1 TXO/transaction rows (see
     *    [L1ShadowSource.clearSdkL1Rows] for why raw Room deletes are the
     *    only SDK surface for this);
     * 4. stamp the persisted reset marker
     *    ([DashPayConfig.L1_SHADOW_LAST_RESET]) so a post-reset sdk=0
     *    deficit is recognizable as reset-aftermath, even across a
     *    process death ([ShadowResetDecider]);
     * 5. restart SPV.
     *
     * NOTE: this is NO LONGER called automatically by the parity probe —
     * an inflated mismatch now only detects and blocks (see the
     * [ShadowResetDecider] KDoc), never wipes. It remains a MANUAL debug
     * tool, reachable via the debug broadcast ([L1ShadowDebugReset]) and
     * callable directly for a debug screen. NOTE: the live Rust wallet's
     * in-memory TXO view is NOT rebuilt by this call (the SDK offers no
     * in-process reload short of `removeWallet`'s destructive cascade —
     * which is exactly what [recoverByRecreatingWallet] runs); the
     * persisted rows are clean immediately and the in-memory view is
     * rebuilt from them on the next app start.
     *
     * Returns whether the reset ran (false when the shadow isn't running
     * or a step failed; failures are logged, never thrown).
     */
    suspend fun resetShadowState(hard: Boolean = true): Boolean {
        return try {
            mutex.withLock {
                val walletIdHex = runningWalletIdHex.value
                if (walletIdHex == null) {
                    log.info("L1 shadow reset skipped: shadow sync not running")
                    return false
                }
                log.warn(
                    "L1 shadow {} reset: stopping SPV, {}, clearing L1 rows, rescanning",
                    if (hard) "HARD" else "soft",
                    if (hard) "deleting the SPV dataDir" else "clearing SPV storage via the SDK"
                )
                runCatching { source.stopSpv() }
                    .onFailure { log.warn("shadow reset: SPV stop failed; continuing", it) }
                _progress.value = ShadowSyncProgress.IDLE
                // Pre-reset parity evidence must never count toward a cutover.
                parityStreakRecorder.clear()
                if (hard) {
                    deleteSpvDataDir()
                }
                source.clearSdkL1Rows(walletIdHex)
                if (!hard) {
                    source.clearSpvStorage()
                }
                runCatching { dashPayConfig.set(DashPayConfig.L1_SHADOW_LAST_RESET, nowMs()) }
                    .onFailure { log.warn("shadow reset: failed to persist the reset marker", it) }
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

    /** Fire-and-forget hard [resetShadowState], for the debug broadcast trigger. */
    fun hardResetInBackground(): Job = scope.launch { resetShadowState(hard = true) }

    /**
     * Fire-and-forget [recoverByRecreatingWallet] — the entry point for
     * the probe decision (which must not await it: the recovery cancels
     * the very parity loop the decision fires from) and for the debug
     * broadcast trigger (`--ez recreate true`, [L1ShadowDebugReset]).
     */
    fun recreateWalletInBackground(): Job = scope.launch { recoverByRecreatingWallet() }

    /**
     * The DEFINITIVE shadow-state recovery: full SDK-wallet RE-CREATION.
     *
     * ## Why the hard reset is not enough — where the watermark really lives
     *
     * Live evidence (third failure mode): after a hard reset (shadow SPV
     * dataDir recursively deleted AND the wallet's Room TXO/tx rows
     * deleted) a full header/filter re-download completed but the wallet
     * re-discovered NOTHING — sdk=0 duffs / 0 TXOs / 0 txs vs dashj
     * 154427919 / 128 / 436. Traced through the SDK sources: the
     * per-wallet filter-scan watermark is `WalletEntity.syncedHeight`
     * (`kotlin-sdk/.../persistence/entities/WalletEntity.kt:50`), written
     * back on every changeset header
     * (`PlatformWalletPersistenceHandler.onWalletChangesetHeader`, :424)
     * and rehydrated on `loadPersistedWallets` into the Rust wallet's
     * `WalletMetadata.synced_height`
     * (`onLoadWalletList` → `buildUtxoRestoreData`, handler :1312/:1405 →
     * `rs-platform-wallet-ffi/src/persistence.rs:2914`, overriding the
     * `from_wallet` default; struct field `key-wallet/src/wallet/
     * metadata.rs:21`). The SPV filter scanner then starts at
     * `synced_height + 1` (`dash-spv/src/sync/filters/manager.rs:187`)
     * and drops any wallet with `synced_height >= batch_end` before its
     * scripts are ever matched (`manager.rs:739-752`) — so the re-download
     * re-fetches every filter and MATCHES NONE of them. The watermark
     * lives in the WALLET's persisted state (the Room `wallets` row +
     * the rehydrated Rust wallet object), NOT in the SPV dataDir (which
     * holds only chain data — headers/filters/masternodes/peers and one
     * chain-global `last_target_height`, `dash-spv/src/storage/
     * metadata.rs:11`). No combination of dataDir + TXO/tx row deletion
     * can ever clear it; destroying and re-creating the wallet is the
     * only SDK surface that does.
     *
     * ## Sequence
     *
     * 1. [stop] the shadow (probe loops cancelled, Rust SPV client
     *    stopped) and stop the shielded runtime — nothing may touch the
     *    wallet's rows mid-removal;
     * 2. [DashSdkService.removeAppWallet]: the SDK's full removal cascade
     *    (Room wallet/identity/TXO/address/shielded rows — including the
     *    `wallets` row carrying `syncedHeight` — plus the Keystore-backed
     *    identity keys and mnemonic, and the native wallet handle; full
     *    trace on that KDoc);
     * 3. delete the shadow SPV dataDir (same filesystem-level wipe as the
     *    hard reset — the chain data is per-wallet-cheap and this
     *    guarantees the fresh wallet's first scan starts from a clean
     *    filter store);
     * 4. stamp the persisted reset marker
     *    ([DashPayConfig.L1_SHADOW_LAST_RESET]) so a still-empty deficit
     *    after the re-creation remains recognizable as recovery aftermath
     *    across a process death;
     * 5. clear the binder latch ([SdkWalletBinder.resetForWalletRecreation])
     *    and fire the full bind pass with the same non-interactive unlock
     *    recipe as startup ([NonInteractiveWalletUnlock]): `createWallet`
     *    re-derives the SAME deterministic wallet id from the same seed —
     *    this time with the checkpoint-mapped birth height — then identity
     *    discovery + key heal re-attach the app identity;
     * 6. once the bind pass finishes, restart the shadow SPV
     *    ([startIfEnabled]) — the fresh wallet's `synced_height` starts at
     *    `birth_height - 1`, so the filter scan actually re-matches and
     *    repopulates every TXO/tx row.
     *
     * ## Safety: SDK-side state ONLY — dashj is untouchable from here
     *
     * Every step operates exclusively on SDK-owned state: the SDK's Room
     * database, the SDK's Keystore-backed `WalletStorage`, the app-owned
     * shadow SPV dataDir (`filesDir/l1_shadow_spv/<network>`), and the
     * SDK shielded runtime. The dashj wallet — the L1 source of truth
     * holding user funds — its wallet file, block store and keys are not
     * reachable through ANY collaborator on this path
     * ([ShadowWalletRecreator] exposes no dashj surface; [L1ShadowSource]
     * only READS dashj balances). The user's seed is never destroyed: the
     * canonical copy lives encrypted in the dashj wallet; only the SDK's
     * `WalletStorage` COPY is deleted by the cascade, and the rebind
     * re-stores it from freshly-decrypted words. The wallet's SHIELDED
     * note state IS wiped by the cascade (all four `shielded_*` tables —
     * wallet-scoped, deleted explicitly by `deleteWalletData`), so any
     * shielded balance needs a full shielded re-sync afterwards —
     * acceptable (0 on the incident device), and the shielded runtime's
     * next `ensureShieldedReady` pass rebuilds from the network.
     *
     * Once-per-process automation is the DECIDER's job: it fires
     * [ShadowResetDecider.Decision.REBUILD_WALLET] on a persistent parity
     * mismatch (either direction) so a user who simply installs the updated
     * build self-heals automatically (see the [ShadowResetDecider] KDoc).
     * Also reachable as a MANUAL debug tool via the [L1ShadowDebugReset]
     * broadcast (`--ez recreate true`); this method itself is re-runnable.
     * Returns whether the destructive phase completed and the rebind was
     * launched (false when no wallet is bound, the recreator isn't wired,
     * or a step failed — failures are logged, never thrown).
     */
    suspend fun recoverByRecreatingWallet(): Boolean {
        val recreator = this.recreator ?: run {
            log.warn("L1 shadow wallet re-creation skipped: no recreator wired (test construction?)")
            return false
        }
        return try {
            recoveryMutex.withLock {
                val walletIdHex = runningWalletIdHex.value ?: source.boundWalletIdOrNull()
                if (walletIdHex == null) {
                    log.info("L1 shadow wallet re-creation skipped: no SDK wallet bound")
                    return false
                }
                log.warn(
                    "L1 shadow RECOVERY BY WALLET RE-CREATION for SDK wallet {}…: stopping " +
                        "shadow + shielded sync, removing the SDK wallet (full cascade — this " +
                        "destroys the persisted scan watermark row deletion cannot reach), " +
                        "deleting the SPV dataDir, re-binding from the app seed, re-scanning. " +
                        "SDK-side state only; dashj is untouched",
                    walletIdHex.take(8)
                )
                stop() // takes the main mutex itself; cancels loops + stops the Rust client
                runCatching { recreator.stopShieldedSync() }
                    .onFailure { log.warn("wallet re-creation: shielded stop failed; continuing", it) }
                mutex.withLock {
                    if (runningWalletIdHex.value != null) {
                        log.warn("wallet re-creation aborted: the shadow was restarted concurrently")
                        return false
                    }
                    recreator.removeSdkWallet(walletIdHex)
                    deleteSpvDataDir()
                    runCatching { dashPayConfig.set(DashPayConfig.L1_SHADOW_LAST_RESET, nowMs()) }
                        .onFailure { log.warn("wallet re-creation: failed to persist the reset marker", it) }
                    recreator.resetBinderLatch()
                }
                val bindJob = recreator.rebindInBackground()
                scope.launch {
                    bindJob.join()
                    val started = startIfEnabled()
                    log.info(
                        "post-re-creation shadow restart: started={} (false = bind pass did not " +
                            "complete a wallet bind yet; the next binding trigger retries and the " +
                            "shadow starts on the next start trigger)",
                        started
                    )
                }.logCompletion("post-re-creation restart")
                log.info(
                    "L1 shadow wallet re-creation: destructive phase complete for wallet {}…; " +
                        "rebind launched in the background",
                    walletIdHex.take(8)
                )
                true
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error(
                "L1 shadow wallet re-creation FAILED — the SDK wallet may be partially removed; " +
                    "the next binding trigger re-creates it (createWallet is deterministic on the " +
                    "seed), and dashj state is unaffected either way",
                t
            )
            false
        }
    }

    /**
     * Wallet-wipe ("Reset this wallet") cleanup: destroy ALL SDK-side
     * wallet state so the NEXT wallet the user sets up binds FRESH and
     * cannot inherit this wallet's SDK bound-id or discovered identity.
     * Same destructive collaborators as [recoverByRecreatingWallet] —
     * stop shadow + shielded, [ShadowWalletRecreator.removeSdkWallet]
     * (full Room + Keystore cascade), delete the SPV dataDir,
     * [ShadowWalletRecreator.resetBinderLatch] — but WITHOUT the rebind /
     * shadow restart: after a wipe there is no seed to bind until the user
     * creates or restores the next wallet, whose setup runs its own first
     * bind.
     *
     * This closes the reset-time resurrection race for the SDK path — the
     * SDK twin of the [PlatformSyncService] guard in
     * [de.schildbach.wallet.WalletApplicationExt] `clearDatabasesInner`. A
     * stale binder latch (`completed == true` with the previous
     * `boundWalletIdHex`) or a lingering SDK wallet row let the PREVIOUS
     * wallet's discovered identity keep driving the DashPay UI on the next
     * (identity-less) wallet — observed live as the "Join DashPay" entry
     * points staying hidden after a reset because the old DONE creation
     * state never cleared (the app kept reading the old SDK wallet). The
     * intermittence matches a race: whether an in-flight bind/discovery
     * pass re-attaches before the clears win depends on process lifecycle.
     *
     * SDK-side state ONLY — dashj is untouched (see
     * [recoverByRecreatingWallet]'s safety contract: no collaborator here
     * exposes a dashj surface, and the seed's canonical copy lives in the
     * dashj wallet, which the wipe removes separately). Never throws;
     * every step is failure-contained so one failure cannot abort the
     * rest (a partial clear is exactly the resurrection bug).
     */
    suspend fun clearForWalletWipe() {
        val recreator = this.recreator ?: run {
            log.info("wallet-wipe SDK cleanup skipped: no recreator wired (test construction?)")
            return
        }
        try {
            recoveryMutex.withLock {
                val walletIdHex = runningWalletIdHex.value ?: source.boundWalletIdOrNull()
                log.warn(
                    "wallet-wipe SDK cleanup for wallet {}: stopping shadow + shielded sync, " +
                        "removing the SDK wallet (full cascade), deleting the SPV dataDir, clearing " +
                        "the binder latch — NO rebind (the next wallet setup binds fresh). " +
                        "SDK-side state only; dashj is untouched",
                    walletIdHex?.take(8) ?: "none"
                )
                stop() // takes the main mutex itself; cancels loops + stops the Rust client
                // Pre-wipe parity evidence must never count toward the NEXT
                // (restored/new) wallet's cutover — the restored wallet re-earns
                // its own streak from scratch.
                parityStreakRecorder.clear()
                runCatching { recreator.stopShieldedSync() }
                    .onFailure { log.warn("wipe cleanup: shielded stop failed; continuing", it) }
                mutex.withLock {
                    if (walletIdHex != null) {
                        runCatching { recreator.removeSdkWallet(walletIdHex) }
                            .onFailure { log.warn("wipe cleanup: removeSdkWallet failed; continuing", it) }
                    }
                    runCatching { deleteSpvDataDir() }
                        .onFailure { log.warn("wipe cleanup: dataDir delete failed; continuing", it) }
                    runCatching { recreator.resetBinderLatch() }
                        .onFailure { log.warn("wipe cleanup: binder latch reset failed; continuing", it) }
                }
                log.info(
                    "wallet-wipe SDK cleanup complete for wallet {}: the next wallet binds fresh",
                    walletIdHex?.take(8) ?: "none"
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error(
                "wallet-wipe SDK cleanup FAILED — the next wallet's first bind still re-derives " +
                    "from the new seed, so a fresh (identity-less) wallet recovers; a stale bound " +
                    "identity could linger until then",
                t
            )
        }
    }

    /**
     * The hard-reset destroy step: recursively delete the shadow SPV
     * dataDir at the filesystem level — the only reset that provably
     * removes the Rust header store and scan watermark (the SDK's
     * `clearSpvStorage` cannot once the client is stopped; see
     * [L1ShadowSource.clearSpvStorage]). Logs the directory and the file
     * count it held.
     */
    private fun deleteSpvDataDir() {
        val dir = File(spvDataDirPath())
        if (!dir.exists()) {
            log.info("L1 shadow hard reset: dataDir {} already absent", dir.absolutePath)
            return
        }
        val fileCount = dir.walkBottomUp().count { it.isFile }
        val deleted = dir.deleteRecursively()
        if (deleted) {
            log.warn(
                "L1 shadow hard reset: deleted SPV dataDir {} ({} files) — header store and " +
                    "scan watermark gone; the restart re-scans from the wallet's birth height",
                dir.absolutePath, fileCount
            )
        } else {
            log.error(
                "L1 shadow hard reset: FAILED to fully delete SPV dataDir {} ({} files before " +
                    "the attempt) — leftover scan state may again suppress the rescan",
                dir.absolutePath, fileCount
            )
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

        /**
         * FAST parity probe cadence — in force only until the cutover commits
         * or the first sustained MATCH streak completes, after which the loop
         * relaxes to [PARITY_SLOW_INTERVAL_MS] ([slowParityCadenceActive]).
         *
         * Kept short so the cutover-readiness streak fills promptly: the probe
         * feeds [parityStreakRecorder], and the upgrade auto-commit needs
         * [CutoverPolicy.MIN_PARITY_STREAK] consecutive caught-up parity-MATCH
         * samples — at this 10s cadence that is ~20s of sustained parity, so a
         * genuinely caught-up SDK flips within ~20-30s (was minutes at 60s).
         * The probe is a cheap in-memory SDK-vs-dashj balance/txcount compare
         * (no network I/O — see [probeParity]), so a 10s cadence is fine on the
         * debug/QA builds this shadow harness runs on.
         *
         * SIDE EFFECT (checked): this cadence also clocks [ShadowResetDecider]'s
         * consecutive-probe counting, so a hard reset now fires after ~30s of a
         * sustained synced mismatch instead of ~3 min. That is safe — the
         * decider's benign-transient guards are cadence-INDEPENDENT (the 15-min
         * wall-clock [SELF_SPEND_GRACE_MS] and the dashj-caught-up block
         * tolerance), so faster probing only speeds recovery from a genuine,
         * guarded, sustained divergence; it never resets a healthy view sooner.
         */
        internal const val PARITY_INTERVAL_MS = 10_000L

        /**
         * Relaxed probe cadence once the fast cadence's job is done — the
         * cutover committed or the first sustained MATCH streak completed
         * ([slowParityCadenceActive]). Restores the original pre-streak 60s
         * monitoring cost for the long tail of a multi-day sync.
         */
        internal const val PARITY_SLOW_INTERVAL_MS = 60_000L

        /** Progress one-liner throttle (the SDK feed itself ticks at 1 Hz). */
        internal const val PROGRESS_LOG_INTERVAL_MS = 30_000L

        /** Watchdog heartbeat-check cadence. */
        internal const val WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * A probe-loop heartbeat older than this while the service claims
         * to be running means the loop died/hung: 5 minutes = thirty missed
         * 10s probes.
         */
        internal const val PROBE_STALL_THRESHOLD_MS = 5 * 60_000L

        /** Retry backoff for a failed progress-monitor collection. */
        internal const val LOOP_RETRY_DELAY_MS = 5_000L

        /**
         * [txEvents] buffer: absorbs a burst of per-record events (one per
         * affected account per tx) without suspending the tap collector.
         * A drop is logged and recovered by the Room snapshot pass.
         */
        internal const val TX_EVENT_BUFFER_CAPACITY = 64

        /**
         * How long a [noteSelfSpendBroadcast] marker suppresses the
         * INFLATED auto-reset. Sized to comfortably cover the legitimate
         * inflation window of a Phase 5b self-spend — mempool broadcast →
         * next mined block (~2.5 min target, occasionally much longer) →
         * the SDK's filter scan applying it — while staying far shorter
         * than any organic corruption timescale. At the 60s probe cadence
         * this masks at most ~15 mismatch probes.
         */
        internal const val SELF_SPEND_GRACE_MS = 15 * 60_000L
    }
}
