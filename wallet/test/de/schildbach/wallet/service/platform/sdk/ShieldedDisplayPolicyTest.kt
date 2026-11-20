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

import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure decisions behind the iOS-aligned shielded balance display:
 * [ShieldedSyncFreshnessPolicy] (iOS `ShieldedSyncFreshnessPolicy`) and the
 * post-spend hold rule [decideShieldedDisplay] (iOS `handleShieldedBalanceResult`).
 */
class ShieldedDisplayPolicyTest {

    private val start = 1_000_000L
    private val watchdogAge = ShieldedSyncFreshnessPolicy.MAXIMUM_FULL_SCAN_AGE_MS
    private val foregroundGrace = ShieldedSyncFreshnessPolicy.FOREGROUND_FRESHNESS_GRACE_MS

    // ── ShieldedSyncFreshnessPolicy ──────────────────────────────────────

    @Test
    fun constants_matchIos() {
        assertEquals(15_000L, ShieldedSyncFreshnessPolicy.WATCHDOG_INTERVAL_MS)
        assertEquals(90_000L, watchdogAge)
        assertEquals(30_000L, foregroundGrace)
    }

    @Test
    fun watchdog_refreshesAtExactlyTheMaximumAge_notBefore() {
        val last = start + 5_000L
        assertFalse(watchdog(now = last + watchdogAge - 1, last = last))
        assertTrue(watchdog(now = last + watchdogAge, last = last))
        assertTrue(watchdog(now = last + watchdogAge + 1, last = last))
    }

    @Test
    fun foreground_refreshesAtExactlyTheGrace_notBefore() {
        val last = start + 5_000L
        assertFalse(foreground(now = last + foregroundGrace - 1, last = last))
        assertTrue(foreground(now = last + foregroundGrace, last = last))
    }

    @Test
    fun withNoFullScanYet_theBaselineIsTheMonitoringStart() {
        assertFalse(watchdog(now = start + watchdogAge - 1, last = null))
        assertTrue(watchdog(now = start + watchdogAge, last = null))
        assertFalse(foreground(now = start + foregroundGrace - 1, last = null))
        assertTrue(foreground(now = start + foregroundGrace, last = null))
    }

    @Test
    fun aFullScan_overridesTheMonitoringStart() {
        // Monitoring is ancient but the last scan is recent: not stale.
        assertFalse(watchdog(now = start + 10 * watchdogAge, last = start + 10 * watchdogAge - 1_000L))
    }

    @Test
    fun syncingOrAnInFlightRefresh_suppressBoth() {
        val now = start + 10 * watchdogAge
        assertFalse(watchdog(now = now, last = null, syncing = true))
        assertFalse(watchdog(now = now, last = null, inFlight = true))
        assertFalse(foreground(now = now, last = null, syncing = true))
        assertFalse(foreground(now = now, last = null, inFlight = true))
    }

    private fun watchdog(now: Long, last: Long?, syncing: Boolean = false, inFlight: Boolean = false) =
        ShieldedSyncFreshnessPolicy.shouldRefreshForWatchdog(now, last, start, syncing, inFlight)

    private fun foreground(now: Long, last: Long?, syncing: Boolean = false, inFlight: Boolean = false) =
        ShieldedSyncFreshnessPolicy.shouldRefreshOnForeground(now, last, start, syncing, inFlight)

    // ── decideShieldedDisplay ────────────────────────────────────────────

    private val previous = Dash(150_000_000L)
    private val change = Dash(40_000_000L)

    @Test
    fun aZeroAfterABalance_duringAReconciliation_startsAHold() {
        assertEquals(
            ShieldedDisplayDecision.START_HOLD,
            decide(Dash.ZERO, reconciling = true, last = previous, holding = false)
        )
    }

    @Test
    fun aNonzeroIntermediateReading_publishes() {
        assertEquals(ShieldedDisplayDecision.PUBLISH, decide(change, reconciling = true, last = previous, holding = false))
    }

    @Test
    fun aZeroWithoutAReconciliation_publishes() {
        assertEquals(
            ShieldedDisplayDecision.PUBLISH,
            decide(Dash.ZERO, reconciling = false, last = previous, holding = false)
        )
    }

    @Test
    fun aZeroWithNoPreviousOrAZeroPrevious_publishes() {
        assertEquals(ShieldedDisplayDecision.PUBLISH, decide(Dash.ZERO, reconciling = true, last = null, holding = false))
        assertEquals(
            ShieldedDisplayDecision.PUBLISH,
            decide(Dash.ZERO, reconciling = true, last = Dash.ZERO, holding = false)
        )
    }

    @Test
    fun whileHolding_aZeroKeepsHolding() {
        assertEquals(
            ShieldedDisplayDecision.KEEP_HOLDING,
            decide(Dash.ZERO, reconciling = true, last = previous, holding = true)
        )
    }

    @Test
    fun whileHolding_aReadingAboveZeroReleases() {
        assertEquals(ShieldedDisplayDecision.PUBLISH, decide(change, reconciling = true, last = previous, holding = true))
    }

    @Test
    fun whileHolding_settlementReleases_evenAtZero() {
        assertEquals(
            ShieldedDisplayDecision.PUBLISH,
            decide(Dash.ZERO, reconciling = false, last = previous, holding = true)
        )
    }

    @Test
    fun whileHolding_theWindowEndReleases_atZero() {
        assertEquals(
            ShieldedDisplayDecision.PUBLISH,
            decide(Dash.ZERO, reconciling = true, last = previous, holding = true, windowEnded = true)
        )
    }

    @Test
    fun aWindowEnd_withoutAHold_doesNotRestartOne_onceZeroWasPublished() {
        // After the window published zero, lastPublished is zero: the next zero publishes.
        assertEquals(
            ShieldedDisplayDecision.PUBLISH,
            decide(Dash.ZERO, reconciling = true, last = Dash.ZERO, holding = false)
        )
    }

    private fun decide(reading: Dash, reconciling: Boolean, last: Dash?, holding: Boolean, windowEnded: Boolean = false) =
        decideShieldedDisplay(reading, reconciling, last, holding, windowEnded)
}
