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
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

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
}
