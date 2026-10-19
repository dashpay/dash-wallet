package de.schildbach.wallet

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.util.AtomicFileWriter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import org.bitcoinj.core.Context
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Startup's obsolete-file sweep deletes every "*.tmp" in the files dir, which
 * includes the key backup's in-flight [AtomicFileWriter] temp. A sweep that
 * deletes it mid-write fails the write, and the startup repair of an owed
 * replacement backup is not retried this session (review, PR #1576).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class StartupFileCleanupTest {
    private lateinit var context: android.content.Context
    private lateinit var app: WalletApplication
    private val backupName = Constants.Files.WALLET_KEY_BACKUP_PROTOBUF
    private val backup get() = File(context.filesDir, backupName)
    private val backupTemp get() = File(context.filesDir, backupName + AtomicFileWriter.TEMP_SUFFIX)

    private class AfterLoadWalletReached : RuntimeException()

    @Before
    fun setUp() {
        Context.propagate(Constants.CONTEXT)
        context = ApplicationProvider.getApplicationContext()
        backup.delete()
        backupTemp.delete()
        app = spyk(WalletApplication(), recordPrivateCalls = true)
        every { app.filesDir } answers { context.filesDir }
        every { app.fileList() } answers { context.fileList() }
    }

    @After
    fun tearDown() {
        unmockkStatic(SecurityGuard::class)
    }

    @Test
    fun `the temp sweep runs before afterLoadWallet starts the backup writers`() {
        mockkStatic(SecurityGuard::class)
        every { SecurityGuard.getInstance() } returns mockk(relaxed = true)
        ReflectionHelpers.setField(app, "config", mockk<Configuration>(relaxed = true))
        ReflectionHelpers.setField(app, "packageInfoProvider", mockk<PackageInfoProvider>(relaxed = true))
        ReflectionHelpers.setField(app, "dashSystemService", mockk<DashSystemService>(relaxed = true))
        ReflectionHelpers.setField(app, "wallet", Wallet(Constants.NETWORK_PARAMETERS))
        // Abandoned by a dead process: the sweep must have removed it by the
        // time afterLoadWallet() arms the autosave and the maintenance thread.
        backupTemp.writeText("abandoned")
        var sweptFirst = false
        every { app["afterLoadWallet"]() } answers {
            sweptFirst = !backupTemp.exists()
            throw AfterLoadWalletReached()
        }

        try {
            app.finalizeInitialization()
            fail("afterLoadWallet must be reached")
        } catch (expected: AfterLoadWalletReached) {
            // Stops finalizeInitialization() here; nothing after it is under test.
        }
        assertTrue("cleanupFiles() must run before afterLoadWallet()", sweptFirst)
    }

    @Test
    fun `a sweep waits for an in-flight backup write instead of deleting its temp`() {
        val content = "REPLACEMENT-BACKUP".toByteArray()
        val tempWritten = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writeFailure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                AtomicFileWriter.write(context, backupName) { out ->
                    out.write(content)
                    tempWritten.countDown()
                    releaseWrite.await()
                }
            } catch (t: Throwable) {
                writeFailure.set(t)
            }
        }
        writer.start()
        assertTrue(tempWritten.await(5, TimeUnit.SECONDS))
        assertTrue("the temp is on disk mid-write", backupTemp.exists())

        val sweeper = Thread { app.cleanupFiles() }
        sweeper.start()
        sweeper.join(300)
        assertTrue("the sweep must wait for the write", sweeper.isAlive)
        assertTrue("the in-flight temp must survive", backupTemp.exists())

        releaseWrite.countDown()
        writer.join(5_000)
        sweeper.join(5_000)
        assertFalse(sweeper.isAlive)
        writeFailure.get()?.let { throw AssertionError("the write must not fail", it) }
        assertArrayEquals(content, backup.readBytes())
        assertFalse(backupTemp.exists())
    }

    @Test
    fun `a sweep still removes an abandoned temp`() {
        backupTemp.writeText("abandoned")
        app.cleanupFiles()
        assertFalse(backupTemp.exists())
    }
}
