package de.schildbach.wallet

import android.app.Application
import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.WalletUriHandlerActivity
import de.schildbach.wallet.ui.degradedScreenActions
import de.schildbach.wallet.ui.redirectDegradedWallet
import de.schildbach.wallet.util.SafeModeRetryWaiters
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.spyk
import io.mockk.mockk
import io.mockk.verify
import de.schildbach.wallet.service.WalletFactory
import org.bitcoinj.core.Context
import de.schildbach.wallet.service.platform.sdk.CutoverCoordinator
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.KeyChainGroup
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import de.schildbach.wallet.util.BackupReplacementState
import de.schildbach.wallet.util.RecoveryResetState
import de.schildbach.wallet.util.WalletWipeSequence
import de.schildbach.wallet.util.WalletWipeState
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RecoveredWalletPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
    /** The app's `noBackupFilesDir`, which holds the wipe marker's install token. */
    @get:Rule val noBackupDirectory = TemporaryFolder()
    private val noBackupDir: File get() = noBackupDirectory.root
    private lateinit var app: WalletApplication
    private lateinit var primary: File
    private lateinit var backup: File
    private lateinit var recovered: Wallet

    @Before
    fun setUp() {
        Context.propagate(Constants.CONTEXT)
        app = WalletApplication()
        primary = File(directory.root, "wallet-protobuf")
        backup = File(directory.root, "key-backup-protobuf")
        Wallet(Constants.NETWORK_PARAMETERS).apply {
            freshReceiveKey()
            saveToFile(backup)
        }
        recovered = backup.inputStream().use { WalletProtobufSerializer().readWallet(it) }
        setField("walletFile", primary)
        setField("wallet", recovered)
        // As if loaded from the primary: the wallet whose backup may be written.
        setField("committedWallet", recovered)
    }

    @Test
    fun `failed primary save routes recovered wallet to non-destructive degraded screen`() {
        val originalBackup = backup.readBytes()
        val failingWallet = spyk(recovered)
        every { failingWallet.saveToFile(primary) } throws IOException("injected disk full")
        try {
            app.persistRecoveredWallet(failingWallet)
            fail("save must fail")
        } catch (expected: IOException) {
            // The recovery caller reports this failure and retains the wallet.
        }

        assertFalse(app.walletFileExists())
        assertSame(recovered, app.wallet)
        assertFalse(recovered.isEncrypted)
        // Onboarding checks this before its primary-file and encryption routing.
        assertTrue(app.isWalletLoadDegraded)
        assertFalse(app.isWalletRecoveryFromSeedNeeded)
        assertEquals(false, retryResult(app))
        assertArrayEquals(originalBackup, backup.readBytes())

        // Exercise the shared activity-entry guard with a non-null recovered
        // wallet, as when Android restores a task or delivers an external URI.
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        assertTrue(activity.redirectDegradedWallet(app))
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        val redirect = shadowOf(activity).nextStartedActivity
        assertEquals(OnboardingActivity::class.java.name, redirect.component!!.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            redirect.flags
        )
        controller.destroy()

        // Invoke the URI entry point itself, before any parsing or key access.
        val uriActivity = Robolectric.buildActivity(WalletUriHandlerActivity::class.java).get()
        ReflectionHelpers.setField(uriActivity, "mApplication", app)
        ReflectionHelpers.setField(uriActivity, "wallet", recovered)
        WalletUriHandlerActivity::class.java.getDeclaredMethod("handleIntent", Intent::class.java).apply {
            isAccessible = true
            invoke(uriActivity, Intent(Intent.ACTION_VIEW))
        }
        assertTrue(uriActivity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(uriActivity).resultCode)
        assertEquals(
            OnboardingActivity::class.java.name,
            shadowOf(uriActivity).nextStartedActivity.component!!.className
        )

        // A safe-mode retry whose load ends with the recovered wallet still
        // unsaved reports "not loaded" to its waiters and stays degraded.
        // completeSafeModeRetry() is the retry's main-thread completion; the
        // load itself cleared walletLoadSkippedSafeMode before calling it.
        setField("safeModeRetryInProgress", true)
        var retried: Boolean? = null
        val waiters = getField("safeModeRetryCallbacks") as SafeModeRetryWaiters
        waiters.add { retried = it }
        WalletApplication::class.java.getDeclaredMethod("completeSafeModeRetry").apply {
            isAccessible = true
            invoke(app)
        }
        assertEquals(false, retried)
        assertFalse(app.isSafeModeRetryInProgress)
        assertTrue(app.isWalletLoadDegraded)

        app.persistRecoveredWallet(recovered)
        assertTrue(app.walletFileExists())
        assertFalse(app.isWalletLoadDegraded)
        val saved = primary.inputStream().use { WalletProtobufSerializer().readWallet(it) }
        assertEquals(recovered.currentReceiveAddress(), saved.currentReceiveAddress())
        assertArrayEquals(originalBackup, backup.readBytes())
    }

    @Test
    fun `backup recovery does not queue reset when primary persistence fails`() {
        val originalBackup = backup.readBytes()
        // A missing parent forces the actual protobuf save to fail.
        setField("walletFile", File(directory.root, "missing/primary"))
        setField("walletFactory", mockk<WalletFactory> {
            every { getExtensions(any()) } returns emptyArray()
        })
        val recoveryApp = spyk(app)
        every { recoveryApp.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } answers { backup.inputStream() }
        every { recoveryApp.resetBlockchain() } answers { fail("must not queue reset before persistence succeeds") }

        val restored = WalletApplication::class.java.getDeclaredMethod("restoreWalletFromBackup").run {
            isAccessible = true
            invoke(recoveryApp) as Wallet
        }

        assertTrue(restored.isPubKeyMine(recovered.currentReceiveKey().pubKey))
        assertTrue(recoveryApp.isWalletLoadDegraded)
        assertFalse(recoveryApp.isWalletRecoveryFromSeedNeeded)
        assertArrayEquals(originalBackup, backup.readBytes())
        verify(exactly = 0) { recoveryApp.resetBlockchain() }
    }

    @Test
    fun `owed recovery reset survives later launches without queueing a second reset`() {
        val marker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        app.persistRecoveredWallet(recovered)
        assertTrue(primary.exists())
        assertTrue(marker.exists())

        // The process died before the reset ran: the next launch loads the
        // saved primary as an ordinary wallet. The marker keeps the reset
        // owed for the blockchain service's onCreate, which performs it; the
        // load does only the in-process half and queues no reset intent.
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        setField("walletFactory", mockk<WalletFactory> {
            every { getExtensions(any()) } returns emptyArray()
        })
        val launchApp = spyk(app)
        every { launchApp.resetBlockchain() } answers { fail("must not queue ACTION_RESET_BLOCKCHAIN") }
        every { launchApp.resetBlockchainInProcess() } answers { }
        every { launchApp.finalizeInitialization() } answers { }
        val load = WalletApplication::class.java.getDeclaredMethod("loadWalletFromProtobuf").apply {
            isAccessible = true
        }
        load.invoke(launchApp)
        load.invoke(launchApp)
        verify(exactly = 2) { launchApp.resetBlockchainInProcess() }
        verify(exactly = 2) { launchApp.finalizeInitialization() }
        assertTrue(marker.exists())

        launchApp.markRecoveryResetComplete()
        assertFalse(marker.exists())
        load.invoke(launchApp)
        verify(exactly = 2) { launchApp.resetBlockchainInProcess() }
        verify(exactly = 3) { launchApp.finalizeInitialization() }
        verify(exactly = 0) { launchApp.resetBlockchain() }
    }

    @Test
    fun `an unreadable recovery marker keeps the reset owed`() {
        val marker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        app.persistRecoveredWallet(recovered)
        // On disk the marker is gone, but the file system cannot confirm it.
        assertTrue(marker.delete())
        val onDisk = RecoveryResetState.inspect
        RecoveryResetState.inspect = { RecoveryResetState.Marker.UNKNOWN }
        try {
            assertTrue("fail closed", app.isRecoveryResetPending)
            assertFalse("an unverified absence is not a completed reset", app.markRecoveryResetComplete())

            // The load still does the in-process half of the reset.
            setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
            setField("walletFactory", mockk<WalletFactory> {
                every { getExtensions(any()) } returns emptyArray()
            })
            val launchApp = spyk(app)
            every { launchApp.resetBlockchain() } answers { fail("must not queue ACTION_RESET_BLOCKCHAIN") }
            every { launchApp.resetBlockchainInProcess() } answers { }
            every { launchApp.finalizeInitialization() } answers { }
            val load = WalletApplication::class.java.getDeclaredMethod("loadWalletFromProtobuf").apply {
                isAccessible = true
            }
            load.invoke(launchApp)
            verify(exactly = 1) { launchApp.resetBlockchainInProcess() }

            // Once the absence is confirmed, the load no longer resets.
            RecoveryResetState.inspect = onDisk
            assertFalse(launchApp.isRecoveryResetPending)
            load.invoke(launchApp)
            verify(exactly = 1) { launchApp.resetBlockchainInProcess() }
        } finally {
            RecoveryResetState.inspect = onDisk
        }
    }

    @Test
    fun `a wipe keeps its marker while the recovery marker's absence is unverified`() {
        val wipeApp = spyk(app)
        every { wipeApp.filesDir } returns directory.root
        every { wipeApp.noBackupFilesDir } returns noBackupDir
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        val onDisk = RecoveryResetState.inspect
        RecoveryResetState.inspect = { RecoveryResetState.Marker.UNKNOWN }
        try {
            wipeApp.markWalletWipeComplete()
            assertTrue("the next launch must retry", WalletWipeState.isPending(directory.root, noBackupDir))
        } finally {
            RecoveryResetState.inspect = onDisk
        }
        wipeApp.markWalletWipeComplete()
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
    }

    @Test
    fun `failed recovery resets are counted in the marker`() {
        app.persistRecoveredWallet(recovered)
        assertTrue(app.isRecoveryResetPending)
        assertEquals(1, app.recordRecoveryResetFailure())
        assertEquals(2, app.recordRecoveryResetFailure())
        assertTrue(app.isRecoveryResetPending)
        // A later recovery re-arms without resetting the count.
        app.persistRecoveredWallet(recovered)
        assertEquals(3, app.recordRecoveryResetFailure())
        assertTrue(app.markRecoveryResetComplete())
        assertFalse(app.isRecoveryResetPending)
        assertTrue("already absent", app.markRecoveryResetComplete())
    }

    @Test
    fun `a recovery marker that cannot be deleted reports it`() {
        // A non-empty directory at the marker path cannot be deleted.
        val marker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        assertTrue(File(marker, "block").apply { parentFile!!.mkdirs() }.createNewFile())
        assertTrue(app.isRecoveryResetPending)
        assertFalse(app.markRecoveryResetComplete())
        assertTrue(app.isRecoveryResetPending)
    }

    @Test
    fun `recovered primary is not saved when the reset marker cannot be written`() {
        // A missing directory makes the marker write fail before the save.
        val primaryInMissingDir = File(directory.root, "missing/primary")
        setField("walletFile", primaryInMissingDir)
        val savingWallet = spyk(recovered)
        try {
            app.persistRecoveredWallet(savingWallet)
            fail("marker write must fail")
        } catch (expected: IOException) {
            // The primary save never ran, so this came from the marker write.
        }
        verify(exactly = 0) { savingWallet.saveToFile(any()) }
        assertTrue(app.isWalletLoadDegraded)
        assertFalse(primaryInMissingDir.exists())
    }

    @Test
    fun `persisting recovered wallet does not clear an independent wipe recovery guard`() {
        val originalBackup = backup.readBytes()
        setField("recoveredWalletPersistencePending", true)
        setField("walletWipeRecoveryRequired", true)

        app.persistRecoveredWallet(recovered)
        assertTrue(app.isWalletLoadDegraded)
        assertTrue(app.isWalletWipeRecoveryRequired)
        assertEquals(false, retryResult(app))

        val originalPrimary = primary.readBytes()
        // Must return before initialization or loading, even with a usable primary.
        app.fullInitialization()
        assertArrayEquals(originalPrimary, primary.readBytes())
        assertArrayEquals(originalBackup, backup.readBytes())

        val controller = Robolectric.buildActivity(Activity::class.java).create()
        assertTrue(controller.get().redirectDegradedWallet(app))
        assertTrue(controller.get().isFinishing)
        controller.destroy()

        setField("recoveredWalletPersistencePending", true)
        setField("walletWipeRecoveryRequired", false)
        assertTrue(app.isWalletLoadDegraded)
        assertEquals(false, retryResult(app))

        app.persistRecoveredWallet(recovered)
        assertFalse(app.isWalletLoadDegraded)
    }

    @Test
    fun `reset request with legacy marker never starts wipe service`() {
        val files = directory.newFolder("files")
        val noBackup = directory.newFolder("no-backup")
        val marker = File(files, de.schildbach.wallet.util.WalletWipeState.MARKER_FILE_NAME)
        marker.createNewFile()
        val guardedApp = spyk(app)
        every { guardedApp.filesDir } returns files
        every { guardedApp.noBackupFilesDir } returns noBackup
        every { guardedApp.startService(any()) } answers { fail("must not start wipe service"); null }

        guardedApp.triggerWipe()

        assertTrue(guardedApp.isWalletWipeRecoveryRequired)
        assertEquals("", marker.readText())
        assertTrue(noBackup.listFiles()!!.isEmpty())
        verify(exactly = 0) { guardedApp.startService(any()) }
    }

    @Test
    fun `wipe recovery outranks seed recovery and no replacement wallet is installed or saved`() {
        // A failed load latched seed recovery; a stopped or unverified wipe then
        // set the wipe guard without clearing it (review, PR #1577).
        ReflectionHelpers.setField(app, "wallet", null)
        setField("walletLoadFailed", true)
        setField("walletRecoveryFromSeedNeeded", true)
        setField("walletWipeRecoveryRequired", true)
        assertTrue(app.isWalletLoadDegraded)
        assertTrue(app.isWalletRecoveryFromSeedNeeded)

        val actions = degradedScreenActions(
            wipeRecoveryRequired = app.isWalletWipeRecoveryRequired,
            safeMode = app.isSafeModeLaunch,
            recoveryFromSeedNeeded = app.isWalletRecoveryFromSeedNeeded,
            firstShow = true
        )
        assertTrue(actions.showWipeRecoveryMessage)
        assertFalse("Restore Wallet must not be offered", actions.offerSeedRecovery)
        assertFalse(actions.offerSafeModeRetry)

        // Every create/restore flow installs its wallet through setWallet.
        assertTrue(app.isWalletReplacementRefused)
        assertFalse(app.setWallet(Wallet(Constants.NETWORK_PARAMETERS)))
        assertNull(app.wallet)
        assertFalse(primary.exists())
        // The retry refuses at once, starting no load, and keeps the
        // safe-mode verdict.
        setField("walletLoadSkippedSafeMode", true)
        assertEquals(false, retryResult(app))
        assertFalse(app.isSafeModeRetryInProgress)
        assertTrue(app.isSafeModeLaunch)
        assertTrue("the seed-recovery verdict is kept, not consumed", app.isWalletRecoveryFromSeedNeeded)
        assertTrue(app.isWalletLoadDegraded)
    }

    @Test
    fun `a valid wipe marker on disk refuses a replacement wallet and latches the guard`() {
        ReflectionHelpers.setField(app, "wallet", null)
        val guardedApp = spyk(app)
        every { guardedApp.filesDir } returns directory.root
        every { guardedApp.noBackupFilesDir } returns noBackupDir
        assertFalse(guardedApp.isWalletReplacementRefused)

        // The next cold launch would read this marker as PENDING and wipe
        // whatever wallet it found.
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        assertFalse(guardedApp.setWallet(Wallet(Constants.NETWORK_PARAMETERS)))
        assertNull(guardedApp.wallet)
        assertFalse(primary.exists())
        assertTrue(guardedApp.isWalletWipeRecoveryRequired)
        assertTrue(guardedApp.isWalletLoadDegraded)
    }

    @Test
    fun `new unsaved onboarding wallet does not activate recovery guard`() {
        setField("wallet", Wallet(Constants.NETWORK_PARAMETERS))
        assertFalse(app.walletFileExists())
        assertFalse(app.isWalletLoadDegraded)
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        assertFalse(activity.redirectDegradedWallet(app))
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.destroy()
    }

    private fun retryResult(app: WalletApplication): Boolean? {
        var result: Boolean? = null
        app.retryWalletLoadAfterSafeMode { result = it }
        return result
    }

    private fun getField(name: String): Any? = WalletApplication::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(app)
    }

    private fun setField(name: String, value: Any) {
        WalletApplication::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(app, value)
        }
    }

    @Test
    fun `replacement setup clears old recovery flags only after saving succeeds`() {
        setField("walletLoadFailed", true)
        setField("walletLoadSkippedSafeMode", true)
        setField("walletRecoveryFromSeedNeeded", true)
        setField("recoveredWalletPersistencePending", true)
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        val setupApp = spyk(app)
        var failBackup = true
        every { setupApp.writeWalletBackup() } answers {
            // Still protected until backup persistence has completed.
            assertTrue(setupApp.isWalletLoadDegraded)
            assertTrue(primary.exists())
            if (failBackup) throw IOException("injected backup failure")
            recovered.saveToFile(backup)
        }
        every { setupApp.finalizeInitialization() } answers {
            assertFalse(setupApp.isWalletLoadDegraded)
            assertFalse(setupApp.isWalletRecoveryFromSeedNeeded)
            assertFalse(setupApp.isSafeModeLaunch)
        }
        ReflectionHelpers.setField(setupApp, "walletFile", File(directory.root, "missing/replacement"))
        try {
            setupApp.saveWalletAndFinalizeInitialization()
            fail("replacement save must fail")
        } catch (expected: RuntimeException) {
            assertTrue(expected.cause is IOException)
        }
        assertTrue(setupApp.isWalletLoadDegraded)
        assertTrue(setupApp.isWalletRecoveryFromSeedNeeded)
        verify(exactly = 0) { setupApp.writeWalletBackup() }
        verify(exactly = 0) { setupApp.finalizeInitialization() }

        ReflectionHelpers.setField(setupApp, "walletFile", primary)
        try {
            setupApp.saveWalletAndFinalizeInitialization()
            fail("backup save must fail")
        } catch (expected: RuntimeException) {
            // The real IOException path, which backupWallet() alone would swallow.
            assertTrue(expected.cause is IOException)
            assertTrue(setupApp.isWalletLoadDegraded)
            assertTrue(setupApp.isWalletRecoveryFromSeedNeeded)
        }
        verify(exactly = 0) { setupApp.finalizeInitialization() }
        failBackup = false
        setupApp.saveWalletAndFinalizeInitialization()
        verify(exactly = 1) { setupApp.finalizeInitialization() }
        val saved = primary.inputStream().use { WalletProtobufSerializer().readWallet(it) }
        assertTrue(saved.isPubKeyMine(recovered.currentReceiveKey().pubKey))
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        assertFalse(controller.get().redirectDegradedWallet(setupApp))
        controller.destroy()
    }

    @Test
    fun `failed replacement primary save leaves the previous backup in place`() {
        val previousBackup = backup.readBytes()
        setField("wallet", Wallet(Constants.NETWORK_PARAMETERS).apply { freshReceiveKey() })
        setField("walletFile", File(directory.root, "missing/primary"))
        val setupApp = spyk(app)
        try {
            setupApp.saveWalletAndFinalizeInitialization()
            fail("primary save must fail")
        } catch (expected: RuntimeException) {
            assertTrue(expected.cause is IOException)
        }
        // Still the recovery input at the path a later launch restores from.
        assertArrayEquals(previousBackup, backup.readBytes())
        assertEquals(listOf(backup.name), directory.root.listFiles()!!.filter { it.isFile }.map { it.name })
        verify(exactly = 0) { setupApp.writeWalletBackup() }
    }

    private val replacementMarker get() = File(directory.root, BackupReplacementState.MARKER_FILE_NAME)

    /** An app whose key backup lives at [backup] and whose backup writes copy [recovered] there, or fail. */
    private fun maintenanceApp(failWrite: () -> Boolean = { false }): WalletApplication {
        val maintenanceApp = spyk(app)
        every { maintenanceApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns backup
        every { maintenanceApp.writeWalletBackup(any()) } answers {
            if (failWrite()) throw IOException("injected backup failure")
            recovered.saveToFile(backup)
        }
        return maintenanceApp
    }

    @Test
    fun `startup maintenance rewrites the backup a replacement still owes, then clears the marker`() {
        replacementMarker.createNewFile()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup(recovered)

        verify(exactly = 1) { maintenanceApp.writeWalletBackup(any()) }
        assertFalse("cleared only after the rewrite", replacementMarker.exists())
    }

    @Test
    fun `startup maintenance leaves a present backup alone when nothing is owed`() {
        val previousBackup = backup.readBytes()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup(recovered)

        // No parse of the backup and no second wallet: nothing is read at all.
        verify(exactly = 0) { maintenanceApp.writeWalletBackup(any()) }
        verify(exactly = 0) { maintenanceApp.openFileInput(any()) }
        assertArrayEquals(previousBackup, backup.readBytes())
    }

    @Test
    fun `startup maintenance writes a missing backup without any marker`() {
        backup.delete()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup(recovered)

        verify(exactly = 1) { maintenanceApp.writeWalletBackup(any()) }
        assertTrue(backup.exists())
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `a failed backup rewrite keeps the replacement marker`() {
        replacementMarker.createNewFile()
        val maintenanceApp = maintenanceApp(failWrite = { true })

        try {
            maintenanceApp.maintainKeyBackup(recovered)
            fail("the rewrite must fail")
        } catch (expected: IOException) {
            // Logged by the maintenance thread.
        }
        assertTrue("still owed to the next startup", replacementMarker.exists())
    }

    @Test
    fun `an uninspectable replacement marker rewrites the backup`() {
        val original = BackupReplacementState.confirmAbsent
        BackupReplacementState.confirmAbsent = { throw SecurityException("injected") }
        try {
            val maintenanceApp = maintenanceApp()
            maintenanceApp.maintainKeyBackup(recovered)
            verify(exactly = 1) { maintenanceApp.writeWalletBackup(any()) }
        } finally {
            BackupReplacementState.confirmAbsent = original
        }
    }

    /**
     * Startup maintenance repairs wallet A's owed backup while onboarding
     * installs wallet B, arms the marker and saves B's primary. A's
     * completion must not clear B's obligation; B's own completion does
     * (review, PR #1576).
     */
    private fun assertRepairLeavesANewerReplacementOwed(armA: () -> Unit) {
        armA()
        val replacementB = Wallet(Constants.NETWORK_PARAMETERS).apply { freshReceiveKey() }
        var armedB: BackupReplacementState.Generation? = null
        val maintenanceApp = spyk(app)
        every { maintenanceApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns backup
        every { maintenanceApp.writeWalletBackup(any()) } answers {
            // A's backup is published...
            recovered.saveToFile(backup)
            // ...and before A's completion runs, B arms and saves its primary.
            armedB = BackupReplacementState.arm(directory.root)
            replacementB.saveToFile(primary)
        }

        maintenanceApp.maintainKeyBackup(recovered)

        verify(exactly = 1) { maintenanceApp.writeWalletBackup(any()) }
        assertTrue("B's backup is still owed", replacementMarker.exists())
        assertTrue(BackupReplacementState.isPending(directory.root))

        // B's backup is published, and B's completion clears its own arm.
        replacementB.saveToFile(backup)
        assertTrue(BackupReplacementState.complete(directory.root, requireNotNull(armedB)))
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `a repair's completion does not clear a replacement armed during the repair`() {
        assertRepairLeavesANewerReplacementOwed { BackupReplacementState.arm(directory.root) }
    }

    @Test
    fun `a repair of a legacy empty marker does not clear a replacement armed during the repair`() {
        assertRepairLeavesANewerReplacementOwed { replacementMarker.createNewFile() }
    }

    /**
     * A replacement primary that cannot be loaded beside the previous wallet's
     * key backup, its replacement backup still owed (review, PR #1576).
     */
    private fun interruptedReplacementApp(): WalletApplication {
        // The replacement: other key material than the backup, cut short on
        // disk so its parse fails.
        val replacement = Wallet(Constants.NETWORK_PARAMETERS).apply { freshReceiveKey() }
        assertFalse(replacement.isPubKeyMine(recovered.currentReceiveKey().pubKey))
        replacement.saveToFile(primary)
        val intact = primary.readBytes()
        primary.writeBytes(intact.copyOf(intact.size / 2))
        ReflectionHelpers.setField(app, "wallet", null)
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        setField("walletFactory", mockk<WalletFactory> {
            every { getExtensions(any()) } returns emptyArray()
        })
        val launchApp = spyk(app)
        every { launchApp.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } answers { backup.inputStream() }
        every { launchApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns backup
        every { launchApp.persistRecoveredWallet(any()) } answers { fail("must not persist the previous wallet's backup") }
        every { launchApp.resetBlockchain() } answers { fail("must not queue a reset") }
        every { launchApp.resetBlockchainInProcess() } answers { fail("must not reset") }
        every { launchApp.finalizeInitialization() } answers { fail("must not publish a wallet") }
        every { launchApp.maintainKeyBackup(any()) } answers { fail("maintenance must not run") }
        // The load's Toast needs a real Context.
        every { launchApp["showLoadToast"](any<String>()) } answers { }
        return launchApp
    }

    private fun loadWallet(launchApp: WalletApplication) {
        WalletApplication::class.java.getDeclaredMethod("loadWalletFromProtobuf").apply {
            isAccessible = true
            invoke(launchApp)
        }
    }

    @Test
    fun `an unloadable primary whose replacement backup is owed is not recovered from the previous backup`() {
        val launchApp = interruptedReplacementApp()
        replacementMarker.createNewFile()
        // A reset still owed by an earlier recovery: also left alone.
        val resetMarker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        RecoveryResetState.arm(directory.root)
        val primaryBytes = primary.readBytes()
        val backupBytes = backup.readBytes()

        val restored = WalletApplication::class.java.getDeclaredMethod("restoreWalletFromBackup").run {
            isAccessible = true
            invoke(launchApp)
        }
        assertNull(restored)
        loadWallet(launchApp)

        assertNull(launchApp.wallet)
        verify(exactly = 0) { launchApp.persistRecoveredWallet(any()) }
        verify(exactly = 0) { launchApp.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) }
        verify(exactly = 0) { launchApp.maintainKeyBackup(any()) }
        assertArrayEquals("primary untouched", primaryBytes, primary.readBytes())
        assertArrayEquals("backup untouched", backupBytes, backup.readBytes())
        assertTrue("replacement still owed", replacementMarker.exists())
        assertTrue("reset still owed", resetMarker.exists())
        // Onboarding opens the degraded screen: restore from the recovery
        // phrase, never Create/Restore over the files.
        assertTrue(launchApp.isWalletLoadDegraded)
        assertTrue(launchApp.isWalletRecoveryFromSeedNeeded)
        val actions = degradedScreenActions(
            wipeRecoveryRequired = false,
            safeMode = false,
            recoveryFromSeedNeeded = launchApp.isWalletRecoveryFromSeedNeeded,
            firstShow = true
        )
        assertTrue(actions.offerSeedRecovery)
    }

    @Test
    fun `an uninspectable replacement marker refuses recovery from the backup`() {
        val launchApp = interruptedReplacementApp()
        val primaryBytes = primary.readBytes()
        val backupBytes = backup.readBytes()
        val original = BackupReplacementState.confirmAbsent
        BackupReplacementState.confirmAbsent = { throw SecurityException("injected") }
        try {
            loadWallet(launchApp)
        } finally {
            BackupReplacementState.confirmAbsent = original
        }

        assertNull(launchApp.wallet)
        verify(exactly = 0) { launchApp.persistRecoveredWallet(any()) }
        assertArrayEquals(primaryBytes, primary.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
        assertTrue(launchApp.isWalletRecoveryFromSeedNeeded)
    }

    @Test
    fun `replacement setup owes its backup before the primary save and clears it after the backup`() {
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        val setupApp = spyk(app)
        var failBackup = true
        every { setupApp.saveWallet(true) } answers {
            assertTrue("owed before the primary is saved", replacementMarker.exists())
            callOriginal()
        }
        every { setupApp.writeWalletBackup() } answers {
            if (failBackup) throw IOException("injected backup failure")
            recovered.saveToFile(backup)
        }
        every { setupApp.finalizeInitialization() } returns Unit

        try {
            setupApp.saveWalletAndFinalizeInitialization()
            fail("backup save must fail")
        } catch (expected: RuntimeException) {
            assertTrue(expected.cause is IOException)
        }
        assertTrue("a death or failure before the backup leaves it owed", replacementMarker.exists())

        failBackup = false
        setupApp.saveWalletAndFinalizeInitialization()
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `replacement primary is not saved when the backup marker cannot be written`() {
        // The marker's directory is a file, so the marker cannot be created.
        val blocker = File(directory.root, "blocker").apply { writeText("") }
        setField("walletFile", File(blocker, "primary"))
        val setupApp = spyk(app)
        try {
            setupApp.saveWalletAndFinalizeInitialization()
            fail("setup must fail without its marker")
        } catch (expected: RuntimeException) {
            assertTrue(expected.cause is IOException)
        }
        verify(exactly = 0) { setupApp.saveWallet(any()) }
        verify(exactly = 0) { setupApp.writeWalletBackup() }
    }

    @Test
    fun `successful wipe clears recovery guard but failed and unrequested wipes retain it`() = runBlocking {
        setField("walletFile", File(directory.root, "missing/primary"))
        try {
            app.persistRecoveredWallet(recovered)
            fail("save must fail")
        } catch (expected: IOException) {
            assertTrue(app.isWalletLoadDegraded)
        }
        val wipeApp = spyk(app)
        every { wipeApp.filesDir } returns directory.root
        every { wipeApp.noBackupFilesDir } returns noBackupDir
        suspend fun wipe(destroy: suspend () -> Unit): Boolean = WalletWipeSequence.finish(
            pending = { WalletWipeState.isPending(directory.root, noBackupDir) },
            detachWallet = { ReflectionHelpers.setField(wipeApp, "wallet", null) },
            destroy = destroy,
            markComplete = { wipeApp.markWalletWipeComplete() }
        )

        assertFalse(wipe { fail("unrequested wipe must not destroy data") })
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        try {
            wipe { throw IOException("injected destruction failure") }
            fail("wipe must fail")
        } catch (expected: IOException) {
            assertTrue(wipeApp.isWalletLoadDegraded)
            assertTrue(WalletWipeState.isPending(directory.root, noBackupDir))
        }

        // Marker removal fails: the valid marker stays on disk. (A directory at
        // the marker path no longer simulates this — the marker would be
        // unverified, and the wipe would not run at all.)
        mockkObject(WalletWipeState)
        try {
            every { WalletWipeState.complete(any()) } just Runs
            assertTrue(wipe { })
        } finally {
            unmockkObject(WalletWipeState)
        }
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(WalletWipeState.isPending(directory.root, noBackupDir))

        assertTrue(wipe { assertTrue(backup.delete()) })
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        assertFalse(wipeApp.isWalletLoadDegraded)
        assertNull(wipeApp.wallet)
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        assertFalse(controller.get().redirectDegradedWallet(wipeApp))
        controller.destroy()
        Unit
    }

    @Test
    fun `wipe stays pending until the wiped wallet's recovery marker is removed`() = runBlocking {
        // The recovered wallet's reset is still owed when the user resets it.
        setField("walletFile", File(directory.root, "missing/primary"))
        try {
            app.persistRecoveredWallet(recovered)
            fail("save must fail")
        } catch (expected: IOException) {
            assertTrue(app.isWalletLoadDegraded)
        }
        val wipeApp = spyk(app)
        every { wipeApp.filesDir } returns directory.root
        every { wipeApp.noBackupFilesDir } returns noBackupDir
        // As finishWalletWipe does: the sequence, then the stopped-wipe verdict.
        suspend fun wipe(): Boolean = WalletWipeSequence.finish(
            pending = { WalletWipeState.isPending(directory.root, noBackupDir) },
            detachWallet = { ReflectionHelpers.setField(wipeApp, "wallet", null) },
            destroy = { },
            markComplete = { wipeApp.markWalletWipeComplete() }
        ).also { wipeApp.recordWalletWipeStopped() }

        // A nonempty directory at the recovery marker path cannot be deleted.
        val recoveryMarker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        val child = File(recoveryMarker, "block-delete").apply {
            parentFile!!.mkdirs()
            writeText("test")
        }
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        assertTrue(wipe())
        assertTrue("the next launch must retry", WalletWipeState.isPending(directory.root, noBackupDir))
        assertTrue(recoveryMarker.exists())
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(ReflectionHelpers.getField<Boolean>(wipeApp, "recoveredWalletPersistencePending"))

        // Without the recovered-wallet guard, the unfinished wipe alone keeps
        // onboarding off create/restore.
        ReflectionHelpers.setField(wipeApp, "recoveredWalletPersistencePending", false)
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(wipeApp.isWalletWipeRecoveryRequired)
        ReflectionHelpers.setField(wipeApp, "recoveredWalletPersistencePending", true)

        assertTrue(child.delete())
        assertTrue(wipe())
        assertFalse(recoveryMarker.exists())
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        assertFalse(ReflectionHelpers.getField<Boolean>(wipeApp, "recoveredWalletPersistencePending"))
        assertFalse(wipeApp.isWalletLoadDegraded)
        assertFalse(wipeApp.isWalletWipeRecoveryRequired)
        Unit
    }

    @Test
    fun `a wipe whose key backup survives keeps its marker and retries instead of recovering`() = runBlocking {
        // The wiped wallet's primary is on disk; its backup cannot be deleted
        // (a nonempty directory at its path), as when delete() fails.
        recovered.saveToFile(primary)
        assertTrue(backup.delete())
        val child = File(backup, "block-delete").apply {
            parentFile!!.mkdirs()
            writeText("test")
        }
        val wipeApp = spyk(app)
        every { wipeApp.filesDir } returns directory.root
        every { wipeApp.noBackupFilesDir } returns noBackupDir
        every { wipeApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns backup
        // As finishWalletWipe does: the sequence (failures caught), then the
        // stopped-wipe verdict; destroyWalletData ends with the confirmation.
        suspend fun wipe(): Result<Boolean> = runCatching {
            WalletWipeSequence.finish(
                pending = { WalletWipeState.isPending(directory.root, noBackupDir) },
                detachWallet = { ReflectionHelpers.setField(wipeApp, "wallet", null) },
                destroy = { wipeApp.confirmWalletSourcesDestroyed() },
                markComplete = { wipeApp.markWalletWipeComplete() }
            )
        }.also { wipeApp.recordWalletWipeStopped() }

        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        val failed = wipe()
        assertTrue("an unconfirmed backup deletion fails the wipe", failed.exceptionOrNull() is IOException)
        assertFalse("the primary is deleted", primary.exists())
        assertTrue(backup.exists())
        // onCreate checks this marker before any recoverable wallet file, so
        // the next launch re-runs the wipe rather than recovering the backup.
        assertTrue("the next launch must retry", WalletWipeState.isPending(directory.root, noBackupDir))
        assertTrue(wipeApp.isWalletWipeRecoveryRequired)
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(wipeApp.isWalletReplacementRefused)

        // The retry deletes the backup once it can be, and only then completes.
        assertTrue(child.delete())
        assertEquals(true, wipe().getOrThrow())
        assertFalse(backup.exists())
        assertFalse(primary.exists())
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        assertFalse(wipeApp.isWalletWipeRecoveryRequired)
        Unit
    }

    @Test
    fun `a backup of another network leaves the primary and recovery state untouched`() {
        // A self-consistent backup written with another network's parameters.
        val otherNetwork = org.bitcoinj.params.MainNetParams.get()
        assertNotEquals(Constants.NETWORK_PARAMETERS, otherNetwork)
        val otherContext = Context(otherNetwork)
        Wallet(otherContext).apply {
            freshReceiveKey()
            saveToFile(backup)
        }
        val originalPrimary = "UNREADABLE-PRIMARY".toByteArray()
        primary.writeBytes(originalPrimary)
        val recoveryMarker = File(directory.root, RecoveryResetState.MARKER_FILE_NAME)
        setField("walletFactory", mockk<WalletFactory> {
            every { getExtensions(any()) } returns emptyArray()
        })
        val recoveryApp = spyk(app)
        every { recoveryApp.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } answers { backup.inputStream() }
        every { recoveryApp.persistRecoveredWallet(any()) } answers { fail("must not persist another network's wallet") }

        // dashj's reader only rejects another network when the thread's
        // Context disagrees; a thread whose Context matches the backup (or a
        // reader that creates one implicitly) parses it, so the network check
        // must be the app's own.
        Context.propagate(otherContext)
        val restored = try {
            WalletApplication::class.java.getDeclaredMethod("restoreWalletFromBackup").run {
                isAccessible = true
                invoke(recoveryApp) as Wallet?
            }
        } finally {
            Context.propagate(Constants.CONTEXT)
        }

        assertNull(restored)
        assertArrayEquals(originalPrimary, primary.readBytes())
        assertFalse(recoveryMarker.exists())
        assertFalse(ReflectionHelpers.getField<Boolean>(recoveryApp, "recoveredWalletPersistencePending"))
        assertTrue("an unusable backup latches seed recovery", recoveryApp.isWalletRecoveryFromSeedNeeded)
        verify(exactly = 0) { recoveryApp.persistRecoveredWallet(any()) }
    }

    /** Where the app writes its key backup (the name carries the network suffix). */
    private val keyBackup get() = File(directory.root, Constants.Files.WALLET_KEY_BACKUP_PROTOBUF)

    /** An app whose files dir is [directory] and whose key backup writes really go through AtomicFileWriter. */
    private fun backupWritingApp(): WalletApplication {
        val writingApp = spyk(app)
        every { writingApp.filesDir } returns directory.root
        every { writingApp.noBackupFilesDir } returns noBackupDir
        every { writingApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns keyBackup
        every { writingApp.openFileOutput(any(), any()) } answers {
            java.io.FileOutputStream(File(directory.root, firstArg<String>()))
        }
        return writingApp
    }

    private suspend fun finishWipe(wipeApp: WalletApplication): Boolean = WalletWipeSequence.finish(
        pending = { WalletWipeState.isPending(directory.root, noBackupDir) },
        detachWallet = { wipeApp.detachWalletForWipe() },
        destroy = {
            primary.delete()
            keyBackup.delete()
            wipeApp.confirmWalletSourcesDestroyed()
        },
        markComplete = { wipeApp.markWalletWipeComplete() }
    ).also { wipeApp.recordWalletWipeStopped() }

    @Test
    fun `startup maintenance paused before its backup write does not rewrite a wiped wallet's backup`() = runBlocking {
        recovered.saveToFile(primary)
        assertFalse("maintenance owes a missing backup", keyBackup.exists())
        val wipeApp = backupWritingApp()
        val paused = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        every { wipeApp.writeKeyBackupProto(any(), any()) } answers {
            paused.countDown()
            resume.await()
            callOriginal()
        }
        val maintenanceFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val maintenance = Thread {
            try {
                wipeApp.maintainKeyBackup(recovered)
            } catch (t: Throwable) {
                maintenanceFailure.set(t)
            }
        }
        maintenance.start()
        assertTrue(paused.await(5, java.util.concurrent.TimeUnit.SECONDS))

        // The user resets while maintenance holds a proto of the wallet.
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        resume.countDown()
        maintenance.join(5_000)
        assertTrue("the write is refused", maintenanceFailure.get() is IOException)
        assertFalse(keyBackup.exists())

        assertTrue(finishWipe(wipeApp))
        assertFalse(keyBackup.exists())
        assertFalse(primary.exists())
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        assertFalse(wipeApp.isWalletWipeRecoveryRequired)
        Unit
    }

    @Test
    fun `a backup write in flight finishes before the wipe detaches and destroys the wallet`() = runBlocking {
        recovered.saveToFile(primary)
        assertFalse(keyBackup.exists())
        val wipeApp = backupWritingApp()
        val writing = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        every { wipeApp.openFileOutput(any(), any()) } answers {
            writing.countDown()
            resume.await()
            java.io.FileOutputStream(File(directory.root, firstArg<String>()))
        }
        val writeFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                wipeApp.writeWalletBackup()
            } catch (t: Throwable) {
                writeFailure.set(t)
            }
        }
        writer.start()
        assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        val detach = Thread { wipeApp.detachWalletForWipe() }
        detach.start()
        detach.join(300)
        assertTrue("detaching waits for the write in flight", detach.isAlive)
        resume.countDown()
        detach.join(5_000)
        writer.join(5_000)
        assertFalse(detach.isAlive)
        writeFailure.get()?.let { throw AssertionError("the in-flight write must complete", it) }
        assertTrue(keyBackup.exists())
        assertNull(wipeApp.wallet)

        assertTrue(finishWipe(wipeApp))
        assertFalse(keyBackup.exists())
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        Unit
    }

    /** A replacement as create/restore builds it: a WalletEx with its own seed. */
    private fun replacementWallet(): WalletEx = WalletEx(
        Constants.NETWORK_PARAMETERS,
        KeyChainGroup.builder(Constants.NETWORK_PARAMETERS).fromRandom(Script.ScriptType.P2PKH).build()
    )

    /** A [backupWritingApp] that can also install a replacement through setWallet and run its setup. */
    private fun setupCapableApp(): WalletApplication {
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        setField("cutoverCoordinator", mockk<CutoverCoordinator>(relaxed = true))
        val setupApp = backupWritingApp()
        every { setupApp.finalizeInitialization() } returns Unit
        return setupApp
    }

    private fun readWallet(file: File): Wallet = file.inputStream().use { WalletProtobufSerializer().readWallet(it) }

    /**
     * The reviewer's ordering (PR #1576): wallet A loads with its replacement
     * backup owed, and PIN recovery installs wallet B through setWallet
     * BEFORE startup maintenance chooses which wallet to back up. Neither B
     * (not saved yet) nor A (no longer the app's wallet) may be published,
     * and A's obligation must not be acknowledged; B's setup publishes B's
     * backup and clears its own generation.
     */
    @Test
    fun `a replacement installed before startup maintenance runs is neither backed up nor acknowledged`() {
        // A is the primary (committed in setUp) beside the previous wallet's backup, its own still owed.
        recovered.saveToFile(primary)
        Wallet(Constants.NETWORK_PARAMETERS).apply { freshReceiveKey() }.saveToFile(keyBackup)
        val previousBackup = keyBackup.readBytes()
        val owedA = BackupReplacementState.arm(directory.root)
        val primaryA = primary.readBytes()
        val setupApp = setupCapableApp()

        val replacementB = replacementWallet()
        val keyB = replacementB.freshReceiveKey()
        assertTrue(setupApp.setWallet(replacementB))
        assertSame(replacementB, setupApp.wallet)

        setupApp.maintainKeyBackup(recovered)

        verify(exactly = 0) { setupApp.writeKeyBackupProto(any(), any()) }
        assertArrayEquals("nothing is published", previousBackup, keyBackup.readBytes())
        assertEquals("A's obligation is not acknowledged", owedA.content, replacementMarker.readText())
        assertArrayEquals("the primary still holds A", primaryA, primary.readBytes())

        // B is not backed up from any other path either until its primary is saved.
        try {
            setupApp.writeWalletBackup()
            fail("an unsaved replacement must not be backed up")
        } catch (expected: WalletApplication.UncommittedWalletBackupException) {
            // Nothing written.
        }
        setupApp.backupWallet()
        assertArrayEquals(previousBackup, keyBackup.readBytes())
        assertEquals(owedA.content, replacementMarker.readText())

        // B commits: primary, then backup, then B's own generation clears the marker.
        setupApp.saveWalletAndFinalizeInitialization()
        assertTrue(readWallet(primary).isPubKeyMine(keyB.pubKey))
        assertTrue(readWallet(keyBackup).isPubKeyMine(keyB.pubKey))
        assertFalse(replacementMarker.exists())
        verify(exactly = 1) { setupApp.finalizeInitialization() }
    }

    /**
     * B is installed after maintenance decided to repair A but before A's
     * backup is published: the publish-time check refuses it, so the
     * backup and A's marker stay as they were.
     */
    @Test
    fun `a replacement installed while startup maintenance builds the backup refuses the publish`() {
        recovered.saveToFile(primary)
        Wallet(Constants.NETWORK_PARAMETERS).apply { freshReceiveKey() }.saveToFile(keyBackup)
        val previousBackup = keyBackup.readBytes()
        val owedA = BackupReplacementState.arm(directory.root)
        val setupApp = setupCapableApp()
        val replacementB = replacementWallet()
        every { setupApp.writeKeyBackupProto(any(), any()) } answers {
            assertTrue(setupApp.setWallet(replacementB))
            callOriginal()
        }

        setupApp.maintainKeyBackup(recovered)

        verify(exactly = 1) { setupApp.writeKeyBackupProto(recovered, any()) }
        assertArrayEquals(previousBackup, keyBackup.readBytes())
        assertEquals(owedA.content, replacementMarker.readText())
    }

    @Test
    fun `a first install still writes its key backup`() {
        ReflectionHelpers.setField(app, "wallet", null)
        ReflectionHelpers.setField(app, "committedWallet", null)
        assertFalse(keyBackup.exists())
        val setupApp = setupCapableApp()
        val created = replacementWallet()
        val key = created.freshReceiveKey()

        assertTrue(setupApp.setWallet(created))
        setupApp.saveWalletAndFinalizeInitialization()

        assertTrue(readWallet(primary).isPubKeyMine(key.pubKey))
        assertTrue(readWallet(keyBackup).isPubKeyMine(key.pubKey))
        assertFalse(replacementMarker.exists())

        // Its startup maintenance (bound to it) then finds nothing owed.
        val written = keyBackup.readBytes()
        setupApp.maintainKeyBackup(created)
        assertArrayEquals(written, keyBackup.readBytes())
    }

    @Test
    fun `backupWallet writes the committed wallet's backup`() {
        recovered.saveToFile(primary)
        assertFalse(keyBackup.exists())
        val writingApp = backupWritingApp()

        writingApp.backupWallet()

        assertTrue(readWallet(keyBackup).isPubKeyMine(recovered.currentReceiveKey().pubKey))
    }

    /** A fresh process on the same files: nothing in memory, only what is on disk. */
    private fun restartedApp(): WalletApplication {
        val fresh = WalletApplication()
        fun set(name: String, value: Any) = ReflectionHelpers.setField(fresh, name, value)
        set("walletFile", primary)
        set("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        set("walletFactory", mockk<WalletFactory> { every { getExtensions(any()) } returns emptyArray() })
        val launchApp = spyk(fresh)
        every { launchApp.filesDir } returns directory.root
        every { launchApp.noBackupFilesDir } returns noBackupDir
        every { launchApp.getFileStreamPath(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } returns keyBackup
        every { launchApp.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) } answers { keyBackup.inputStream() }
        every { launchApp.openFileOutput(any(), any()) } answers {
            java.io.FileOutputStream(File(directory.root, firstArg<String>()))
        }
        every { launchApp.finalizeInitialization() } returns Unit
        every { launchApp.resetBlockchain() } answers { fail("must not queue a reset") }
        every { launchApp["showLoadToast"](any<String>()) } answers { }
        return launchApp
    }

    /**
     * Wallet A is committed with its key backup. Existing-wallet PIN recovery
     * installs wallet B through setWallet() and, before PIN setup runs, the
     * blockchain service's shutdown save (an ordinary saveWallet()) writes
     * B's primary. The process then dies. B's backup must be owed on disk,
     * so the restart neither trusts A's backup as B's recovery input nor
     * skips the repair (review, PR #1576).
     */
    @Test
    fun `an ordinary save of an installed replacement owes its backup across a restart`() {
        recovered.saveToFile(primary)
        val setupApp = setupCapableApp()
        setupApp.backupWallet()
        val backupA = keyBackup.readBytes()
        assertTrue(readWallet(keyBackup).isPubKeyMine(recovered.currentReceiveKey().pubKey))
        assertFalse(replacementMarker.exists())

        val replacementB = replacementWallet()
        val keyB = replacementB.freshReceiveKey()
        assertTrue(setupApp.setWallet(replacementB))
        // As saveWalletOnShutdown() does: the app is not degraded, so it saves.
        assertFalse(setupApp.isWalletLoadDegraded)
        setupApp.saveWallet()

        assertTrue("B's primary is on disk", readWallet(primary).isPubKeyMine(keyB.pubKey))
        assertArrayEquals("A's backup is still in place", backupA, keyBackup.readBytes())
        assertTrue("B's backup is owed", replacementMarker.exists())
        val armed = replacementMarker.readText()
        // Further saves of the now-committed B do not re-arm.
        setupApp.saveWallet()
        assertEquals(armed, replacementMarker.readText())

        // Restart 1: B's primary fails to load before any repair. A's backup
        // must not be accepted (and persisted) as the wallet.
        val primaryB = primary.readBytes()
        primary.writeBytes(primaryB.copyOf(primaryB.size / 2))
        val failedLaunch = restartedApp()
        every { failedLaunch.persistRecoveredWallet(any()) } answers { fail("must not persist A's backup") }
        loadWallet(failedLaunch)
        assertNull(failedLaunch.wallet)
        assertTrue(failedLaunch.isWalletRecoveryFromSeedNeeded)
        verify(exactly = 0) { failedLaunch.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF) }
        assertArrayEquals(backupA, keyBackup.readBytes())
        assertTrue(replacementMarker.exists())

        // Restart 2: B's primary loads; maintenance rewrites the backup from
        // B and only then clears the marker.
        primary.writeBytes(primaryB)
        val launchApp = restartedApp()
        loadWallet(launchApp)
        val loadedB = requireNotNull(launchApp.wallet)
        assertTrue(loadedB.isPubKeyMine(keyB.pubKey))
        launchApp.maintainKeyBackup(loadedB)
        assertTrue(readWallet(keyBackup).isPubKeyMine(keyB.pubKey))
        assertFalse(readWallet(keyBackup).isPubKeyMine(recovered.currentReceiveKey().pubKey))
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `saving the committed wallet does not owe a replacement backup`() {
        val setupApp = setupCapableApp()
        setupApp.saveWallet()
        setupApp.saveWallet()
        assertTrue(primary.exists())
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `setup after an ordinary save of its replacement supersedes that arm and clears the marker`() {
        recovered.saveToFile(primary)
        val setupApp = setupCapableApp()
        setupApp.backupWallet()
        val replacementB = replacementWallet()
        val keyB = replacementB.freshReceiveKey()
        assertTrue(setupApp.setWallet(replacementB))
        setupApp.saveWallet()
        assertTrue(replacementMarker.exists())

        setupApp.saveWalletAndFinalizeInitialization()

        assertTrue(readWallet(keyBackup).isPubKeyMine(keyB.pubKey))
        assertFalse(replacementMarker.exists())
        verify(exactly = 1) { setupApp.finalizeInitialization() }
    }

    @Test
    fun `an ordinary save of a replacement is refused when its backup obligation cannot be recorded`() {
        recovered.saveToFile(primary)
        val primaryA = primary.readBytes()
        val setupApp = setupCapableApp()
        // A non-empty directory where the marker goes: the arm's rename fails.
        replacementMarker.mkdir()
        File(replacementMarker, "child").writeText("")
        val replacementB = replacementWallet()
        assertTrue(setupApp.setWallet(replacementB))

        try {
            setupApp.saveWallet()
            fail("the save must fail without its marker")
        } catch (expected: RuntimeException) {
            assertTrue(expected.cause is IOException)
        }
        assertArrayEquals("the primary still holds A", primaryA, primary.readBytes())
        // B was not committed, so it still cannot be backed up.
        try {
            setupApp.writeWalletBackup()
            fail("an unsaved replacement must not be backed up")
        } catch (expected: WalletApplication.UncommittedWalletBackupException) {
            // Nothing written.
        }
    }

    @Test
    fun `persisting a wallet recovered from the backup owes no replacement backup`() {
        ReflectionHelpers.setField(app, "committedWallet", null)
        app.persistRecoveredWallet(recovered)
        assertTrue(primary.exists())
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `a wipe that stops with an unverified marker keeps onboarding off create and restore`() {
        val wipeApp = spyk(app)
        every { wipeApp.filesDir } returns directory.root
        every { wipeApp.noBackupFilesDir } returns noBackupDir
        assertTrue(WalletWipeState.begin(directory.root, noBackupDir))
        // The marker is left unreadable (here: a directory at its path), so it
        // can neither authorize finishing the wipe nor be treated as absent.
        val marker = File(directory.root, WalletWipeState.MARKER_FILE_NAME)
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        assertEquals(
            WalletWipeState.State.RECOVERY_REQUIRED,
            WalletWipeState.inspect(directory.root, noBackupDir)
        )

        wipeApp.recordWalletWipeStopped()
        assertTrue(wipeApp.isWalletWipeRecoveryRequired)
        assertTrue(wipeApp.isWalletLoadDegraded)
        val originalBackup = backup.readBytes()
        wipeApp.fullInitialization()
        assertArrayEquals(originalBackup, backup.readBytes())
        assertTrue(marker.isDirectory)

        // Only a marker that is actually gone clears it.
        assertTrue(marker.delete())
        wipeApp.recordWalletWipeStopped()
        assertFalse(wipeApp.isWalletWipeRecoveryRequired)
        assertFalse(wipeApp.isWalletLoadDegraded)
    }
}
