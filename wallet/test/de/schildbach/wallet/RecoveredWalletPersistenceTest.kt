package de.schildbach.wallet

import android.app.Application
import android.app.Activity
import android.content.Intent
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.WalletUriHandlerActivity
import de.schildbach.wallet.ui.redirectDegradedWallet
import io.mockk.every
import io.mockk.spyk
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
        assertFalse(app.retryWalletLoadAfterSafeMode())
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

        setField("walletLoadSkippedSafeMode", true)
        val safeModeApp = spyk(app)
        every { safeModeApp.fullInitialization() } answers { }
        assertFalse(safeModeApp.retryWalletLoadAfterSafeMode())
        assertTrue(safeModeApp.isWalletLoadDegraded)
        setField("walletLoadSkippedSafeMode", false)

        app.persistRecoveredWallet(recovered)
        assertTrue(app.walletFileExists())
        assertFalse(app.isWalletLoadDegraded)
        val saved = primary.inputStream().use { WalletProtobufSerializer().readWallet(it) }
        assertEquals(recovered.currentReceiveAddress(), saved.currentReceiveAddress())
        assertArrayEquals(originalBackup, backup.readBytes())
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

    private fun setField(name: String, value: Any) {
        WalletApplication::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(app, value)
        }
    }
}
