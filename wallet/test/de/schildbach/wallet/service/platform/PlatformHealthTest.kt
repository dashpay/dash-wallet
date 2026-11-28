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
package de.schildbach.wallet.service.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-JVM tests for [assessPlatformHealth] — the pure decision behind
 * the ADVISORY "network is running slower than usual" row on the
 * username entry screen (live incident: Platform's consensus core
 * height lagged a freshly confirmed funding tx and the registration
 * retried invisibly for ~10 minutes).
 */
class PlatformHealthTest {

    // ── DEGRADED: platform core height trails the local tip ────────────────

    @Test
    fun laggingByTheThreshold_isDegraded() {
        assertEquals(
            PlatformHealth.DEGRADED,
            assessPlatformHealth(platformCoreHeight = 1_515_029L, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun laggingBeyondTheThreshold_isDegraded() {
        assertEquals(
            PlatformHealth.DEGRADED,
            assessPlatformHealth(platformCoreHeight = 1_514_000L, localChainHeight = 1_515_031L)
        )
    }

    // ── NORMAL: equal, ahead, or within tolerance ───────────────────────────

    @Test
    fun equalHeights_isNormal() {
        assertEquals(
            PlatformHealth.NORMAL,
            assessPlatformHealth(platformCoreHeight = 1_515_031L, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun oneBlockBehind_isNormalJitter() {
        // One block of skew is ordinary propagation/timing jitter — the
        // exact live-incident gap (1515032 vs 1515031) resolves within a
        // block interval and must not warn.
        assertEquals(
            PlatformHealth.NORMAL,
            assessPlatformHealth(platformCoreHeight = 1_515_030L, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun platformAheadOfLocalTip_isNormal() {
        // The platform side being MORE synced than us is never a problem.
        assertEquals(
            PlatformHealth.NORMAL,
            assessPlatformHealth(platformCoreHeight = 1_515_040L, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun customThreshold_isHonored() {
        assertEquals(
            PlatformHealth.NORMAL,
            assessPlatformHealth(1_515_027L, 1_515_031L, lagThresholdBlocks = 5)
        )
        assertEquals(
            PlatformHealth.DEGRADED,
            assessPlatformHealth(1_515_026L, 1_515_031L, lagThresholdBlocks = 5)
        )
    }

    // ── UNKNOWN: missing evidence never warns ───────────────────────────────

    @Test
    fun nullPlatformHeight_isUnknown() {
        assertEquals(
            PlatformHealth.UNKNOWN,
            assessPlatformHealth(platformCoreHeight = null, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun zeroPlatformHeight_isUnknown() {
        assertEquals(
            PlatformHealth.UNKNOWN,
            assessPlatformHealth(platformCoreHeight = 0L, localChainHeight = 1_515_031L)
        )
    }

    @Test
    fun unusableLocalHeight_isUnknown() {
        // No blockchain state yet (fresh start / unsynced wallet): there is
        // no baseline to compare against — never warn from ignorance.
        assertEquals(
            PlatformHealth.UNKNOWN,
            assessPlatformHealth(platformCoreHeight = 1_515_031L, localChainHeight = 0L)
        )
        assertEquals(
            PlatformHealth.UNKNOWN,
            assessPlatformHealth(platformCoreHeight = 1_515_031L, localChainHeight = -1L)
        )
    }
}
