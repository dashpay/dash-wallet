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
package de.schildbach.wallet

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import org.dash.wallet.common.data.WalletUIConfig
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LastKnownBalancesTest {
    private val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true)

    @Test
    fun `balances an earlier version stored are cleared once, the marker last`() {
        coEvery { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_BALANCES_OWNED) } returns null

        ensureLastKnownBalancesOwned(walletUIConfig, dashPayConfig)

        coVerifyOrder {
            walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE)
            dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS)
            walletUIConfig.set(WalletUIConfig.LAST_BALANCES_OWNED, true)
        }
    }

    @Test
    fun `balances already owned by the installed wallet are left alone`() {
        coEvery { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_BALANCES_OWNED) } returns true

        ensureLastKnownBalancesOwned(walletUIConfig, dashPayConfig)

        coVerify(exactly = 0) { walletUIConfig.remove(any<androidx.datastore.preferences.core.Preferences.Key<Long>>()) }
        coVerify(exactly = 0) { dashPayConfig.remove(any<androidx.datastore.preferences.core.Preferences.Key<Long>>()) }
    }

    @Test
    fun `an unreadable marker throws, so the caller treats both balances as unknown`() {
        coEvery { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_BALANCES_OWNED) } throws IOException("unreadable")

        assertThrows(IOException::class.java) { ensureLastKnownBalancesOwned(walletUIConfig, dashPayConfig) }
        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_BALANCES_OWNED, true) }
    }

    @Test
    fun `a clear stopped part way leaves no marker, so it runs again`() {
        coEvery { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_BALANCES_OWNED) } returns null
        coEvery { dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS) } throws IOException("disk full")

        assertThrows(IOException::class.java) { ensureLastKnownBalancesOwned(walletUIConfig, dashPayConfig) }
        coVerify(exactly = 0) { walletUIConfig.set(WalletUIConfig.LAST_BALANCES_OWNED, true) }
    }

    @Test
    fun `an install-time clear also marks the balances as owned`() {
        clearLastKnownBalances(walletUIConfig, dashPayConfig)

        coVerify(exactly = 1) { walletUIConfig.set(WalletUIConfig.LAST_BALANCES_OWNED, true) }
    }
}
