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

package org.dash.wallet.common.money

import java.math.BigDecimal

/**
 * A fiat monetary amount ([value] is in the same smallest unit as dashj's `Fiat` — 1E-8 —
 * and delegates to the self-contained [Fiat] port internally, so parsing and formatting
 * behavior is identical). Feature/integration modules use this instead of Fiat.
 */
data class FiatValue(val currencyCode: String, val value: Long) : Comparable<FiatValue> {

    companion object {
        const val SMALLEST_UNIT_EXPONENT = Fiat.SMALLEST_UNIT_EXPONENT

        fun valueOf(currencyCode: String, value: Long) = FiatValue(currencyCode, value)

        /** Mirrors [Fiat.parseFiat]: throws [IllegalArgumentException] on overflow/precision. */
        fun parseFiat(currencyCode: String, str: String): FiatValue {
            val fiat = Fiat.parseFiat(currencyCode, str)
            return FiatValue(fiat.currencyCode, fiat.value)
        }

        /** Mirrors [Fiat.parseFiatInexact]: rounds instead of throwing on excess precision. */
        fun parseFiatInexact(currencyCode: String, str: String): FiatValue {
            val fiat = Fiat.parseFiatInexact(currencyCode, str)
            return FiatValue(fiat.currencyCode, fiat.value)
        }

        fun zero(currencyCode: String) = FiatValue(currencyCode, 0)
    }

    private val fiat: Fiat get() = Fiat.valueOf(currencyCode, value)

    fun add(other: FiatValue) = FiatValue(currencyCode, fiat.add(Fiat.valueOf(other.currencyCode, other.value)).value)
    operator fun plus(other: FiatValue) = add(other)
    fun subtract(other: FiatValue) =
        FiatValue(currencyCode, fiat.subtract(Fiat.valueOf(other.currencyCode, other.value)).value)
    operator fun minus(other: FiatValue) = subtract(other)

    val isZero: Boolean get() = value == 0L
    val isPositive: Boolean get() = value > 0L
    val isNegative: Boolean get() = value < 0L
    fun isGreaterThan(other: FiatValue) = value > other.value
    fun isLessThan(other: FiatValue) = value < other.value
    override fun compareTo(other: FiatValue): Int = value.compareTo(other.value)

    fun toPlainString(): String = fiat.toPlainString()
    fun toFriendlyString(): String = fiat.toFriendlyString()
    fun toBigDecimal(): BigDecimal = BigDecimal(value).movePointLeft(SMALLEST_UNIT_EXPONENT)

    override fun toString(): String = toPlainString()
}
