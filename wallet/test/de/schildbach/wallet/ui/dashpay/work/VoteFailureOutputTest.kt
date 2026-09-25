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

    private fun failedOutput(error: Exception): Data = runBlocking {
        val input = workDataOf(
            BroadcastUsernameVotesWorker.KEY_PASSWORD to "test-password",
            BroadcastUsernameVotesWorker.KEY_NORMALIZED_LABELS to arrayOf("a1ice"),
            BroadcastUsernameVotesWorker.KEY_LABELS to arrayOf("Alice"),
            BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES to arrayOf(AbstainVoteChoice().toString()),
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
            listOf(Triple(AbstainVoteChoice(), null, error))
        val worker = BroadcastUsernameVotesWorker(
            RuntimeEnvironment.getApplication(), parameters, mockk(relaxed = true), broadcaster,
            mockk(relaxed = true), walletData, mockk(relaxed = true), mockk(relaxed = true)
        )
        val result = worker.doWorkWithBaseProgress()
        assertTrue(result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        Data.fromByteArray(output.toByteArray())
    }
}
