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
import io.mockk.coVerifyOrder
import io.mockk.mockk
import org.dash.wallet.common.data.WalletUIConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownBalancesTest {
    private val walletUIConfig = mockk<WalletUIConfig>(relaxed = true)
    private val dashPayConfig = mockk<DashPayConfig>(relaxed = true)

    @Test
    fun `a value counts only with its ownership flag`() {
        assertEquals(5L, ownedOrNull(5L, true))
        // stored by an earlier version: may belong to a previous wallet
        assertNull(ownedOrNull(5L, null))
        assertNull(ownedOrNull(5L, false))
        assertNull(ownedOrNull(null, true))
    }

    @Test
    fun `an install-time clear removes each flag before its value`() {
        clearLastKnownBalances(walletUIConfig, dashPayConfig)

        coVerifyOrder {
            walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE_OWNED)
            walletUIConfig.remove(WalletUIConfig.LAST_TOTAL_BALANCE)
        }
        coVerifyOrder {
            dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_OWNED)
            dashPayConfig.remove(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS)
        }
    }
}
