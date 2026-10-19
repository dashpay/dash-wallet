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

package de.schildbach.wallet.service

import de.schildbach.wallet.service.platform.sdk.ParityBreakdownFindings
import de.schildbach.wallet.service.platform.sdk.ParityReport
import de.schildbach.wallet.service.platform.sdk.TimedBreakdownFindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [pass] one at a time on a single coroutine in [scope], once per burst
 * of [request] calls.
 *
 * The dashj parity diagnostic is republished from several places at once:
 * dashj progress callbacks (main thread), the parity-report collector and the
 * breakdown-findings collector (both on IO). Each publication reads its inputs,
 * derives a verdict and then writes it. Run concurrently, an older pass that
 * read clean findings could write MATCH after a newer pass had already written
 * the verdict for findings that were since revoked, and nothing would correct
 * it. Here every pass runs on the same coroutine, so they never overlap.
 *
 * Requests are conflated: a request made while a pass runs schedules exactly
 * one more pass after it, and a pass always reads its inputs after it starts.
 * So any input change followed by [request] is seen by a pass that finishes
 * after every pass that could have read the older input.
 *
 * [request] never blocks or suspends, so it is safe from dashj callbacks.
 * A pass that throws is reported to [onError] and does not stop later passes.
 * A pass may suspend (it reads the SDK store); the next one still waits for it.
 */
internal class SerialRepublisher(
    scope: CoroutineScope,
    private val onError: (Exception) -> Unit,
    private val pass: suspend () -> Unit
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in requests) {
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** Schedule a pass. Coalesces with any request that has not started yet. */
    fun request() {
        requests.trySend(Unit)
    }
}

/**
 * What one diagnostic pass derived from its inputs.
 *
 * @property parity the verdict to show.
 * @property freshReport the parity report, when it was captured at/after the
 *   catch-up moment; null otherwise.
 * @property freshFindings the breakdown findings, when computed at/after the
 *   catch-up moment; null otherwise.
 */
internal data class DashjDiagnosticVerdict(
    val parity: DashjDiagnosticSyncState.Parity,
    val freshReport: ParityReport?,
    val freshFindings: ParityBreakdownFindings?
)

/**
 * The SDK-vs-dashj verdict for one diagnostic sample.
 *
 * Only inputs captured at/after [caughtUpAtMs] count (a report or breakdown
 * computed while dashj was still behind proves nothing about the caught-up
 * state), and the verdict stays UNKNOWN until dashj reaches 100% and a fresh
 * report exists.
 *
 * The balance-and-count report is NOT a parity check on its own: two engines
 * can hold the same number of transactions, totalling the same balance, and
 * still disagree about WHICH transactions those are, or about what an
 * individual transaction was worth. Both cases have been seen on real wallets.
 * So MATCH additionally requires a fresh transaction-level breakdown that found
 * nothing, and a breakdown that compared no values does not count as having
 * found nothing.
 */
internal fun deriveDashjDiagnosticVerdict(
    percent: Int,
    report: ParityReport?,
    findings: TimedBreakdownFindings?,
    caughtUpAtMs: Long?
): DashjDiagnosticVerdict {
    val freshReport = report?.takeIf { caughtUpAtMs != null && it.timestampMs >= caughtUpAtMs }
    val freshFindings = findings
        ?.takeIf { caughtUpAtMs != null && it.computedAtMs >= caughtUpAtMs }
        ?.findings
    val parity = if (percent >= 100 && freshReport != null) {
        when {
            freshReport.fullMatch && freshFindings?.clean == true ->
                DashjDiagnosticSyncState.Parity.MATCH
            // Balances, counts AND the transaction sets all agree, every
            // eligible value was compared, and only individual stored values
            // differ. Distinguished from the catch-all below so the readout can
            // say how many, because "everything agrees except N values" is a
            // materially different thing to report than "counts differ". Values
            // that could not be compared rule it out: then it is not known that
            // everything else agrees.
            freshReport.fullMatch &&
                freshFindings != null &&
                freshFindings.dashjOnly == 0 &&
                freshFindings.sdkOnly == 0 &&
                freshFindings.valueUnknown == 0 &&
                freshFindings.valueDiffers > 0 ->
                DashjDiagnosticSyncState.Parity.VALUES_DIFFER
            // Something the breakdown found, or it has not run since dashj
            // caught up. The balances still agree, so say exactly that and
            // no more.
            freshReport.balancesMatch && freshReport.confirmedBalancesMatch ->
                DashjDiagnosticSyncState.Parity.BALANCE_MATCH
            else -> DashjDiagnosticSyncState.Parity.MISMATCH
        }
    } else {
        DashjDiagnosticSyncState.Parity.UNKNOWN
    }
    return DashjDiagnosticVerdict(parity, freshReport, freshFindings)
}

/**
 * [deriveDashjDiagnosticVerdict], with the breakdown findings checked against
 * the store they describe before they may count.
 *
 * Findings are true of the SDK store state their run read, and the events
 * that revoke them when it changes can be lost. So whenever the verdict would
 * rest on them — any verdict that read fresh findings, MATCH and
 * VALUES_DIFFER above all — [stillCurrent] re-reads the store's fingerprint
 * ([L1ShadowSyncService.breakdownFindingsStillCurrent]). If they no longer
 * describe it, the verdict is derived again as if no breakdown had run since
 * dashj caught up, which can be BALANCE_MATCH at most.
 *
 * Nothing is read when the verdict would not use the findings (dashj behind,
 * no fresh report, no fresh findings), so the check costs one store read per
 * pass at most, and only while there is a verdict to protect.
 */
internal suspend fun deriveCurrentDashjDiagnosticVerdict(
    percent: Int,
    report: ParityReport?,
    findings: TimedBreakdownFindings?,
    caughtUpAtMs: Long?,
    stillCurrent: suspend (TimedBreakdownFindings) -> Boolean
): DashjDiagnosticVerdict {
    val verdict = deriveDashjDiagnosticVerdict(percent, report, findings, caughtUpAtMs)
    if (findings == null || verdict.freshFindings == null ||
        verdict.parity == DashjDiagnosticSyncState.Parity.UNKNOWN
    ) {
        return verdict
    }
    return if (stillCurrent(findings)) {
        verdict
    } else {
        deriveDashjDiagnosticVerdict(percent, report, null, caughtUpAtMs)
    }
}

/**
 * Write one derived sample to [state]: the live snapshot and the support-log
 * history.
 *
 * While dashj is done but no fresh post-catch-up verdict exists yet, the
 * internal percent is held at 99 and the snapshot is flagged `verifying`, so
 * the readout shows "Verifying" and 100% and the verdict colour then appear
 * together.
 */
internal fun commitDashjDiagnostic(
    state: DashjDiagnosticSyncState,
    percent: Int,
    stageName: String?,
    verdict: DashjDiagnosticVerdict,
    report: ParityReport?
) {
    val verifying = percent >= 100 && verdict.parity == DashjDiagnosticSyncState.Parity.UNKNOWN
    val displayPercent = if (verifying) 99 else percent
    state.update(
        displayPercent, verdict.parity, stageName, verifying,
        valueDiffers = if (verdict.parity == DashjDiagnosticSyncState.Parity.VALUES_DIFFER) {
            verdict.freshFindings?.valueDiffers ?: 0
        } else {
            0
        }
    )
    // Feed the support-log history buffer (deduplicated inside).
    state.recordParity(displayPercent, verdict.parity, report)
}
