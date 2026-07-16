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
import kotlinx.coroutines.withTimeoutOrNull
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
 * - [FAILED]: the harness stood down — CORRUPT_AFTER_RESET, a
 *   DEFICIT_STAND_DOWN (including recreation exhausted), or a stalled
 *   probe loop past its one watchdog restart. Terminal for the process:
 *   verification will not recover without an app restart, so the UI tells
 *   the tester to flag it (Report an Issue).
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
    val filterTarget: Long
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
     * The shadow SPV's best knowledge of the NETWORK chain tip, 0 when the
     * snapshot carries no header heights: the reference height the
     * dashj-caught-up gate ([isDashjChainCaughtUp]) compares dashj's chain
     * head against. Uses the larger of current/target — while syncing the
     * target IS the network tip; once synced the two agree.
     */
    val bestKnownTipHeight: Long get() = maxOf(headerHeight, headerTarget)

    companion object {
        val IDLE = ShadowSyncProgress(ShadowSyncPhase.IDLE, 0.0, 0, 0, 0, 0)
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
    else -> syncPct(
        progress.headerHeight + progress.filterHeight,
        progress.headerTarget + progress.filterTarget
    ).coerceAtMost(99)
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
 * Decides when a persistent parity mismatch warrants an automatic
 * [L1ShadowSyncService.resetShadowState], from the probe stream plus two
 * context bits ([scanLooksComplete][ShadowSyncProgress.scanLooksComplete]
 * and the recent-reset DataStore marker).
 *
 * ## Why `sdk > dashj` always resets and `sdk < dashj` (almost) never does
 *
 * During the migration transition dashj is the source of truth for L1 —
 * it is the engine users spend from, and its view survives every release
 * to date. An SDK view showing MORE funds than dashj is therefore never
 * legitimate: the extra duffs can only be stale/duplicated shadow state
 * (e.g. unreconciled TXO rows after an SPV re-scan following an unclean
 * shutdown). A DEFICIT (`sdk < dashj`), by contrast, can be a real SDK
 * scan gap (e.g. a derivation path the SDK misses) — exactly the bug
 * class the harness must SURFACE, not erase — so it stands down instead
 * of resetting, with ONE exception:
 *
 * ## The reset-aftermath exception (the broken-reset recovery path)
 *
 * A live incident showed a reset itself can MANUFACTURE a deficit: the
 * pre-hard-reset flow deleted the Room TXO/tx rows but the SDK's
 * `clearSpvStorage` no-oped (it only clears a RUNNING client's storage —
 * see [L1ShadowSource.clearSpvStorage]), so on restart the SPV resumed
 * from the surviving header store + scan watermark, reported a "complete"
 * scan within seconds, and never repopulated the rows: `sdk=0` vs a real
 * dashj balance, stuck forever. That state has an unmistakable signature —
 * deficit AND `sdkTxCount == 0` AND headers+filters report complete AND a
 * reset ran recently (this or the previous process, per the persisted
 * marker). The first-generation recovery (one filesystem-level hard
 * reset) proved INSUFFICIENT live: even after the dataDir was provably
 * deleted and a full header/filter re-download completed, the wallet
 * re-discovered nothing — the per-wallet scan watermark survives in the
 * WALLET's own persisted state (see
 * [L1ShadowSyncService.recoverByRecreatingWallet] for the SDK-source
 * trace), which no combination of dataDir + row deletion can clear. The
 * escalation is therefore full SDK-wallet RE-CREATION
 * ([Decision.RECREATE_WALLET]): destroy the SDK wallet (removeWallet
 * cascade), re-bind it from the app seed, and re-scan into genuinely
 * fresh wallet state. The same signature WITHOUT a recent reset marker is
 * an organic total scan failure: stand down with an ERROR
 * ([Decision.DEFICIT_STAND_DOWN]), never reset.
 *
 * ## Decision table (evaluated per probe; `empty deficit` = synced
 * mismatch with sdk < dashj AND sdkTxCount == 0 AND scanLooksComplete)
 *
 * | probe state                     | consecutive | prior action this process | decision |
 * |---------------------------------|-------------|----------------------------|----------|
 * | not synced                      | streaks → 0 | —                          | NONE |
 * | synced, balances match          | streaks → 0 | —                          | NONE |
 * | sdk > dashj, dashj NOT caught up| streaks → 0 | —                          | NONE (dashj mid-sync/replay — see [isDashjChainCaughtUp]) |
 * | inflated (sdk > dashj)          | < threshold | —                          | NONE |
 * | inflated                        | ≥ threshold | no reset yet               | RESET (once) |
 * | inflated                        | ≥ threshold | reset already ran          | CORRUPT_AFTER_RESET (once), then NONE |
 * | deficit, sdkTx > 0 or scan open | streaks → 0 | —                          | NONE (MISMATCH log only) |
 * | empty deficit                   | < threshold | —                          | NONE |
 * | empty deficit + recent reset    | ≥ threshold | no re-creation yet         | RECREATE_WALLET (once) |
 * | empty deficit + recent reset    | ≥ threshold | re-creation ran            | DEFICIT_STAND_DOWN (once), then NONE |
 * | empty deficit, NO recent reset  | ≥ threshold | —                          | DEFICIT_STAND_DOWN (once), then NONE |
 *
 * Each acting decision fires at most once per process (per decider
 * instance): one inflated RESET, one wallet RE-CREATION, one
 * CORRUPT_AFTER_RESET report, one DEFICIT_STAND_DOWN report. After any
 * reset/re-creation the shadow chain re-scans, so `synced=false` probes
 * zero both streaks; only a FULL post-recovery resync that still shows
 * the mismatch for [requiredConsecutiveProbes] probes reaches the
 * stand-down rows.
 */
internal class ShadowResetDecider(
    private val requiredConsecutiveProbes: Int = RESET_CONSECUTIVE_PROBES
) {
    enum class Decision { NONE, RESET, CORRUPT_AFTER_RESET, RECREATE_WALLET, DEFICIT_STAND_DOWN }

    private var consecutiveInflated = 0
    private var consecutiveEmptyDeficit = 0
    private var resetIssued = false
    private var corruptReported = false
    private var recreateIssued = false
    private var deficitStandDownReported = false

    fun onProbe(
        report: ParityReport,
        scanLooksComplete: Boolean = false,
        recentResetMarker: Boolean = false,
        // A flag-gated SDK L1 send (Phase 5b, SdkL1SendService) was broadcast recently.
        // A self-spend legitimately INFLATES the SDK view for minutes: the SDK's
        // compact-filter SPV only applies the spend once it is MINED and filter-scanned,
        // while dashj's bloom filters see the mempool tx within seconds and drop its
        // ESTIMATED balance immediately — so sdk > dashj until the next block lands.
        // Resetting healthy shadow state on that evidence would be wrong, so inflated
        // streaks are zeroed while the marker is fresh. The DEFICIT direction needs no
        // guard: a plain deficit never resets, and the empty-deficit signature requires
        // sdkTxCount == 0, which is impossible right after a send from a wallet whose
        // parity-gated (non-zero, TXO-backed) balance just funded the spend.
        recentSelfSpendMarker: Boolean = false,
        // dashj's initial sync is GENUINELY complete (chain head at the network tip — see
        // isDashjChainCaughtUp). The INFLATED direction is meaningless while dashj is still
        // replaying/downloading: its balance is climbing toward the truth, so a correct SDK
        // view trivially reads sdk > dashj (the live 02:38 incident — a hard reset wiped a
        // CORRECT SDK state mid-replay). Not-caught-up probes zero the inflated streak, which
        // also enforces stability across the consecutive-probe window. Defaults to true so
        // callers with no dashj sync signal keep the pre-gate semantics (a genuinely inflated
        // view with both engines synced must still reset). The DEFICIT direction is NOT gated:
        // it never auto-resets organically, and its empty-deficit signature is about SDK-side
        // scan state, not dashj's.
        dashjChainCaughtUp: Boolean = true,
        // Debug builds always treat a persistent empty deficit as recoverable by wallet
        // re-creation: this harness only runs on debug builds, an empty-and-scanned SDK view
        // is never a legitimate steady state for a funded wallet, and remote testers have no
        // adb to trigger recovery manually when the reset marker has aged out.
        alwaysRecreateOnEmptyDeficit: Boolean = BuildConfig.DEBUG
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
        if (emptyDeficit) {
            consecutiveEmptyDeficit++
            if (consecutiveEmptyDeficit < requiredConsecutiveProbes) return Decision.NONE
            return when {
                (recentResetMarker || alwaysRecreateOnEmptyDeficit) && !recreateIssued -> {
                    recreateIssued = true
                    consecutiveEmptyDeficit = 0
                    Decision.RECREATE_WALLET
                }
                !deficitStandDownReported -> {
                    deficitStandDownReported = true
                    Decision.DEFICIT_STAND_DOWN
                }
                else -> Decision.NONE
            }
        }
        return Decision.NONE
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
    private val progressLogIntervalMs: Long = PROGRESS_LOG_INTERVAL_MS,
    private val watchdogIntervalMs: Long = WATCHDOG_INTERVAL_MS,
    private val probeStallThresholdMs: Long = PROBE_STALL_THRESHOLD_MS,
    /** Wallet-recreation collaborators; null (tests' default) disables [recoverByRecreatingWallet]. */
    private val recreator: ShadowWalletRecreator? = null,
    /**
     * Test override for the debug-build always-recreate-on-empty-deficit
     * behavior; null (production) resolves to [BuildConfig.DEBUG].
     */
    internal val alwaysRecreateOnEmptyDeficitOverride: Boolean? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkService: DashSdkService,
        walletData: WalletDataProvider,
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
                lastProbeHeartbeatMs = nowMs()
                monitorJob = scope.launch { monitorProgress() }.logCompletion("progress monitor")
                parityJob = scope.launch { parityLoop(walletIdHex) }.logCompletion("parity probe loop")
                watchdogJob = scope.launch { watchdogLoop() }.logCompletion("probe watchdog")
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
            runCatching { source.stopSpv() }
                .onFailure { log.warn("failed to stop the shadow SPV client", it) }
            _progress.value = ShadowSyncProgress.IDLE
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
                    val mapped = toShadowSyncProgress(data)
                    _progress.value = mapped
                    // Verification verdict from the chain state: still
                    // scanning until SYNCED, then "probing" until a parity
                    // probe upgrades to VERIFIED (which progress ticks must
                    // not downgrade — only a probe result may).
                    if (!mapped.synced) {
                        updateVerificationStatus(L1VerificationStatus.SCANNING)
                    } else if (_verificationStatus.value != L1VerificationStatus.VERIFIED) {
                        updateVerificationStatus(L1VerificationStatus.PROBING)
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

    private suspend fun parityLoop(walletIdHex: String) {
        // A ping left over from a previous run (or a pre-start edge) must
        // not double the startup probe below.
        while (syncedEdgeSignal.tryReceive().isSuccess) { /* drain */ }
        while (currentCoroutineContext().isActive) {
            lastProbeHeartbeatMs = nowMs()
            try {
                probeParity(walletIdHex)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("L1Parity probe failed; will retry on the next tick", t)
            }
            // The interval tick OR one SYNCED-edge ping, whichever comes
            // first — the schedule is otherwise unchanged.
            withTimeoutOrNull(parityIntervalMs) { syncedEdgeSignal.receive() }
        }
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
        parityStreakRecorder.record(report, android.os.SystemClock.elapsedRealtime())
        log.info(parityLogLine(report))
        if (report.sdkSynced) {
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

        if (report.sdkSynced && !report.balancesMatch) {
            maybeLogOutpointDiff(walletIdHex, report)
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
                "L1Parity inflated-mismatch auto-reset suppressed: dashj chain head {} vs SDK " +
                    "tip {} (must be within {} blocks) — dashj is still mid-initial-sync/replay, " +
                    "so sdk > dashj is expected until it catches up; not counted toward the " +
                    "reset streak",
                dashjChainHead, progressSnapshot.bestKnownTipHeight, DASHJ_TIP_TOLERANCE_BLOCKS
            )
        }
        val decision = resetDecider.onProbe(
            report,
            scanLooksComplete = progressSnapshot.scanLooksComplete,
            recentResetMarker = hasRecentResetMarker(),
            recentSelfSpendMarker =
                lastSelfSpendMs != 0L && nowMs() - lastSelfSpendMs <= SELF_SPEND_GRACE_MS,
            dashjChainCaughtUp = dashjCaughtUp,
            alwaysRecreateOnEmptyDeficit = alwaysRecreateOnEmptyDeficitOverride ?: BuildConfig.DEBUG
        )
        when (decision) {
            ShadowResetDecider.Decision.RESET -> {
                log.warn(
                    "L1Parity inflated MISMATCH persisted for {} consecutive synced probes " +
                        "(sdk={} > dashj={} duffs) — an inflated SDK view is never legitimate " +
                        "(dashj is the L1 source of truth); hard-resetting the shadow state",
                    ShadowResetDecider.RESET_CONSECUTIVE_PROBES, report.sdkDuffs, report.dashjDuffs
                )
                resetShadowState(hard = true)
            }
            ShadowResetDecider.Decision.CORRUPT_AFTER_RESET -> {
                _verificationStatus.value = L1VerificationStatus.FAILED
                log.error(
                    "shadow state corrupt after reset — SDK bug: the inflated L1 mismatch " +
                        "(sdk={} > dashj={} duffs) survived a full post-reset resync; standing " +
                        "down (no further automatic resets this process; the in-memory Rust " +
                        "wallet state is only fully rebuilt on the next app start)",
                    report.sdkDuffs, report.dashjDuffs
                )
            }
            ShadowResetDecider.Decision.RECREATE_WALLET -> {
                log.warn(
                    "L1Parity reset-aftermath deficit: sdk=0 txs / {} duffs vs dashj={} duffs " +
                        "with a complete header+filter scan and a recent shadow reset — the " +
                        "per-wallet scan watermark (WalletEntity.syncedHeight, rehydrated into " +
                        "the Rust wallet) survives every dataDir/row deletion and suppresses " +
                        "re-matching; escalating to ONE full SDK-wallet re-creation " +
                        "(fire-and-forget — it stops this probe loop)",
                    report.sdkDuffs, report.dashjDuffs
                )
                recreateWalletInBackground()
            }
            ShadowResetDecider.Decision.DEFICIT_STAND_DOWN -> {
                _verificationStatus.value = L1VerificationStatus.FAILED
                log.error(
                    "L1Parity DEFICIT stand-down: sdk={} < dashj={} duffs with sdkTx=0 and a " +
                        "complete scan but no recent-reset explanation (marker absent/stale, or " +
                        "the one wallet re-creation already ran) — a deficit is the bug class " +
                        "this harness must surface, not erase; no automatic reset",
                    report.sdkDuffs, report.dashjDuffs
                )
            }
            ShadowResetDecider.Decision.NONE -> Unit
        }
        return report
    }

    /**
     * Whether a shadow reset ran recently — this process or (via the
     * persisted [DashPayConfig.L1_SHADOW_LAST_RESET] marker) a previous
     * one, within [RESET_MARKER_MAX_AGE_MS]. This is the bit that
     * distinguishes a reset-aftermath deficit (recoverable — the reset
     * itself manufactured it) from an organic one (must stand down).
     * Read failures count as "no marker" so a broken DataStore can never
     * cause an unwarranted reset.
     */
    private suspend fun hasRecentResetMarker(): Boolean = try {
        val lastResetMs = dashPayConfig.get(DashPayConfig.L1_SHADOW_LAST_RESET)
        lastResetMs != null && nowMs() - lastResetMs <= RESET_MARKER_MAX_AGE_MS
    } catch (e: Exception) {
        log.warn("failed to read the L1 shadow reset marker; treating as absent", e)
        false
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
     * Called automatically by the probe (inflated mismatch — see
     * [ShadowResetDecider] for the decision table and the
     * once-per-process guarantees; the reset-aftermath deficit escalates
     * past this to [recoverByRecreatingWallet] instead, because the
     * per-wallet scan watermark survives everything this reset deletes),
     * by the debug broadcast ([L1ShadowDebugReset]), and callable
     * directly for a future debug screen. NOTE: the live Rust wallet's
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
     * Once-per-process automation is the DECIDER's job
     * ([ShadowResetDecider.Decision.RECREATE_WALLET]); this method itself
     * is re-runnable (debug trigger). Returns whether the destructive
     * phase completed and the rebind was launched (false when no wallet
     * is bound, the recreator isn't wired, or a step failed — failures
     * are logged, never thrown).
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

        /** Parity probe cadence while the shadow runs (including after SYNCED). */
        internal const val PARITY_INTERVAL_MS = 60_000L

        /** Progress one-liner throttle (the SDK feed itself ticks at 1 Hz). */
        internal const val PROGRESS_LOG_INTERVAL_MS = 30_000L

        /** Watchdog heartbeat-check cadence. */
        internal const val WATCHDOG_INTERVAL_MS = 60_000L

        /**
         * A probe-loop heartbeat older than this while the service claims
         * to be running means the loop died/hung: 5 minutes = five missed
         * 60s probes.
         */
        internal const val PROBE_STALL_THRESHOLD_MS = 5 * 60_000L

        /** Retry backoff for a failed progress-monitor collection. */
        internal const val LOOP_RETRY_DELAY_MS = 5_000L

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

        /**
         * How long the persisted reset marker counts as "recent" for the
         * reset-aftermath deficit recovery ([ShadowResetDecider]) — long
         * enough to span "this or the last process" across a typical
         * debug/QA day, short enough that an ancient marker cannot
         * legitimize resetting an organic deficit weeks later.
         */
        internal const val RESET_MARKER_MAX_AGE_MS = 24 * 60 * 60_000L
    }
}
