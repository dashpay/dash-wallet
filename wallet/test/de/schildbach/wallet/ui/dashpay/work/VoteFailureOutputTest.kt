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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.dashpay.work

import android.app.Application
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.work.BaseWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.KeyParameter
import org.dashj.platform.dpp.voting.AbstainVoteChoice
import org.dashj.platform.dpp.voting.ContestedDocumentResourceVotePoll
import org.dashj.platform.dpp.voting.LockVoteChoice
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.dashj.platform.dpp.voting.Vote
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class VoteFailureOutputTest {
    @Test
    fun `terminal error survives worker serialization and operation conversion`() {
        val reason = "Masternode already voted 5 times, they can only vote 5 times"
        val output = failedOutput(Exception("Protocol error", Exception(reason)))
        assertEquals("Protocol error | $reason", BaseWorker.extractError(output))
        assertArrayEquals(arrayOf("a1ice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS))
        assertArrayEquals(arrayOf("Alice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_LABELS))
        assertArrayEquals(
            arrayOf(AbstainVoteChoice().toString()),
            output.getStringArray(BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES)
        )
        assertTrue(output.getBoolean(BroadcastUsernameVotesWorker.KEY_QUICK_VOTING, false))

        val info = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.FAILED
            every { outputData } returns output
        }
        val resource = BroadcastUsernameVotesOperation.convertState(info)
        assertEquals(Status.ERROR, resource.status)
        assertEquals("Protocol error | $reason", resource.message)
        assertSame(info, resource.data)
    }

    @Test
    fun `large SDK error is bounded before Data serialization`() {
        val reason = "Transport error: " + "x".repeat(20_000)
        assertEquals(reason.take(1024), BaseWorker.extractError(failedOutput(Exception(reason))))
    }

    @Test
    fun `message-less error has a nonempty fallback`() {
        assertEquals("Unknown error - IllegalStateException", BaseWorker.extractError(failedOutput(IllegalStateException())))
    }

    @Test
    fun `fresh and already-cast votes preserve submitted names and choices in order`() {
        val result = runWorker(listOf(
            Triple(AbstainVoteChoice(), null, Exception("vote is already present")),
            Triple(LockVoteChoice(), successfulVote("bob"), null),
            Triple(AbstainVoteChoice(), null, Exception("vote is already present")),
            Triple(LockVoteChoice(), successfulVote("bob"), null)
        ), arrayOf("a1ice", "bob"), arrayOf("Alice", "Bob"),
            arrayOf(AbstainVoteChoice().toString(), LockVoteChoice().toString()))
        assertTrue(result is ListenableWorker.Result.Success)
        val output = (result as ListenableWorker.Result.Success).outputData
        assertArrayEquals(arrayOf("a1ice", "bob"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS))
        assertArrayEquals(arrayOf("Alice", "Bob"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_LABELS))
        assertArrayEquals(arrayOf(AbstainVoteChoice().toString(), LockVoteChoice().toString()),
            output.getStringArray(BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES))
    }

    @Test
    fun `already-cast and terminal failure reports terminal reason in either order`() {
        val reason = "Masternode can only vote 5 times"
        val results = listOf(
            Triple(AbstainVoteChoice(), null, Exception("vote is already present")),
            Triple(AbstainVoteChoice(), null, Exception(reason))
        )
        listOf(results, results.reversed()).forEach { batch ->
            val result = runWorker(batch)
            assertTrue(result is ListenableWorker.Result.Failure)
            val output = (result as ListenableWorker.Result.Failure).outputData
            val info = mockk<WorkInfo> {
                every { state } returns WorkInfo.State.FAILED
                every { outputData } returns output
            }
            val resource = BroadcastUsernameVotesOperation.convertState(info)
            assertEquals(Status.ERROR, resource.status)
            assertEquals(reason, resource.message)
        }
    }

    @Test
    fun `a fresh success does not bury a terminal failure in the same batch`() {
        // The batch the old middle branch missed: it only fired on successCount == 0, so
        // one landed vote was enough to send a vote-limit failure down the success path.
        // convertState then reported Resource.success, UsernameRequestsFragment showed
        // the success indicator and dropped its observer, and the error reached only the
        // log. A quick-vote where Alice lands and Bob is out of votes must still tell the
        // user about Bob.
        val reason = "Masternode with id: Cmb already voted 5 times and is trying to " +
            "vote again, they can only vote 5 times"
        val result = runWorker(listOf(
            Triple(AbstainVoteChoice(), null, Exception("vote is already present")),
            Triple(AbstainVoteChoice(), null, Exception(reason)),
            Triple(AbstainVoteChoice(), successfulVote("a1ice"), null)
        ))
        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData

        // The terminal reason reaches observers, not just logcat.
        val info = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.FAILED
            every { outputData } returns output
        }
        val resource = BroadcastUsernameVotesOperation.convertState(info)
        assertEquals(Status.ERROR, resource.status)
        assertEquals(reason, resource.message)

        // ...and the votes that DID land are still described in the output, so the UI can
        // name what it is reporting on. The rows themselves were persisted before this
        // branch ran and are unaffected by the Result type.
        assertArrayEquals(arrayOf("a1ice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS))
        assertArrayEquals(arrayOf("Alice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_LABELS))
        assertArrayEquals(
            arrayOf(AbstainVoteChoice().toString()),
            output.getStringArray(BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES)
        )
    }

    @Test
    fun `a batch with no terminal failure is still a success`() {
        // The complement of the test above: already-cast results are not terminal, so a
        // mix of landed and already-cast votes must NOT be downgraded to a failure.
        val result = runWorker(listOf(
            Triple(AbstainVoteChoice(), null, Exception("vote is already present")),
            Triple(AbstainVoteChoice(), successfulVote("a1ice"), null)
        ))
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `all already-cast votes still reconcile as success`() {
        val result = runWorker(listOf(Triple(AbstainVoteChoice(), null, Exception("vote is already present"))))
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `an empty broadcast result for a real submission is a failure, not a silent success`() {
        // broadcastUsernameVotes builds its list inside the per-masternode loop, so it
        // returns EMPTY for a non-empty submission when no proTxHash matched the voting
        // key, or when its per-masternode catch swallowed an identity fetch before any
        // entry was added. Nothing was broadcast and nothing was confirmed already cast.
        //
        // This only became user-visible once KEY_VOTE_CHOICES started carrying the
        // SUBMITTED choices: it used to be derived from the (empty) results, and
        // showVoteIndicator returns early on an empty list, so the old output happened
        // not to claim success. Now the claim would be explicit.
        val result = runWorker(emptyList())
        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData

        val info = mockk<WorkInfo> {
            every { state } returns WorkInfo.State.FAILED
            every { outputData } returns output
        }
        val resource = BroadcastUsernameVotesOperation.convertState(info)
        assertEquals(Status.ERROR, resource.status)
        assertEquals("No masternode was able to broadcast a vote", resource.message)

        // The submitted batch is still described, so the UI can name what failed.
        assertArrayEquals(arrayOf("a1ice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS))
        assertArrayEquals(arrayOf("Alice"), output.getStringArray(BroadcastUsernameVotesWorker.KEY_LABELS))
    }

    private fun successfulVote(name: String): Vote {
        val poll = mockk<ContestedDocumentResourceVotePoll> {
            every { indexValues } returns listOf("dash", name)
        }
        return mockk { every { resourceVote.votePoll } returns poll }
    }

    private fun failedOutput(error: Exception): Data {
        val result = runWorker(listOf(Triple(AbstainVoteChoice(), null, error)))
        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        return Data.fromByteArray(output.toByteArray())
    }

    private fun runWorker(
        results: List<Triple<ResourceVoteChoice, Vote?, Exception?>>,
        names: Array<String> = arrayOf("a1ice"),
        labels: Array<String> = arrayOf("Alice"),
        choices: Array<String> = arrayOf(AbstainVoteChoice().toString())
    ): ListenableWorker.Result = runBlocking {
        val input = workDataOf(
            BroadcastUsernameVotesWorker.KEY_PASSWORD to "test-password",
            BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS to names,
            BroadcastUsernameVotesWorker.KEY_LABELS to labels,
            BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES to choices,
            BroadcastUsernameVotesWorker.KEY_MASTERNODE_KEYS to emptyArray<String>(),
            BroadcastUsernameVotesWorker.KEY_QUICK_VOTING to true
        )
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns input
        }
        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet!!.keyCrypter!!.deriveKey("test-password") } returns KeyParameter(ByteArray(32))
        val broadcaster = mockk<PlatformBroadcastService>()
        coEvery { broadcaster.broadcastUsernameVotes(any(), any(), any(), any()) } returns
            results
        val worker = BroadcastUsernameVotesWorker(
            RuntimeEnvironment.getApplication(), parameters, mockk(relaxed = true), broadcaster,
            mockk(relaxed = true), walletData, mockk(relaxed = true), mockk(relaxed = true)
        )
        worker.doWorkWithBaseProgress()
    }
}
