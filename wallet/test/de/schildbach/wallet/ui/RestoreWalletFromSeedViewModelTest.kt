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
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.util.MnemonicCodeExt
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.BlockchainServiceConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class RestoreWalletFromSeedViewModelTest {
    /** The existing wallet's persisted scan-start date, in seconds. */
    private val existingDateSecs = 1_600_000_000L

    /** The date the user picks on the restore screen, in seconds. */
    private val selectedDateSecs = 1_700_000_000L

    /** A real, DataStore-backed config holding the existing wallet's date. */
    private val blockchainServiceConfig by lazy {
        BlockchainServiceConfig(ApplicationProvider.getApplicationContext(), mockk(relaxed = true)).also {
            runBlocking { it.setWalletCreationDate(existingDateSecs) }
        }
    }

    private fun viewModel(app: WalletApplication, factory: WalletFactory, configuration: Configuration = mockk(relaxed = true)) =
        RestoreWalletFromSeedViewModel(
            app, factory, configuration, mockk(relaxed = true), mockk(relaxed = true), blockchainServiceConfig
        ).apply { setWalletCreationDate(selectedDateSecs * 1000) }

    private val words = "abandon ".repeat(11).plus("about").split(" ")

    @Before
    fun setUp() {
        mockkStatic(SecurityGuard::class)
        every { SecurityGuard.getInstance() } returns mockk(relaxed = true)
        mockkObject(MnemonicCodeExt.Companion)
        every { MnemonicCodeExt.getInstance() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkObject(MnemonicCodeExt.Companion)
        unmockkStatic(SecurityGuard::class)
    }

    @Test
    fun `a refused replacement reports REFUSED and runs none of the restore follow-ups`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.setWallet(any()) } returns false
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()
        val configuration = mockk<Configuration>(relaxed = true)
        val viewModel = viewModel(app, factory, configuration)

        assertEquals(SeedRestoreResult.REFUSED, viewModel.restoreWalletFromSeed(words))
        verify(exactly = 0) { app.resetBlockchainState() }
        verify(exactly = 0) { configuration.disarmBackupSeedReminder() }
        verify(exactly = 0) { configuration.isRestoringBackup = any() }
        // The refusal raced past the up-front check: the selected date must
        // still not overwrite the existing wallet's scan start.
        assertEquals(existingDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }

    @Test
    fun `a replacement refused up front builds no wallet and keeps the existing creation date`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns true
        val factory = mockk<WalletFactory>()

        assertEquals(SeedRestoreResult.REFUSED, viewModel(app, factory).restoreWalletFromSeed(words))
        coVerify(exactly = 0) { factory.restoreFromSeed(any(), any()) }
        verify(exactly = 0) { app.setWallet(any()) }
        assertEquals(existingDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }

    @Test
    fun `an accepted replacement persists the selected creation date`() = runBlocking {
        val app = mockk<WalletApplication>(relaxed = true)
        every { app.isWalletReplacementRefused } returns false
        every { app.setWallet(any()) } answers {
            // Not yet: the date follows the acceptance.
            assertEquals(existingDateSecs, runBlocking { blockchainServiceConfig.getWalletCreationDate() })
            true
        }
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any()) } returns mockk<Wallet>()

        assertEquals(SeedRestoreResult.RESTORED, viewModel(app, factory).restoreWalletFromSeed(words))
        assertEquals(selectedDateSecs, blockchainServiceConfig.getWalletCreationDate())
    }
}
