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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.WalletFactory
import de.schildbach.wallet.util.MnemonicCodeExt
import io.mockk.coEvery
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RestoreWalletFromSeedViewModelTest {
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
        every { app.setWallet(any()) } returns false
        val factory = mockk<WalletFactory>()
        coEvery { factory.restoreFromSeed(any(), any(), any()) } returns mockk<Wallet>()
        val configuration = mockk<Configuration>(relaxed = true)
        val viewModel = RestoreWalletFromSeedViewModel(app, factory, configuration, mockk(relaxed = true), mockk(relaxed = true))

        assertEquals(SeedRestoreResult.REFUSED, viewModel.restoreWalletFromSeed(words))
        verify(exactly = 0) { app.resetBlockchainState() }
        verify(exactly = 0) { configuration.disarmBackupSeedReminder() }
        verify(exactly = 0) { configuration.isRestoringBackup = any() }
    }
}
