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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.WalletFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val app = mockk<WalletApplication>(relaxed = true) { every { setWallet(any()) } returns false }
        val viewModel = RestoreWalletFromFileViewModel(app, factory, configuration, mockk(relaxed = true))

        viewModel.restoreWallet(viewModel.restoreWalletFromUri(uri, ""), null)

        verify(exactly = 0) { configuration.armBackupReminder() }
        verify(exactly = 0) { configuration.armBackupSeedReminder() }
    }

    @Test
    fun `an accepted keys-file wallet arms its backup reminders`() {
        val app = mockk<WalletApplication>(relaxed = true) { every { setWallet(any()) } returns true }
        val viewModel = RestoreWalletFromFileViewModel(app, factory, configuration, mockk(relaxed = true))

        viewModel.restoreWallet(viewModel.restoreWalletFromUri(uri, ""), null)

        verify(exactly = 1) { configuration.armBackupReminder() }
        verify(exactly = 1) { configuration.armBackupSeedReminder() }
    }
}
