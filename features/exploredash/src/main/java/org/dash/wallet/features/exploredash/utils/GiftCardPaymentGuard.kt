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

package org.dash.wallet.features.exploredash.utils

import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.money.ExchangeRate
import java.math.BigDecimal

/** Compares the Dash a gift card provider asks for with the Dash the confirmed price came to. */
object GiftCardPaymentGuard {
    /** Up to this much more than the confirmed amount is paid without asking again. */
    const val SILENT_INCREASE_BASIS_POINTS = 50L
    private const val BASIS_POINTS = 10_000L
    private const val DUFF_DECIMALS = 8
    private val plainDecimal = Regex("\\d+(\\.\\d{1,$DUFF_DECIMALS})?")

    /** The Dash that [total] comes to at [rate], rounded down, or null when it cannot be told. */
    fun approvedAmount(total: BigDecimal, currency: String, rate: ExchangeRate): Coin? = try {
        rate.takeIf { it.fiat.currencyCode == currency }
            ?.fiatToCoin(GiftCardCurrency.toFiat(total.toDouble(), currency))
            ?.takeIf { it.isPositive }
    } catch (e: ArithmeticException) {
        null
    }

    /** The Dash amount a provider quoted as text, or null unless it is a positive number of duffs. */
    fun dashAmount(quoted: String?): Coin? = try {
        // exact or not at all: a lenient parser would turn a figure beyond the duff range into another
        quoted?.trim()?.takeIf(plainDecimal::matches)
            ?.let { Coin.valueOf(BigDecimal(it).movePointRight(DUFF_DECIMALS).longValueExact()) }
            ?.takeIf { it.isPositive }
    } catch (e: ArithmeticException) {
        null
    }

    fun isHigher(approved: Coin, requested: Coin): Boolean =
        requested.value - approved.value > silentIncrease(approved.value)

    private fun silentIncrease(amount: Long) = amount / BASIS_POINTS * SILENT_INCREASE_BASIS_POINTS +
        amount % BASIS_POINTS * SILENT_INCREASE_BASIS_POINTS / BASIS_POINTS
}
