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

package de.schildbach.wallet.ui.username.request

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ContestedUsernameFees
import de.schildbach.wallet.ui.username.UsernameType
import org.bitcoinj.core.Coin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cost/source selection for the username confirm sheet
 * ([resolveUsernameConfirmCost]): the sheet is the last confirmation before
 * the spend and must show the amount leaving whichever pool pays. The
 * regression this pins down: the Secondary confirm of a shielded dual
 * creation showed "0.00 DASH / $0.00" while the submit actually spent the
 * 0.3 DASH shielded denomination (observed live).
 *
 * The L1 contested cases are exercised under BOTH [ContestedUsernameFees]
 * branches (MO-1069): [ContestedUsernameFees.LEGACY] is what testnet/mainnet
 * actually charge today (protocol 13 / Drive 4.1.x, 0.25/0.20), and
 * [ContestedUsernameFees.CURRENT] is the Platform v4.2 rate (protocol >= 14,
 * 0.15/0.10) a live [de.schildbach.wallet.service.platform.sdk.DashSdkService
 * .currentProtocolVersion] read activates. The sheet must always show
 * whichever pair was actually resolved, never a hardcoded one.
 */
class UsernameConfirmCostTest {

    private val shieldedContestedDenomination = Coin.valueOf(25_000_000) // 0.25 DASH
    private val shieldedNonContestedDenomination = Coin.valueOf(3_000_000) // 0.03 DASH

    @Test
    fun `L1 contested primary creation shows the legacy contested fee`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(ContestedUsernameFees.LEGACY.contested, cost.amount)
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_LEGACY, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `L1 contested primary creation shows the current (v4_2) contested fee`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.CURRENT
        )
        assertEquals(ContestedUsernameFees.CURRENT.contested, cost.amount)
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `L1 non-contested primary creation shows the standard fee`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = false,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(Constants.DASH_PAY_FEE, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `contested name on an existing identity shows the legacy name-only fee`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = true,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(ContestedUsernameFees.LEGACY.contestedName, cost.amount)
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_NAME_LEGACY, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `contested name on an existing identity shows the current (v4_2) name-only fee`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = true,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.CURRENT
        )
        assertEquals(ContestedUsernameFees.CURRENT.contestedName, cost.amount)
        assertEquals(Constants.DASH_PAY_FEE_CONTESTED_NAME, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `shielded contested creation shows the 0_25 exit denomination under legacy fees`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(shieldedContestedDenomination, cost.amount)
        assertTrue(cost.fromShieldedBalance)
    }

    @Test
    fun `shielded contested creation shows the same 0_25 exit denomination under current fees`() {
        // The shielded exit-denomination set is a Drive consensus value
        // independent of the transparent contested fee: 0.15 (current) and
        // 0.25 (legacy) both round UP to the same 0.25 denomination, so the
        // shielded sheet is invariant across the protocol-gate branches.
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
            contestedFees = ContestedUsernameFees.CURRENT
        )
        assertEquals(shieldedContestedDenomination, cost.amount)
        assertTrue(cost.fromShieldedBalance)
    }

    @Test
    fun `shielded non-contested creation shows the 0_03 exit denomination from the shielded balance`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Primary,
            isContestable = false,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(shieldedNonContestedDenomination, cost.amount)
        assertTrue(cost.fromShieldedBalance)
    }

    @Test
    fun `dual-flow secondary (instant) confirm is free even on the shielded path`() {
        // The instant/secondary name adds no incremental cost; the identity
        // funding (0.25 shielded) is disclosed on the PRIMARY confirm. Showing
        // a price here would wrongly imply the instant name costs something.
        val cost = resolveUsernameConfirmCost(
            UsernameType.Secondary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(Coin.ZERO, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `dual-flow secondary (instant) confirm is free on the L1 path too`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Secondary,
            isContestable = true,
            hasIdentity = false,
            paymentSource = UsernamePaymentSource.DASH_BALANCE,
            contestedFees = ContestedUsernameFees.CURRENT
        )
        assertEquals(Coin.ZERO, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }

    @Test
    fun `secondary name for an existing identity stays free of new wallet spend`() {
        val cost = resolveUsernameConfirmCost(
            UsernameType.Secondary,
            isContestable = true,
            hasIdentity = true,
            paymentSource = UsernamePaymentSource.SHIELDED_BALANCE,
            contestedFees = ContestedUsernameFees.LEGACY
        )
        assertEquals(Coin.ZERO, cost.amount)
        assertFalse(cost.fromShieldedBalance)
    }
}
