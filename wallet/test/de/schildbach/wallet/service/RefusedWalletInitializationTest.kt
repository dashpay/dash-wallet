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
import de.schildbach.wallet.WalletApplicationExt
import de.schildbach.wallet.WalletApplicationExt.clearDatabasesForRecoveryReset
import de.schildbach.wallet.WalletApplicationExt.clearDatabasesForRescan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RefusedWalletInitializationTest {
    @get:Rule val directory = TemporaryFolder()
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

    @Test
    fun `wipe recorded at start survives a refused normal command that stops first`() {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        every { application.isWalletLoadDegraded } returns true
        every { service.stopSelf() } answers {
            // Cleanup reads this flag; it must already be set when any command stops the service.
            assertTrue(ReflectionHelpers.getField<Boolean>(service, "deleteWalletFileOnShutdown"))
        }
        try {
            // onStartCommand's synchronous part, for the wipe and then a normal start.
            service.recordWipeRequest(BlockchainService.ACTION_WIPE_WALLET)
            service.recordWipeRequest(null)
            // The normal command's coroutine resumes first and stops the service.
            assertTrue(service.handleWalletLifecycleCommand(null))
            verify(exactly = 1) { service.stopSelf() }
            service.saveWalletOnShutdown()
            verify(exactly = 0) { application.saveWallet() }
        } finally {
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }

    @Test
    fun `owed recovery reset gates initialization and every command`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        val wallet = mockk<org.bitcoinj.wallet.Wallet>()
        every { application.wallet } returns wallet
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns true
        every { service.getDir("blockstore", any()) } returns directory.root
        every { service.stopSelf() } answers { }
        val latch = ReflectionHelpers.getField<CompletableDeferred<Unit>>(service, "onCreateCompleted")
        try {
            service.refuseForRecoveryReset()

            assertTrue(latch.isCompleted)
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "initCompleted"))
            assertTrue(ReflectionHelpers.getField<Boolean>(service, "resetBlockchainOnShutdown"))
            assertTrue(ReflectionHelpers.getField<File>(service, "blockChainFile").parentFile == directory.root)
            verify(exactly = 1) { service.stopSelf() }
            // The queued reset and any other start are refused until the reset ran.
            assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_RESET_BLOCKCHAIN))
            assertTrue(service.handleWalletLifecycleCommand(null))
            verify(exactly = 3) { service.stopSelf() }
            every { application.isRecoveryResetPending } returns false
            assertFalse(service.handleWalletLifecycleCommand(null))
        } finally {
            Dispatchers.resetMain()
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }

    @Test
    fun `recovery reset marker is removed only after every database clear succeeded`() = runBlocking {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        mockkObject(WalletApplicationExt)
        try {
            every { application.isRecoveryResetPending } returns true
            coEvery { with(WalletApplicationExt) { application.clearDatabasesForRecoveryReset() } } returns false
            every { application.recordRecoveryResetFailure() } returns 1
            service.clearDatabasesAfterReset()
            verify(exactly = 0) { application.markRecoveryResetComplete() }

            coEvery { with(WalletApplicationExt) { application.clearDatabasesForRecoveryReset() } } returns true
            service.clearDatabasesAfterReset()
            verify(exactly = 1) { application.markRecoveryResetComplete() }

            // A clear that keeps failing must not stop the wallet syncing forever.
            coEvery { with(WalletApplicationExt) { application.clearDatabasesForRecoveryReset() } } returns false
            every { application.recordRecoveryResetFailure() } returns de.schildbach.wallet.util.RecoveryResetState.MAX_FAILED_ATTEMPTS
            service.packageInfoProvider = mockk(relaxed = true)
            service.clearDatabasesAfterReset()
            verify(exactly = 2) { application.markRecoveryResetComplete() }

            // An ordinary reset keeps the fire-and-forget clear.
            every { application.isRecoveryResetPending } returns false
            every { with(WalletApplicationExt) { application.clearDatabasesForRescan() } } answers { }
            service.clearDatabasesAfterReset()
            verify(exactly = 1) { with(WalletApplicationExt) { application.clearDatabasesForRescan() } }
            coVerify(exactly = 3) { with(WalletApplicationExt) { application.clearDatabasesForRecoveryReset() } }
            verify(exactly = 2) { application.markRecoveryResetComplete() }
        } finally {
            unmockkObject(WalletApplicationExt)
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }
}
