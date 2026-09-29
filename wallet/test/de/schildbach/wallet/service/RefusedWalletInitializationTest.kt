package de.schildbach.wallet.service

import android.app.Application
import android.os.HandlerThread
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import de.schildbach.wallet.WalletApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RefusedWalletInitializationTest {
    @Test
    fun `refusal releases waiters without stopping before a wipe is armed`() = runBlocking {
        repeat(2) {
            val service = spyk(BlockchainServiceImpl())
            val latch = ReflectionHelpers.getField<CompletableDeferred<Unit>>(service, "onCreateCompleted")
            service.application = mockk<WalletApplication>(relaxed = true)
            every { service.application.isWalletLoadDegraded } returns true
            try {
                assertFalse(latch.isCompleted)
                val command = launch(start = CoroutineStart.UNDISPATCHED) { latch.await() }
                val cleanup = launch(start = CoroutineStart.UNDISPATCHED) { latch.await() }
                every { service.stopSelf() } answers {
                    assertTrue(latch.isCompleted)
                    assertFalse(ReflectionHelpers.getField<Boolean>(service, "initCompleted"))
                    assertTrue(ReflectionHelpers.getField<Boolean>(service, "deleteWalletFileOnShutdown"))
                }

                service.refuseWalletInitialization()
                verify(exactly = 0) { service.stopSelf() }

                withTimeout(1_000) {
                    command.join()
                    cleanup.join()
                }
                assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_WIPE_WALLET))
                verify(exactly = 1) { service.stopSelf() }
                assertFalse(ReflectionHelpers.getField<Boolean>(service, "initCompleted"))
            } finally {
                latch.complete(Unit)
                ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
                ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
            }
        }
    }

    @Test
    fun `null or degraded wallet refuses normal commands and skips shutdown save`() {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        every { service.stopSelf() } answers { }
        try {
            every { application.wallet } returns null
            every { application.isWalletLoadDegraded } returns false
            assertTrue(service.handleWalletLifecycleCommand(null))
            service.saveWalletOnShutdown()
            verify(exactly = 0) { application.saveWallet() }

            val wallet = mockk<org.bitcoinj.wallet.Wallet>()
            every { wallet.context } returns de.schildbach.wallet.Constants.CONTEXT
            every { application.wallet } returns wallet
            every { application.isWalletLoadDegraded } returns true
            assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_RESET_BLOCKCHAIN))
            service.saveWalletOnShutdown()
            verify(exactly = 0) { application.saveWallet() }
            verify(exactly = 2) { service.stopSelf() }

            every { application.isWalletLoadDegraded } returns false
            assertFalse(service.handleWalletLifecycleCommand(null))
            service.saveWalletOnShutdown()
            verify(exactly = 1) { application.saveWallet() }

            assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_WIPE_WALLET))
            service.saveWalletOnShutdown()
            verify(exactly = 1) { application.saveWallet() }
        } finally {
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }
}
