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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletApplication.WalletInstallResult
import de.schildbach.wallet.service.WalletFactory
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import io.mockk.verify
import kotlinx.coroutines.cancel
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RestoreWalletFromFileViewModelTest {
    private val uri = Uri.parse("content://backup/keys.txt")
    private val wallet = mockk<Wallet>(relaxed = true) { every { hasKeyChain(any()) } returns true }
    private val factory = mockk<WalletFactory> { every { restoreFromFile(any(), uri, any()) } returns (wallet to true) }
    private val configuration = mockk<Configuration>(relaxed = true)

    @Test
    fun `a refused keys-file wallet arms no backup reminders for the existing wallet`() {
        val app = mockk<WalletApplication>(relaxed = true) { every { installWallet(any(), false) } returns WalletInstallResult.REFUSED }
        val viewModel = RestoreWalletFromFileViewModel(app, factory, configuration, mockk(relaxed = true))

        viewModel.restoreAndSettle(viewModel.restoreWalletFromUri(uri, ""))

        verify(exactly = 0) { configuration.armBackupReminder() }
        verify(exactly = 0) { configuration.armBackupSeedReminder() }
    }

    @Test
    fun `an accepted keys-file wallet arms its backup reminders`() {
        var installedOnMain: Boolean? = null
        val app = mockk<WalletApplication>(relaxed = true) {
            every { installWallet(any(), false) } answers {
                installedOnMain = Looper.myLooper() == Looper.getMainLooper()
                WalletInstallResult.INSTALLED
            }
        }
        val viewModel = RestoreWalletFromFileViewModel(app, factory, configuration, mockk(relaxed = true))

        viewModel.restoreAndSettle(viewModel.restoreWalletFromUri(uri, ""))

        verify(exactly = 1) { configuration.armBackupReminder() }
        verify(exactly = 1) { configuration.armBackupSeedReminder() }
        // PR #1700 review: the install can wait on the install lock, so it must not run on the main thread.
        assertEquals(false, installedOnMain)
    }

    @Test
    fun `a restore installed after its screen is gone still completes its bookkeeping`() {
        val done = CountDownLatch(1)
        lateinit var viewModel: RestoreWalletFromFileViewModel
        val app = mockk<WalletApplication>(relaxed = true) {
            every { installWallet(any(), false) } answers {
                // The activity finishes while the blocking install runs.
                viewModel.viewModelScope.cancel()
                WalletInstallResult.INSTALLED
            }
            every { resetBlockchainState() } answers { done.countDown() }
        }
        viewModel = RestoreWalletFromFileViewModel(app, factory, configuration, mockk(relaxed = true))
        var navigated = false
        viewModel.startActivityAction.observeForever { navigated = true }

        viewModel.restoreWallet(viewModel.restoreWalletFromUri(uri, ""), null)
        assertTrue("the bookkeeping never ran", done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()

        verify(exactly = 1) { configuration.armBackupReminder() }
        verify(exactly = 1) { configuration.armBackupSeedReminder() }
        assertFalse("no navigation once the screen is gone", navigated)
    }

    /** Restores, then pumps the main looper until the restore reports an outcome. */
    private fun RestoreWalletFromFileViewModel.restoreAndSettle(restored: Wallet) {
        var settled = false
        walletReplacementRefused.observeForever { settled = true }
        walletAlreadyInstalled.observeForever { settled = true }
        startActivityAction.observeForever { settled = true }
        restoreWallet(restored, null)
        val deadline = System.currentTimeMillis() + 5_000
        while (!settled) {
            check(System.currentTimeMillis() < deadline) { "the restore never reported an outcome" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
