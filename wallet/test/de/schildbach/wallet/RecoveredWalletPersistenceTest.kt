package de.schildbach.wallet

import android.app.Application
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
    }

    private fun setField(name: String, value: Any) {
        WalletApplication::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(app, value)
        }
    }
}
