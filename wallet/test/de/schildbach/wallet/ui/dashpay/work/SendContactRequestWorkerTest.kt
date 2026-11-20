package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendContactRequestWorkerTest {
    private val broadcastService = mockk<PlatformBroadcastService>()
    private val identityRepository = mockk<IdentityRepository>()

    @Before
    fun setUp() {
        mockkStatic(WalletApplication::class)
        val app = mockk<WalletApplication>(relaxed = true)
        every { WalletApplication.getInstance() } returns app
        every { app.wallet!!.keyCrypter!!.deriveKey(any<CharSequence>()) } returns KeyParameter(ByteArray(32))
        val request = mockk<DashPayContactRequest>(relaxed = true) {
            every { userId } returns "me"
            every { toUserId } returns "them"
        }
        coEvery { broadcastService.sendContactRequest("them", any()) } returns request
    }

    @After
    fun tearDown() {
        unmockkStatic(WalletApplication::class)
    }

    private fun worker(): SendContactRequestWorker {
        val parameters = mockk<WorkerParameters>(relaxed = true) {
            every { inputData } returns workDataOf(
                SendContactRequestWorker.KEY_PASSWORD to "password",
                SendContactRequestWorker.KEY_TO_USER_ID to "them"
            )
        }
        return SendContactRequestWorker(
            mockk<Context>(relaxed = true),
            parameters,
            mockk(relaxed = true),
            broadcastService,
            identityRepository
        )
    }

    @Test
    fun emptyBalance_doesNotSend() = runTest {
        coEvery { identityRepository.getIdentityBalance() } returns
            CreditBalanceInfo(CreditBalanceInfo.MAX_OPERATION_COST - 1)

        val result = worker().doWorkWithBaseProgress()

        assertTrue(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 0) { broadcastService.sendContactRequest(any(), any()) }
    }

    @Test
    fun unknownBalance_doesNotSend() = runTest {
        coEvery { identityRepository.getIdentityBalance() } returns null

        val result = worker().doWorkWithBaseProgress()

        assertTrue(result is ListenableWorker.Result.Failure)
        coVerify(exactly = 0) { broadcastService.sendContactRequest(any(), any()) }
    }

    @Test
    fun enoughBalance_sends() = runTest {
        coEvery { identityRepository.getIdentityBalance() } returns
            CreditBalanceInfo(CreditBalanceInfo.MAX_OPERATION_COST)

        val result = worker().doWorkWithBaseProgress()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { broadcastService.sendContactRequest("them", any()) }
    }
}
