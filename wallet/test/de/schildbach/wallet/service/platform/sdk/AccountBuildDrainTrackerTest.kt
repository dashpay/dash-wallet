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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBuildDrainTrackerTest {

    private var locked = false
    private var now = 1_000_000L
    private val tracker = AccountBuildDrainTracker(
        deviceLocked = { locked },
        elapsedMs = { now },
        unlockGraceMs = 300_000L
    )
    private val wallet = "50dc6706"

    @Test
    fun beforeAnyDrain_theStateIsNotAttempted() {
        assertEquals(AccountBuildDrainState.NOT_ATTEMPTED, tracker.state(wallet))
        tracker.onDrainRan("another wallet")
        assertEquals(AccountBuildDrainState.NOT_ATTEMPTED, tracker.state(wallet))
    }

    @Test
    fun aDrainThatRan_isRan() {
        tracker.onDrainRan(wallet)
        assertEquals(AccountBuildDrainState.RAN, tracker.state(wallet))
    }

    @Test
    fun aRemovedWallet_startsOverAsNotAttempted() {
        // Reset Wallet → restore from seed without a process restart re-creates
        // the same deterministic SDK wallet id, which has drained nothing yet.
        tracker.onDrainRan(wallet)
        tracker.forget(wallet)
        assertEquals(AccountBuildDrainState.NOT_ATTEMPTED, tracker.state(wallet))
    }

    @Test
    fun aDeferralWhileLocked_holdsWhileTheDeviceStaysLocked() {
        locked = true
        assertTrue(tracker.onDrainDeferred(wallet))
        now += 5 * 60 * 60_000L // hours, as on QA phone 2
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
    }

    @Test
    fun aDeferralWhileUnlocked_hasNoKnownTemporaryCause() {
        // Keystore unavailable with the device unlocked: not the locked-device
        // case, so the ordinary pinned-count rule must apply.
        locked = false
        assertFalse(tracker.onDrainDeferred(wallet))
        assertEquals(AccountBuildDrainState.RAN, tracker.state(wallet))
    }

    @Test
    fun afterAnUnlock_theHoldEndsWhenTheNextDrainRuns() {
        locked = true
        tracker.onDrainDeferred(wallet)
        locked = false
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))

        tracker.onDrainRan(wallet)
        assertEquals(AccountBuildDrainState.RAN, tracker.state(wallet))
    }

    @Test
    fun afterAnUnlock_withNoNewDrain_theHoldExpiresAfterTheGrace() {
        locked = true
        tracker.onDrainDeferred(wallet)
        now += 60 * 60_000L
        locked = false
        // The grace runs from the first unlocked read, not from the deferral.
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 299_000L
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 2_000L
        assertEquals(AccountBuildDrainState.RAN, tracker.state(wallet))
    }

    @Test
    fun relockingBeforeTheGraceEnds_restartsTheGraceOnTheNextUnlock() {
        locked = true
        tracker.onDrainDeferred(wallet)
        locked = false
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 200_000L
        locked = true
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 200_000L
        locked = false
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 200_000L
        assertEquals(AccountBuildDrainState.BLOCKED_DEVICE_LOCKED, tracker.state(wallet))
        now += 200_000L
        assertEquals(AccountBuildDrainState.RAN, tracker.state(wallet))
    }
}
