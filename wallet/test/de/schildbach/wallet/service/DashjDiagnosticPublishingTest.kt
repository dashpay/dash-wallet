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

import de.schildbach.wallet.service.DashjDiagnosticSyncState.Parity
import de.schildbach.wallet.service.platform.sdk.ParityBreakdownFindings
import de.schildbach.wallet.service.platform.sdk.ParityReport
import de.schildbach.wallet.service.platform.sdk.TimedBreakdownFindings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DashjDiagnosticPublishingTest {

    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun report(
        timestampMs: Long = REPORT_AT_MS,
        balancesMatch: Boolean = true,
        txCountsMatch: Boolean = true
    ) = ParityReport(
        balancesMatch = balancesMatch,
        sdkDuffs = 1_000,
        dashjDuffs = if (balancesMatch) 1_000 else 900,
        sdkTxCount = 10,
        dashjTxCount = if (txCountsMatch) 10 else 11,
        sdkSynced = true,
        timestampMs = timestampMs,
        confirmedBalancesMatch = balancesMatch,
        sdkConfirmedDuffs = 1_000,
        dashjAvailableDuffs = if (balancesMatch) 1_000 else 900
    )

    private fun findings(
        valueCompared: Int = 5,
        valueDiffers: Int = 0,
        valueUnknown: Int = 0,
        computedAtMs: Long = REPORT_AT_MS
    ) = TimedBreakdownFindings(
        computedAtMs,
        ParityBreakdownFindings(
            dashjOnly = 0,
            sdkOnly = 0,
            valueCompared = valueCompared,
            valueDiffers = valueDiffers,
            valueUnknown = valueUnknown
        )
    )

    private fun verdict(report: ParityReport?, findings: TimedBreakdownFindings?, percent: Int = 100) =
        deriveDashjDiagnosticVerdict(percent, report, findings, CAUGHT_UP_AT_MS)

    // ── The race: an older clean pass finishing after the revocation's pass ──

    @Test
    fun `an older clean pass cannot overwrite the verdict for revoked findings`() {
        val state = DashjDiagnosticSyncState()
        val parityReport = report()
        val latestFindings = MutableStateFlow<TimedBreakdownFindings?>(findings())
        val passCount = AtomicInteger()
        val firstPassHasRead = CountDownLatch(1)
        val releaseFirstPass = CountDownLatch(1)
        val bothPassesDone = CountDownLatch(2)
        val failure = AtomicReference<Exception?>(null)

        // The same read -> derive -> write a BlockchainServiceImpl pass does,
        // with a hook that holds the first pass between its read and its write.
        val republisher = SerialRepublisher(scope, onError = { failure.set(it) }) {
            val derived = verdict(parityReport, latestFindings.value)
            if (passCount.incrementAndGet() == 1) {
                firstPassHasRead.countDown()
                releaseFirstPass.await(5, TimeUnit.SECONDS)
            }
            commitDashjDiagnostic(state, 100, "BLOCKS", derived, parityReport)
            bothPassesDone.countDown()
        }

        // A parity report triggers a publication; it reads the clean findings.
        thread { republisher.request() }
        assertTrue(firstPassHasRead.await(5, TimeUnit.SECONDS))

        // A new transaction expires the findings and the findings collector
        // republishes while the first publication is still in flight.
        latestFindings.value = null
        republisher.request()

        releaseFirstPass.countDown()
        assertTrue(bothPassesDone.await(5, TimeUnit.SECONDS))

        assertNull(failure.get())
        assertEquals(Parity.BALANCE_MATCH, state.state.value.parity)
        assertEquals(Parity.BALANCE_MATCH, state.parityHistory().last().verdict)
    }

    @Test
    fun `a pass that throws does not stop later passes`() {
        val passCount = AtomicInteger()
        val secondPassRan = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val failed = CountDownLatch(1)
        val republisher = SerialRepublisher(
            scope,
            onError = {
                failure.set(it)
                failed.countDown()
            }
        ) {
            if (passCount.incrementAndGet() == 1) error("boom")
            secondPassRan.countDown()
        }

        republisher.request()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        republisher.request()

        assertTrue(secondPassRan.await(5, TimeUnit.SECONDS))
        assertEquals("boom", failure.get()?.message)
    }

    // ── Classification ──

    @Test
    fun `clean fresh findings with a full-match report are MATCH`() {
        assertEquals(Parity.MATCH, verdict(report(), findings()).parity)
    }

    @Test
    fun `absent findings never read as MATCH`() {
        assertEquals(Parity.BALANCE_MATCH, verdict(report(), null).parity)
    }

    @Test
    fun `findings computed before the catch-up do not count`() {
        val stale = findings(computedAtMs = CAUGHT_UP_AT_MS - 1)
        val derived = verdict(report(), stale)
        assertEquals(Parity.BALANCE_MATCH, derived.parity)
        assertNull(derived.freshFindings)
    }

    @Test
    fun `a stale report leaves the verdict UNKNOWN`() {
        assertEquals(
            Parity.UNKNOWN,
            verdict(report(timestampMs = CAUGHT_UP_AT_MS - 1), findings()).parity
        )
    }

    @Test
    fun `below 100 percent the verdict is UNKNOWN`() {
        assertEquals(Parity.UNKNOWN, verdict(report(), findings(), percent = 99).parity)
    }

    @Test
    fun `only differing values, all compared, is VALUES_DIFFER`() {
        val state = DashjDiagnosticSyncState()
        val derived = verdict(report(), findings(valueCompared = 5, valueDiffers = 2))
        assertEquals(Parity.VALUES_DIFFER, derived.parity)

        commitDashjDiagnostic(state, 100, null, derived, report())
        assertEquals(2, state.state.value.valueDiffers)
    }

    @Test
    fun `differing values with some not compared is not VALUES_DIFFER`() {
        val state = DashjDiagnosticSyncState()
        val derived = verdict(report(), findings(valueCompared = 5, valueDiffers = 2, valueUnknown = 1))
        assertEquals(Parity.BALANCE_MATCH, derived.parity)

        // The readout's "not N values" count belongs to VALUES_DIFFER only.
        commitDashjDiagnostic(state, 100, null, derived, report())
        assertEquals(Parity.BALANCE_MATCH, state.state.value.parity)
        assertEquals(0, state.state.value.valueDiffers)
        assertEquals(Parity.BALANCE_MATCH, state.parityHistory().last().verdict)
    }

    @Test
    fun `differing tx counts fall to BALANCE_MATCH, differing balances to MISMATCH`() {
        assertEquals(
            Parity.BALANCE_MATCH,
            verdict(report(txCountsMatch = false), findings(valueDiffers = 2)).parity
        )
        assertEquals(Parity.MISMATCH, verdict(report(balancesMatch = false), findings()).parity)
    }

    @Test
    fun `caught up without a fresh verdict holds at 99 and verifying`() {
        val state = DashjDiagnosticSyncState()
        val derived = verdict(report(timestampMs = CAUGHT_UP_AT_MS - 1), findings())
        commitDashjDiagnostic(state, 100, null, derived, null)
        assertEquals(99, state.state.value.percent)
        assertTrue(state.state.value.verifying)
    }

    private companion object {
        const val CAUGHT_UP_AT_MS = 1_000L
        const val REPORT_AT_MS = 2_000L
    }
}
