package de.schildbach.wallet

import android.app.Application
import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.WalletUriHandlerActivity
import de.schildbach.wallet.ui.degradedScreenActions
import de.schildbach.wallet.ui.redirectDegradedWallet
import de.schildbach.wallet.util.SafeModeRetryWaiters
import io.mockk.every
import io.mockk.spyk
import io.mockk.mockk
import io.mockk.verify
import de.schildbach.wallet.service.WalletFactory
import org.bitcoinj.core.Context
import org.bitcoinj.wallet.Wallet
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
        // A failed primary and backup load latched seed recovery; a Reset
        // Wallet then stopped with its marker on disk (review, PR #1576). The
        // recovered-wallet persistence latch is NOT set: the wipe guard alone
        // must refuse the replacement.
        ReflectionHelpers.setField(app, "wallet", null)
        setField("walletLoadFailed", true)
        setField("walletRecoveryFromSeedNeeded", true)
        setField("walletWipeIncomplete", true)
        setField("recoveredWalletPersistencePending", false)
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
        assertFalse(ReflectionHelpers.getField<Boolean>(app, "recoveredWalletPersistencePending"))
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
        every { maintenanceApp.writeWalletBackup() } answers {
            if (failWrite()) throw IOException("injected backup failure")
            recovered.saveToFile(backup)
        }
        return maintenanceApp
    }

    @Test
    fun `startup maintenance rewrites the backup a replacement still owes, then clears the marker`() {
        replacementMarker.createNewFile()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup()

        verify(exactly = 1) { maintenanceApp.writeWalletBackup() }
        assertFalse("cleared only after the rewrite", replacementMarker.exists())
    }

    @Test
    fun `startup maintenance leaves a present backup alone when nothing is owed`() {
        val previousBackup = backup.readBytes()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup()

        // No parse of the backup and no second wallet: nothing is read at all.
        verify(exactly = 0) { maintenanceApp.writeWalletBackup() }
        verify(exactly = 0) { maintenanceApp.openFileInput(any()) }
        assertArrayEquals(previousBackup, backup.readBytes())
    }

    @Test
    fun `startup maintenance writes a missing backup without any marker`() {
        backup.delete()
        val maintenanceApp = maintenanceApp()

        maintenanceApp.maintainKeyBackup()

        verify(exactly = 1) { maintenanceApp.writeWalletBackup() }
        assertTrue(backup.exists())
        assertFalse(replacementMarker.exists())
    }

    @Test
    fun `a failed backup rewrite keeps the replacement marker`() {
        replacementMarker.createNewFile()
        val maintenanceApp = maintenanceApp(failWrite = { true })

        try {
            maintenanceApp.maintainKeyBackup()
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
            maintenanceApp.maintainKeyBackup()
            verify(exactly = 1) { maintenanceApp.writeWalletBackup() }
        } finally {
            BackupReplacementState.confirmAbsent = original
        }
    }

    @Test
    fun `replacement setup owes its backup before the primary save and clears it after the backup`() {
        setField("config", mockk<org.dash.wallet.common.Configuration>(relaxed = true))
        val setupApp = spyk(app)
        var failBackup = true
        every { setupApp.saveWallet() } answers {
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
        verify(exactly = 0) { setupApp.saveWallet() }
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

        // A nonempty directory at the marker path makes marker removal fail.
        val marker = File(directory.root, WalletWipeState.MARKER_FILE_NAME)
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        val child = File(marker, "block-delete").apply { writeText("test") }
        assertTrue(wipe { })
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(WalletWipeState.isPending(directory.root, noBackupDir))
        assertTrue(child.delete())

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
        ReflectionHelpers.setField(wipeApp, "recoveredWalletPersistencePending", true)

        assertTrue(child.delete())
        assertTrue(wipe())
        assertFalse(recoveryMarker.exists())
        assertFalse(WalletWipeState.isPending(directory.root, noBackupDir))
        assertFalse(ReflectionHelpers.getField<Boolean>(wipeApp, "recoveredWalletPersistencePending"))
        assertFalse(wipeApp.isWalletLoadDegraded)
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
                wipeApp.maintainKeyBackup()
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
}
