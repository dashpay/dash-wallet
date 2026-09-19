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

import org.dashfoundation.dashsdk.security.DeviceLockState
import org.dashfoundation.dashsdk.security.KeystoreDeviceLockedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure classification behind the "SDK setup pending" state. */
class SdkBindBlockerTest {

    private fun typedDenial(deviceLocked: Boolean) = KeystoreDeviceLockedException(
        "org.dashfoundation.wallet.master",
        "createWallet",
        DeviceLockState(deviceLocked, deviceLocked),
        RuntimeException("Keystore2 denied")
    )

    // The reference install's 2026-09-14 14:49 line, arriving re-thrown as a plain exception.
    private val wrappedDenial = IllegalStateException(
        "Keystore denied 'createWallet' on lock-bound alias org.dashfoundation.wallet.master (isDeviceLocked=true)"
    )

    private val unrelated = IllegalStateException("Room: database is locked")

    @Test
    fun keystoreDenial_isRecognisedTypedOrByMessage_atAnyDepth() {
        assertTrue(isKeystoreDenial(typedDenial(true)))
        assertTrue(isKeystoreDenial(wrappedDenial))
        assertTrue(isKeystoreDenial(RuntimeException("bind failed", typedDenial(false))))
        assertTrue(isKeystoreDenial(RuntimeException("createWallet: UserNotAuthenticatedException")))
        assertFalse(isKeystoreDenial(unrelated))
        assertFalse(isKeystoreDenial(RuntimeException("outer", unrelated)))
    }

    @Test
    fun typedDenial_carriesTheSdkLockSample_wrappedOrNot() {
        assertEquals(true, keystoreDenialReportsDeviceLocked(typedDenial(true)))
        assertEquals(false, keystoreDenialReportsDeviceLocked(RuntimeException("x", typedDenial(false))))
        assertNull(keystoreDenialReportsDeviceLocked(wrappedDenial))
        assertNull(keystoreDenialReportsDeviceLocked(unrelated))
    }

    @Test
    fun denialWhileLocked_isDeviceLocked_byKeyguardOrBySdkSample() {
        // KeyguardManager says locked.
        assertEquals(
            SdkBindBlocker.DEVICE_LOCKED,
            classifyBindFailure(wrappedDenial, deviceProvablyLocked = true, unlockedDenialStreak = 0, otherFailureStreak = 0)
        )
        // Keyguard read says unlocked but the SDK sampled locked at denial time — trust the evidence.
        assertEquals(
            SdkBindBlocker.DEVICE_LOCKED,
            classifyBindFailure(typedDenial(true), deviceProvablyLocked = false, unlockedDenialStreak = 5, otherFailureStreak = 0)
        )
    }

    @Test
    fun denialWhileUnlocked_isTransient_untilThreeInARow() {
        // walletB (HONOR PTP-N49): 7 of 16 denials came with the keyguard reporting unlocked.
        assertEquals(
            SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED,
            classifyBindFailure(typedDenial(false), deviceProvablyLocked = false, unlockedDenialStreak = 1, otherFailureStreak = 0)
        )
        assertEquals(
            SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED,
            classifyBindFailure(typedDenial(false), deviceProvablyLocked = false, unlockedDenialStreak = 2, otherFailureStreak = 0)
        )
        assertEquals(
            SdkBindBlocker.KEYSTORE_PROBLEM,
            classifyBindFailure(typedDenial(false), deviceProvablyLocked = false, unlockedDenialStreak = 3, otherFailureStreak = 0)
        )
        assertTrue(SdkBindBlocker.KEYSTORE_PROBLEM.needsUser)
        assertFalse(SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED.needsUser)
        assertFalse(SdkBindBlocker.DEVICE_LOCKED.needsUser)
    }

    @Test
    fun nonKeystoreFailure_isOther_untilTheLadderIsSpent() {
        assertEquals(
            SdkBindBlocker.OTHER,
            classifyBindFailure(unrelated, deviceProvablyLocked = false, unlockedDenialStreak = 0, otherFailureStreak = 4)
        )
        assertEquals(
            SdkBindBlocker.SETUP_FAILED,
            classifyBindFailure(unrelated, deviceProvablyLocked = false, unlockedDenialStreak = 0, otherFailureStreak = 5)
        )
        // A locked device does not turn an unrelated failure into a lock problem.
        assertEquals(
            SdkBindBlocker.OTHER,
            classifyBindFailure(unrelated, deviceProvablyLocked = true, unlockedDenialStreak = 0, otherFailureStreak = 1)
        )
    }
}
