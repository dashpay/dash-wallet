package de.schildbach.wallet

import android.app.Application
import de.schildbach.wallet.util.SafeModeRetryWaiters
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
        assertEquals(false, retryResult(app))
        assertArrayEquals(originalBackup, backup.readBytes())

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
    fun `new unsaved onboarding wallet does not activate recovery guard`() {
        setField("wallet", Wallet(Constants.NETWORK_PARAMETERS))
        assertFalse(app.walletFileExists())
        assertFalse(app.isWalletLoadDegraded)
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
}
