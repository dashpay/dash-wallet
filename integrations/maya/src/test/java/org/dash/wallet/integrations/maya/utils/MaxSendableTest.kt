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

package org.dash.wallet.integrations.maya.utils

import org.bitcoinj.core.Coin
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toCoin
import org.dash.wallet.integrations.maya.model.Amount
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The Max button's arithmetic (MO-997): a max sell is a sweep, so what it can enter is
 * `balance − fee`, never the balance.
 */
class MaxSendableTest {

    private val oneDash: Coin = Coin.COIN
    private val sweepFee: Coin = Coin.valueOf(1930) // ~193 byte 1-in/1-out sweep

    // ── sendable = balance − fee ────────────────────────────────────────────────

    @Test
    fun feeBelowBalance_sendableIsBalanceMinusFee() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)

        assertEquals(Coin.valueOf(99998070), sendable)
        assertTrue(sendable.isLessThan(oneDash))
    }

    @Test
    fun feeEqualToBalance_sendableIsZero() {
        assertEquals(Coin.ZERO, MaxSendable.sendableFromBalance(sweepFee, sweepFee))
    }

    @Test
    fun feeAboveBalance_isCoercedAtZeroRatherThanGoingNegative() {
        val sendable = MaxSendable.sendableFromBalance(Coin.valueOf(500), sweepFee)

        assertEquals(Coin.ZERO, sendable)
        assertFalse(sendable.isNegative)
    }

    @Test
    fun emptyWallet_sendsNothing() {
        assertEquals(Coin.ZERO, MaxSendable.sendableFromBalance(Coin.ZERO, sweepFee))
    }

    @Test
    fun negativeSweepOutputFromTheEstimator_isCoercedAtZero() {
        assertEquals(Coin.ZERO, MaxSendable.coerceSendable(Coin.valueOf(-1430)))
    }

    @Test
    fun positiveSweepOutputFromTheEstimator_isPassedThrough() {
        assertEquals(Coin.valueOf(99998070), MaxSendable.coerceSendable(Coin.valueOf(99998070)))
    }

    // ── Max entered in fiat, read back as DASH ──────────────────────────────────

    /**
     * Entering the Max in fiat and converting back loses satoshis twice (the fiat value is
     * rounded to the currency's digits, then truncated back to a Coin), which is why the DASH
     * component is re-pinned afterwards. Without the pin the swap is quietly downgraded to a
     * partial one — and a half-satoshi short of a sweep is a deposit NEAR Intents refunds.
     */
    @Test
    fun maxEnteredInFiat_roundTripFallsShortOfSendable() {
        val sendable = MaxSendable.sendableFromBalance(Coin.valueOf(50_000_000), sweepFee)
        val amount = amountAtRate("125.37").apply { dash = sendable.toBigDecimal() }

        // What the picker shows in fiat, rounded to the currency's 2 digits, and then re-entered
        // the way setEnteredAmount does it — GenericUtils.toScaledBigDecimal at 8 decimals, which
        // is also the scale the fiat-to-DASH division inherits.
        val displayedFiat = amount.fiat.setScale(2, RoundingMode.HALF_UP)
        val reEntered = amountAtRate("125.37").apply {
            fiat = displayedFiat.setScale(8, RoundingMode.HALF_UP)
        }

        assertTrue(reEntered.dash.toCoin().isLessThan(sendable))
        assertFalse(MaxSendable.isTypedMax(reEntered.dash.toCoin(), sendable))
    }

    @Test
    fun maxPinnedAfterFiatEntry_readsBackAsExactlySendable() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)
        val amount = amountAtRate("125.37")

        // What selectMaxAmount does: pin the exact DASH figure, then restore the picker's anchor.
        amount.dash = sendable.toBigDecimal()
        amount.anchoredType = CurrencyInputType.Fiat

        assertEquals(CurrencyInputType.Fiat, amount.anchoredType)
        assertEquals(sendable, amount.dash.toCoin())
        assertTrue(MaxSendable.isTypedMax(amount.dash.toCoin(), sendable))
    }

    // ── Hand-typed max still counts as a max ────────────────────────────────────

    @Test
    fun typedSendableAmount_isTreatedAsMax() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)

        assertTrue(MaxSendable.isTypedMax(sendable, sendable))
        assertTrue(MaxSendable.isMaxSwap(false, sendable, sendable))
    }

    @Test
    fun typedAmountBelowSendable_isNotAMax() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)

        assertFalse(MaxSendable.isTypedMax(sendable.subtract(Coin.SATOSHI), sendable))
        assertFalse(MaxSendable.isMaxSwap(false, sendable.subtract(Coin.SATOSHI), sendable))
    }

    /**
     * The gross balance is above the sendable figure, so it is rejected by the entry bound
     * before it can be swapped — but if it ever does get through it is still a sweep, not a
     * partial swap for more than the wallet can deliver.
     */
    @Test
    fun typedGrossBalance_isTreatedAsMax() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)

        assertTrue(MaxSendable.isTypedMax(oneDash, sendable))
    }

    @Test
    fun maxButtonIntent_survivesAnAmountThatNoLongerMatches() {
        val sendable = MaxSendable.sendableFromBalance(oneDash, sweepFee)
        val roundedDown = sendable.subtract(Coin.valueOf(2))

        assertFalse(MaxSendable.isTypedMax(roundedDown, sendable))
        assertTrue(MaxSendable.isMaxSwap(true, roundedDown, sendable))
    }

    @Test
    fun walletThatCanSendNothing_isNeverAMax() {
        assertFalse(MaxSendable.isTypedMax(Coin.ZERO, Coin.ZERO))
        assertFalse(MaxSendable.isTypedMax(oneDash, Coin.ZERO))
        assertFalse(MaxSendable.isMaxSwap(false, Coin.ZERO, Coin.ZERO))
    }

    private fun amountAtRate(dashFiatRate: String) = Amount().apply {
        dashFiatExchangeRate = BigDecimal(dashFiatRate)
        cryptoFiatExchangeRate = BigDecimal("65000")
    }
}
