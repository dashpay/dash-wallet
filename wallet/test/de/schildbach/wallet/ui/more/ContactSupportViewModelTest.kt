/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.ReportParityBreakdown
import de.schildbach.wallet.service.platform.sdk.StoredParityBreakdown
import de.schildbach.wallet.service.platform.sdk.parityBreakdownReportSection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The support report's wallet acquisition. The recovery screen opens the report
 * on the main thread while a safe-mode retry may be loading the wallet, and
 * reading the wallet then waits for the retry's parse.
 */
class ContactSupportViewModelTest {

    private fun viewModel(
        application: WalletApplication,
        l1ShadowSyncService: L1ShadowSyncService = mockk(relaxed = true),
        packageInfoProvider: PackageInfoProvider = mockk(relaxed = true)
    ) = ContactSupportViewModel(
        configuration = mockk(relaxed = true),
        application = application,
        packageInfoProvider = packageInfoProvider,
        transactionMetadataDocumentDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        dashjDiagnosticSyncState = mockk(relaxed = true),
        l1ShadowSyncService = l1ShadowSyncService,
        sdkService = mockk(relaxed = true)
    )

    @Test
    fun safeModeRetryInProgress_takesNoWallet_andNeverReadsIt() {
        val application = mockk<WalletApplication> {
            every { isSafeModeRetryInProgress } returns true
            every { wallet } throws AssertionError("getWallet() would wait for the retry's parse")
        }

        val viewModel = viewModel(application)

        assertNull(viewModel.wallet)
        verify(exactly = 0) { application.wallet }
    }

    @Test
    fun degradedButNotRetrying_withAWalletAssigned_takesIt() {
        // publish or finalizeInitialization failed after the wallet was set:
        // the crash report wants that wallet.
        val assigned = mockk<Wallet>()
        val application = mockk<WalletApplication> {
            every { isSafeModeRetryInProgress } returns false
            every { isWalletLoadDegraded } returns true
            every { wallet } returns assigned
        }

        assertSame(assigned, viewModel(application).wallet)
    }

    @Test
    fun normalLaunch_takesTheLoadedWallet() {
        val loaded = mockk<Wallet>()
        val application = mockk<WalletApplication> {
            every { isSafeModeRetryInProgress } returns false
            every { isWalletLoadDegraded } returns false
            every { wallet } returns loaded
        }

        assertSame(loaded, viewModel(application).wallet)
    }

    /**
     * The report's re-read runs on the main thread, where a retry starts, so
     * no retry can start between the "is a retry running" check and the
     * wallet read (review, #1594: on IO one could, and the read would then
     * wait for the whole parse).
     */
    private fun onMain(mainThread: Thread) = if (Thread.currentThread() === mainThread) "main" else "off-main"

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reportSnapshot_readsTheWalletOnTheMainThread() {
        lateinit var mainThread: Thread
        val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, "fake-main").also { t -> mainThread = t } }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val readThreads = Collections.synchronizedList(ArrayList<String>())
            val application = mockk<WalletApplication> {
                every { isSafeModeRetryInProgress } answers {
                    readThreads.add("retry:" + onMain(mainThread))
                    false
                }
                every { wallet } answers {
                    readThreads.add("wallet:" + onMain(mainThread))
                    null
                }
                every { isWalletLoadDegraded } answers {
                    readThreads.add("degraded:" + onMain(mainThread))
                    true
                }
            }
            // Created on the main thread, as the dialog does.
            val viewModel = mainExecutor.submit<ContactSupportViewModel> { viewModel(application) }.get()
            readThreads.clear()

            runBlocking(Dispatchers.IO) { viewModel.snapshotWallet() }

            assertEquals(listOf("retry:main", "wallet:main", "degraded:main"), readThreads)
        } finally {
            Dispatchers.resetMain()
            mainDispatcher.close()
        }
    }

    @Test
    fun parityLog_isAttachedWhenTheDiagnosticRan_orABreakdownApplies() {
        val applies = ReportParityBreakdown(null, "SDK not synced")
        val notApplicable = ReportParityBreakdown.NOT_APPLICABLE
        // Committed cutover: a breakdown applies even with the diagnostic off.
        assertTrue(shouldAttachParityLog(diagnosticEnabled = false, hasParityHistory = false, breakdown = applies))
        assertTrue(shouldAttachParityLog(diagnosticEnabled = true, hasParityHistory = false, breakdown = notApplicable))
        assertTrue(shouldAttachParityLog(diagnosticEnabled = false, hasParityHistory = true, breakdown = notApplicable))
        // Before the cutover, diagnostic off and never run: no parity log.
        assertFalse(shouldAttachParityLog(diagnosticEnabled = false, hasParityHistory = false, breakdown = notApplicable))
    }

    /**
     * The breakdown lists txids and amounts. With "Append application log"
     * (and "Append wallet dump") unticked it is not computed, the parity
     * log's section reads "omitted", and it does not attach the parity log
     * on its own — even though the cutover is committed and a fresh
     * breakdown would apply.
     */
    @Test
    fun applicationLogNotShared_breakdownIsNeitherComputedNorAttached() = runBlocking {
        val applicable = ReportParityBreakdown(
            StoredParityBreakdown("cd".repeat(32), 42L, "ParityBreakdown … 2222…2222 net=-5"),
            notRefreshedReason = null
        )
        val l1 = mockk<L1ShadowSyncService> {
            coEvery { parityBreakdownForReport(any()) } returns applicable
        }
        val application = mockk<WalletApplication>(relaxed = true) {
            every { isSafeModeRetryInProgress } returns false
        }
        val viewModel = viewModel(application, l1)

        val notShared = viewModel.parityBreakdownForReport(collectApplicationLog = false)
        assertEquals(ReportParityBreakdown.NOT_SHARED, notShared)
        coVerify(exactly = 0) { l1.parityBreakdownForReport(any()) }
        // Diagnostic off, never ran: no parity log at all.
        assertFalse(shouldAttachParityLog(diagnosticEnabled = false, hasParityHistory = false, breakdown = notShared))
        // Diagnostic on: the parity log carries its totals as before, and the
        // breakdown section says why it is missing.
        assertTrue(shouldAttachParityLog(diagnosticEnabled = true, hasParityHistory = false, breakdown = notShared))
        val section = parityBreakdownReportSection(notShared) { "unused" }
        assertEquals("\n--- latest parity breakdown ---\nomitted (application log not shared)\n", section)

        // Application log shared: computed and attached.
        val shared = viewModel.parityBreakdownForReport(collectApplicationLog = true)
        assertEquals(applicable, shared)
        coVerify(exactly = 1) { l1.parityBreakdownForReport(any()) }
        assertTrue(shouldAttachParityLog(diagnosticEnabled = false, hasParityHistory = false, breakdown = shared))
    }

    // ── The report never takes the app down ──────────────────────────────

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun reportSection_keepsARuntimeFailure_andReturnsTheFallback() {
        val failures = ArrayList<Throwable>()
        val result = reportSection("application info", { e -> failures.add(e); "fallback" }) {
            throw IllegalStateException("wallet walk failed")
        }
        assertEquals("fallback", result)
        assertEquals(IllegalStateException::class.java, failures.single().javaClass)
    }

    @Test
    fun reportSection_keepsAnOutOfMemoryError() {
        val result = reportSection("wallet dump", { "dropped" }) { throw OutOfMemoryError() }
        assertEquals("dropped", result)
    }

    @Test
    fun reportSection_rethrowsCancellation() {
        try {
            reportSection("logs", { fail("cancellation must not be kept") }) {
                throw CancellationException("dialog closed")
            }
            fail("cancellation was swallowed")
        } catch (expected: CancellationException) {
            // rethrown
        }
    }

    @Test
    fun reportSection_passesTheResultThrough() {
        assertEquals(42, reportSection("x", { -1 }) { 42 })
    }

    @Test
    fun failureLines_nameTheSectionAndClass_butNotTheMessage() {
        val failure = IllegalArgumentException("/data/user/0/secret/path")
        assertEquals("\n[device info not collected: IllegalArgumentException]\n", reportSectionFailureLine("device info", failure))
        val fallback = fallbackReportText("Sends fail", failure)
        assertTrue(fallback.startsWith("Sends fail"))
        assertTrue(fallback.contains("report generation failed"))
        assertFalse(fallback.contains("secret"))
    }

    /**
     * Regression: a runtime exception in a section (here the application info,
     * which walks the dashj wallet) escaped createReport, and with it the
     * dialog's coroutine — the app crashed and relaunched to the home screen
     * with no chooser. Now the section is dropped with a note and the report
     * still comes back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailingSection_stillProducesTheReport() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val application = mockk<WalletApplication>(relaxed = true) {
                every { isSafeModeRetryInProgress } returns false
                every { isWalletLoadDegraded } returns false
                every { wallet } returns null
                every { cacheDir } returns tmp.root
            }
            val packageInfoProvider = mockk<PackageInfoProvider>(relaxed = true) {
                every { packageInfo } throws IllegalStateException("package manager died")
            }
            val viewModel = viewModel(application, packageInfoProvider = packageInfoProvider)
            viewModel.stackTrace = "the stack"

            val (text, attachments) = runBlocking {
                viewModel.createReportOrFallback(
                    "Sends fail",
                    collectDeviceInfo = false,
                    collectInstalledPackages = false,
                    collectApplicationLog = false,
                    collectWalletDump = false
                )
            }

            assertTrue(text.startsWith("Sends fail"))
            assertTrue(text.contains("[application info not collected: "))
            // The sections after the failing one still ran.
            assertTrue(text.contains("=== stack trace ===\n\nthe stack"))
            assertFalse(text.contains("report generation failed"))
            assertTrue(attachments.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** The backstop: createReport failing outright still yields a report to send. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anOutrightFailure_sendsTheDescriptionWithTheReason() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val application = mockk<WalletApplication>(relaxed = true) {
                every { isSafeModeRetryInProgress } returns false
                every { isWalletLoadDegraded } returns false
                every { wallet } returns null
                every { cacheDir } throws SecurityException("no cache dir")
            }
            val viewModel = viewModel(application)

            val (text, attachments) = runBlocking {
                viewModel.createReportOrFallback(
                    "Sends fail",
                    collectDeviceInfo = false,
                    collectInstalledPackages = false,
                    collectApplicationLog = false,
                    collectWalletDump = false
                )
            }

            assertTrue(text.startsWith("Sends fail"))
            assertTrue(text.contains("[report not collected: SecurityException]"))
            assertTrue(attachments.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
