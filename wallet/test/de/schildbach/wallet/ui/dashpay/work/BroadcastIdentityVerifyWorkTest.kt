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
package de.schildbach.wallet.ui.dashpay.work

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A verification link published right after an SDK registration: its worker can run before
 * recovery has loaded the identity, and a re-publish must not broadcast a second document.
 */
class BroadcastIdentityVerifyWorkTest {

    @Test
    fun `an identity recovery has not loaded yet is waited for, not a terminal failure`() {
        assertEquals(
            BroadcastIdentityVerifyWorker.IdentityWait.RETRY,
            BroadcastIdentityVerifyWorker.identityWait(identityLoaded = false, runAttemptCount = 0)
        )
        assertEquals(
            BroadcastIdentityVerifyWorker.IdentityWait.RETRY,
            BroadcastIdentityVerifyWorker.identityWait(
                identityLoaded = false,
                runAttemptCount = BroadcastIdentityVerifyWorker.MAX_IDENTITY_WAIT_ATTEMPTS - 1
            )
        )
    }

    @Test
    fun `a loaded identity publishes, and the wait is bounded`() {
        assertEquals(
            BroadcastIdentityVerifyWorker.IdentityWait.PUBLISH,
            BroadcastIdentityVerifyWorker.identityWait(identityLoaded = true, runAttemptCount = 5)
        )
        assertEquals(
            BroadcastIdentityVerifyWorker.IdentityWait.GIVE_UP,
            BroadcastIdentityVerifyWorker.identityWait(
                identityLoaded = false,
                runAttemptCount = BroadcastIdentityVerifyWorker.MAX_IDENTITY_WAIT_ATTEMPTS
            )
        )
    }

    @Test
    fun `a link already published or still queued for the name is not published again`() {
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(WorkInfo.State.SUCCEEDED)))
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(WorkInfo.State.RUNNING)))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(emptyList()))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(WorkInfo.State.FAILED)))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(WorkInfo.State.CANCELLED)))
    }
}
