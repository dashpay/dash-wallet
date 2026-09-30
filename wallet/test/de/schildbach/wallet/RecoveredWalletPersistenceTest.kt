package de.schildbach.wallet

import android.app.Application
import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.WalletUriHandlerActivity
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
import de.schildbach.wallet.util.WalletWipeSequence
import de.schildbach.wallet.util.WalletWipeState
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RecoveredWalletPersistenceTest {
    @get:Rule val directory = TemporaryFolder()
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
        suspend fun wipe(destroy: suspend () -> Unit): Boolean = WalletWipeSequence.finish(
            pending = { WalletWipeState.isPending(directory.root) },
            detachWallet = { ReflectionHelpers.setField(wipeApp, "wallet", null) },
            destroy = destroy,
            markComplete = { wipeApp.markWalletWipeComplete() }
        )

        assertFalse(wipe { fail("unrequested wipe must not destroy data") })
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(WalletWipeState.begin(directory.root))
        try {
            wipe { throw IOException("injected destruction failure") }
            fail("wipe must fail")
        } catch (expected: IOException) {
            assertTrue(wipeApp.isWalletLoadDegraded)
            assertTrue(WalletWipeState.isPending(directory.root))
        }

        // A nonempty directory at the marker path makes marker removal fail.
        val marker = File(directory.root, WalletWipeState.MARKER_FILE_NAME)
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        val child = File(marker, "block-delete").apply { writeText("test") }
        assertTrue(wipe { })
        assertTrue(wipeApp.isWalletLoadDegraded)
        assertTrue(WalletWipeState.isPending(directory.root))
        assertTrue(child.delete())

        assertTrue(wipe { assertTrue(backup.delete()) })
        assertFalse(WalletWipeState.isPending(directory.root))
        assertFalse(wipeApp.isWalletLoadDegraded)
        assertNull(wipeApp.wallet)
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        assertFalse(controller.get().redirectDegradedWallet(wipeApp))
        controller.destroy()
        Unit
    }
}
