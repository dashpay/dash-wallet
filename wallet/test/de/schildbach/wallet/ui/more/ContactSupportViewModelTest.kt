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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The support report's wallet acquisition. The recovery screen opens the report
 * on the main thread while a safe-mode retry may be loading the wallet, and
 * reading the wallet then waits for the retry's parse.
 */
class ContactSupportViewModelTest {

    private fun viewModel(application: WalletApplication) = ContactSupportViewModel(
        configuration = mockk(relaxed = true),
        application = application,
        packageInfoProvider = mockk(relaxed = true),
        transactionMetadataDocumentDao = mockk(relaxed = true),
        dashPayConfig = mockk(relaxed = true),
        dashjDiagnosticSyncState = mockk(relaxed = true),
        l1ShadowSyncService = mockk(relaxed = true),
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
}
