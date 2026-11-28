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

package org.dash.wallet.integrations.maya.swapkit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * The SwapKit/NEAR deposit amount computation ([swapKitDepositAmountDash]) —
 * the #1535 scope-gap fix. A MAX sell must be a FIXED-amount send of exactly
 * the quoted amount (`spendable − fee reserve`), never an `emptyWallet`
 * sweep: the changeless drain has no wallet-owned output, compact block
 * filters never match it, and it never settles (mainnet a5c99aec…/1f608a9a…).
 */
class SwapKitDepositAmountTest {

    @Test
    fun ordinarySell_paysSellAmountPlusSwapFee() {
        val amount = swapKitDepositAmountDash(
            sellAmountDash = BigDecimal("1.25000000"),
            feeAmountDash = BigDecimal("0.00100000"),
            maximum = false
        )
        assertEquals(BigDecimal("1.25100000"), amount)
    }

    @Test
    fun maxSell_paysExactlyTheQuotedAmount_feeNotAdded() {
        // The MAX quote is already `spendable − fee reserve` (measured through
        // MayaBlockchainApi.maxSwapDepositAmount on the commit-time refresh).
        // What is sent downstream must equal what was quoted — adding the swap
        // fee (or sweeping the balance) would break that equality.
        val quotedMax = BigDecimal("2.34567890")
        val amount = swapKitDepositAmountDash(
            sellAmountDash = quotedMax,
            feeAmountDash = BigDecimal("0.00100000"),
            maximum = true
        )
        assertEquals(quotedMax.setScale(8), amount)
    }

    @Test
    fun amountsAreNormalizedToEightDecimals_halfUp() {
        val amount = swapKitDepositAmountDash(
            sellAmountDash = BigDecimal("0.123456789"), // 9 decimals
            feeAmountDash = BigDecimal.ZERO,
            maximum = true
        )
        assertEquals(BigDecimal("0.12345679"), amount)
        assertEquals(8, amount.scale())
    }
}
