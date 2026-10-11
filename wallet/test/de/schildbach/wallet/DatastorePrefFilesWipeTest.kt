package de.schildbach.wallet

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import org.bitcoinj.core.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Reset Wallet's raw datastore sweep ([WalletApplication.clearDatastorePrefFiles])
 * must leave the Kotlin SDK's secrets DataStore to the later, post-SDK-cascade
 * erase step (SdkWalletSecretsWipe): it is a live, SDK-owned instance, and it
 * carries the device-scoped false-locked Keystore defect record that should
 * survive a reset when no wallet secret remains (MO-1023). That step's
 * last-resort raw delete ([WalletApplication.deleteSdkSecretsDatastoreFile])
 * must report success only once the file is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class DatastorePrefFilesWipeTest {
    private lateinit var context: android.content.Context
    private lateinit var app: WalletApplication
    private val folder get() = File(context.filesDir, Constants.Files.DATASTORE_PREFS_DIRECTORY)

    @Before
    fun setUp() {
        Context.propagate(Constants.CONTEXT)
        context = ApplicationProvider.getApplicationContext()
        folder.deleteRecursively()
        folder.mkdirs()
        app = spyk(WalletApplication())
        every { app.filesDir } answers { context.filesDir }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    @Test
    fun `the SDK secrets datastore survives while other files are swept`() {
        val sdkSecrets = File(folder, WalletApplication.SDK_SECRETS_DATASTORE_FILE).apply { writeText("defect") }
        val stale = File(folder, "coinjoin.preferences_pb").apply { writeText("stale") }
        val live = File(folder, "dashpay.preferences_pb").apply { writeText("api-cleared") }

        app.clearDatastorePrefFiles(setOf(live.name))

        assertTrue("the SDK-owned secrets DataStore is left to the post-cascade erase", sdkSecrets.exists())
        assertFalse("a file with no live instance is still swept", stale.exists())
        assertTrue("an api-cleared live file is left to its API", live.exists())
    }

    @Test
    fun `the last-resort raw delete removes the SDK secrets file and confirms it`() {
        val sdkSecrets = File(folder, WalletApplication.SDK_SECRETS_DATASTORE_FILE).apply { writeText("secrets") }

        assertTrue(app.deleteSdkSecretsDatastoreFile())
        assertFalse(sdkSecrets.exists())
        assertTrue("an already-absent file counts as deleted", app.deleteSdkSecretsDatastoreFile())
    }

    @Test
    fun `the last-resort raw delete reports failure when the file survives`() {
        // A non-empty directory under the file's name cannot be deleted.
        val blocker = File(folder, WalletApplication.SDK_SECRETS_DATASTORE_FILE).apply { mkdirs() }
        File(blocker, "child").writeText("x")

        assertFalse(app.deleteSdkSecretsDatastoreFile())
        assertTrue(blocker.exists())
    }
}
