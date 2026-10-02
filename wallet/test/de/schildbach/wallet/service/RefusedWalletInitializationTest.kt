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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
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
        markInitialized(service)
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

    private class RecoveryResetFixture(val directory: File) {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        val binder = mockk<de.schildbach.wallet.service.platform.sdk.SdkWalletBinder>()
        val coordinator = mockk<de.schildbach.wallet.service.platform.sdk.CutoverCoordinator>()
        val blockChain = File(directory, de.schildbach.wallet.Constants.Files.BLOCKCHAIN_FILENAME)
        val headers = File(directory, de.schildbach.wallet.Constants.Files.HEADERS_FILENAME)
        var sdkOwnsL1 = true
        var databasesCleared = true
        var sdkDebtRecorded = true
        var markerDeleted = true
        var failures = 0

        init {
            service.application = application
            service.sdkWalletBinder = binder
            service.cutoverCoordinator = coordinator
            service.packageInfoProvider = mockk(relaxed = true)
            every { service.getDir("blockstore", any()) } returns directory
            every { coordinator.sdkOwnsL1Flow() } answers { kotlinx.coroutines.flow.flowOf(sdkOwnsL1) }
            coEvery { binder.oweSpvRescanForRecoveryReset() } answers { sdkDebtRecorded }
            every { application.recordRecoveryResetFailure() } answers { ++failures }
            every { application.markRecoveryResetComplete() } answers { markerDeleted }
            coEvery { with(WalletApplicationExt) { application.clearDatabasesForRecoveryReset() } } answers {
                databasesCleared
            }
            blockChain.writeText("stale chain")
            headers.writeText("stale headers")
        }

        fun close() {
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }

    private fun withRecoveryReset(block: suspend RecoveryResetFixture.() -> Unit) = runBlocking {
        mockkObject(WalletApplicationExt)
        val fixture = RecoveryResetFixture(directory.newFolder())
        try {
            fixture.block()
        } finally {
            fixture.close()
            unmockkObject(WalletApplicationExt)
        }
    }

    @Test
    fun `recovery reset runs before initialization and completes only when every part succeeded`() =
        withRecoveryReset {
            assertTrue(service.performRecoveryReset())
            assertFalse(blockChain.exists())
            assertFalse(headers.exists())
            coVerify(exactly = 1) { binder.oweSpvRescanForRecoveryReset() }
            verify(exactly = 1) { application.markRecoveryResetComplete() }
            verify(exactly = 0) { application.recordRecoveryResetFailure() }
            // Done inline, never by teardown, so it needs no stopSelf().
            verify(exactly = 0) { service.stopSelf() }
        }

    @Test
    fun `recovery reset stays owed when the SDK rescan cannot be owed`() = withRecoveryReset {
        sdkDebtRecorded = false
        assertFalse(service.performRecoveryReset())
        verify(exactly = 0) { application.markRecoveryResetComplete() }
        verify(exactly = 1) { application.recordRecoveryResetFailure() }
    }

    @Test
    fun `recovery reset stays owed when the databases do not all clear`() = withRecoveryReset {
        databasesCleared = false
        assertFalse(service.performRecoveryReset())
        verify(exactly = 0) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `recovery reset stays owed when a blockstore cannot be deleted`() = withRecoveryReset {
        // A non-empty directory at the store's path cannot be deleted.
        assertTrue(blockChain.delete())
        assertTrue(File(blockChain, "locked").apply { parentFile!!.mkdirs() }.createNewFile())
        assertFalse(service.performRecoveryReset())
        assertTrue(blockChain.exists())
        verify(exactly = 0) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `recovery reset owes no SDK rescan before the cutover`() = withRecoveryReset {
        sdkOwnsL1 = false
        assertTrue(service.performRecoveryReset())
        coVerify(exactly = 0) { binder.oweSpvRescanForRecoveryReset() }
        verify(exactly = 1) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `only a failing database clear is waived after the bounded attempts`() = withRecoveryReset {
        databasesCleared = false
        repeat(de.schildbach.wallet.util.RecoveryResetState.MAX_FAILED_ATTEMPTS - 1) {
            assertFalse(service.performRecoveryReset())
        }
        verify(exactly = 0) { application.markRecoveryResetComplete() }
        assertTrue("the re-syncable stores' clear is waived", service.performRecoveryReset())
        verify(exactly = 1) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `a missing SDK rescan debt is never waived`() = withRecoveryReset {
        sdkDebtRecorded = false
        repeat(de.schildbach.wallet.util.RecoveryResetState.MAX_FAILED_ATTEMPTS + 2) {
            assertFalse(service.performRecoveryReset())
        }
        verify(exactly = 0) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `an undeleted blockstore is never waived`() = withRecoveryReset {
        assertTrue(blockChain.delete())
        assertTrue(File(blockChain, "locked").apply { parentFile!!.mkdirs() }.createNewFile())
        repeat(de.schildbach.wallet.util.RecoveryResetState.MAX_FAILED_ATTEMPTS + 2) {
            assertFalse(service.performRecoveryReset())
        }
        verify(exactly = 0) { application.markRecoveryResetComplete() }
    }

    @Test
    fun `a marker that cannot be deleted fails the reset`() = withRecoveryReset {
        markerDeleted = false
        assertFalse(service.performRecoveryReset())
        verify(exactly = 1) { application.markRecoveryResetComplete() }
        verify(exactly = 1) { application.recordRecoveryResetFailure() }
        markerDeleted = true
        assertTrue(service.performRecoveryReset())
    }

    @Test
    fun `a recovery marker that cannot be inspected never lets initialization proceed`() = withRecoveryReset {
        // The application answers from the real marker state, which the file
        // system cannot establish: no marker file exists, and none can be read.
        val markerDir = this@RefusedWalletInitializationTest.directory.newFolder()
        every { application.isRecoveryResetPending } answers {
            de.schildbach.wallet.util.RecoveryResetState.isPending(markerDir)
        }
        every { application.markRecoveryResetComplete() } answers {
            de.schildbach.wallet.util.RecoveryResetState.complete(markerDir)
        }
        every { application.recordRecoveryResetFailure() } answers {
            de.schildbach.wallet.util.RecoveryResetState.recordFailedAttempt(markerDir)
        }
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { service.stopSelf() } answers { }
        markInitialized(service)
        val onDisk = de.schildbach.wallet.util.RecoveryResetState.inspect
        de.schildbach.wallet.util.RecoveryResetState.inspect = {
            de.schildbach.wallet.util.RecoveryResetState.Marker.UNKNOWN
        }
        try {
            // onCreate's gate: owed, and every part of the reset succeeds,
            // but its completion cannot be confirmed, so it is never waived.
            repeat(de.schildbach.wallet.util.RecoveryResetState.MAX_FAILED_ATTEMPTS + 2) {
                assertTrue(application.isRecoveryResetPending)
                assertFalse(service.performRecoveryReset())
            }
            assertTrue(service.handleWalletLifecycleCommand(null))

            // Once the file system confirms the absence, the next start proceeds.
            de.schildbach.wallet.util.RecoveryResetState.inspect = onDisk
            assertFalse(application.isRecoveryResetPending)
            assertFalse(service.handleWalletLifecycleCommand(null))
        } finally {
            de.schildbach.wallet.util.RecoveryResetState.inspect = onDisk
        }
    }

    @Test
    fun `commands are refused while a recovery reset is still owed`() {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        every { application.wallet } returns mockk()
        every { application.isWalletLoadDegraded } returns false
        every { application.isRecoveryResetPending } returns true
        every { service.stopSelf() } answers { }
        markInitialized(service)
        try {
            assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_RESET_BLOCKCHAIN))
            assertTrue(service.handleWalletLifecycleCommand(null))
            every { application.isRecoveryResetPending } returns false
            assertFalse(service.handleWalletLifecycleCommand(null))
        } finally {
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }
    /**
     * Review, 2026-10-02: onStartCommand runs this check on Main once the latch
     * is released, and a refusal releases it without initializing. A safe-mode
     * retry started meanwhile must not make the refused instance read the
     * wallet (the getter waits on Main for the retry's whole parse) nor, once
     * the retry has cleared the degradation, accept commands it never
     * initialized for.
     */
    @Test
    fun `a refused instance stops without entering the wallet getter while a safe-mode retry loads`() =
        runBlocking {
            val service = spyk(BlockchainServiceImpl())
            val application = mockk<WalletApplication>(relaxed = true)
            service.application = application
            val latch = ReflectionHelpers.getField<CompletableDeferred<Unit>>(service, "onCreateCompleted")
            // The paused retry: degraded answers at once (as WalletApplication
            // does while safeModeRetryInProgress), the getter would block Main.
            var retryLoading = true
            val wallet = mockk<org.bitcoinj.wallet.Wallet>()
            every { application.isWalletLoadDegraded } answers { retryLoading }
            every { application.isRecoveryResetPending } returns false
            every { application.wallet } answers {
                if (retryLoading) error("entered the wallet getter during the safe-mode retry")
                wallet
            }
            every { service.stopSelf() } answers { }
            try {
                service.refuseWalletInitialization()
                withTimeout(1_000) { latch.await() }

                // The queued command reaches the check while the retry is paused.
                assertTrue(service.handleWalletLifecycleCommand(null))
                // The retry succeeds and clears the application's degradation:
                // this instance still never initialized, so it still stops.
                retryLoading = false
                assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_RESET_BLOCKCHAIN))
                verify(exactly = 2) { service.stopSelf() }
                verify(exactly = 0) { application.wallet }
                // A wipe on the refused instance is still recorded and stops.
                assertTrue(service.handleWalletLifecycleCommand(BlockchainService.ACTION_WIPE_WALLET))
                assertTrue(ReflectionHelpers.getField<Boolean>(service, "deleteWalletFileOnShutdown"))
                verify(exactly = 3) { service.stopSelf() }
            } finally {
                ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
                ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
            }
        }

    @Test
    fun `shutdown save checks the degraded state before reading the wallet`() {
        val service = spyk(BlockchainServiceImpl())
        val application = mockk<WalletApplication>(relaxed = true)
        service.application = application
        every { application.isWalletLoadDegraded } returns true
        every { application.wallet } answers { error("entered the wallet getter during the safe-mode retry") }
        try {
            service.saveWalletOnShutdown()
            verify(exactly = 0) { application.wallet }
            verify(exactly = 0) { application.saveWallet() }
        } finally {
            ReflectionHelpers.getField<Job>(service, "serviceJob").cancel()
            ReflectionHelpers.getField<HandlerThread>(service, "notificationHandlerThread").quitSafely()
        }
    }

    /** What onCreate's init coroutine sets when it runs to its end with a wallet. */
    private fun markInitialized(service: BlockchainServiceImpl) {
        ReflectionHelpers.setField(service, "initCompleted", true)
    }
}
