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
import de.schildbach.wallet.service.platform.sdk.publishVerificationLinkOnce

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

    private fun run(state: WorkInfo.State, uncertain: Boolean = false) =
        BroadcastIdentityVerifyOperation.PastRun(state, uncertain)

    @Test
    fun `a link already published or still queued for the name is not published again`() {
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.SUCCEEDED))))
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.ENQUEUED))))
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.RUNNING))))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(emptyList()))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.FAILED))))
        assertFalse(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.CANCELLED))))
    }

    @Test
    fun `a publication whose outcome is unknown is never published again`() {
        // the SDK's Ambiguous outcome (a submission timeout) fails the worker, but the document
        // may have landed: another create could pay for a second one
        assertTrue(BroadcastIdentityVerifyOperation.publishedOrPending(listOf(run(WorkInfo.State.FAILED, uncertain = true))))
        assertTrue(
            BroadcastIdentityVerifyOperation.publishedOrPending(
                listOf(run(WorkInfo.State.FAILED), run(WorkInfo.State.FAILED, uncertain = true))
            )
        )
    }

    @Test
    fun `a timed-out publication reconciled while the name retry is still pending creates nothing more`() =
        kotlinx.coroutines.runBlocking {
            val runs = mutableListOf<BroadcastIdentityVerifyOperation.PastRun>()
            var enqueued = 0
            val publish: suspend (String) -> Unit = {
                publishVerificationLinkOnce(
                    link = "https://example.com/proof",
                    pastRuns = { runs.toList() },
                    enqueue = { enqueued++ }
                )
            }

            publish("alice") // the retry's own publication
            assertEquals(1, enqueued)
            runs += run(WorkInfo.State.FAILED, uncertain = true) // its submission timed out
            publish("alice") // the restart reconcile: the retry's mark survived

            assertEquals("no second create while the first may have landed", 1, enqueued)
        }

    @Test
    fun `a definite failure, or no valid link, decides the re-publish`() = kotlinx.coroutines.runBlocking {
        var enqueued = 0
        publishVerificationLinkOnce("https://example.com/proof", { listOf(run(WorkInfo.State.FAILED)) }, { enqueued++ })
        assertEquals("a failure that submitted nothing is published again", 1, enqueued)
        publishVerificationLinkOnce(null, { emptyList() }, { enqueued++ })
        publishVerificationLinkOnce("not a link", { emptyList() }, { enqueued++ })
        assertEquals(1, enqueued)
    }
}
