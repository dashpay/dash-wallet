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
 * A Dash amount in duffs (satoshis), independent of the underlying wallet library.
 *
 * The API mirrors dashj's `Coin` (and delegates to the self-contained [Coin] port internally)
 * so behavior — parsing, formatting, arithmetic overflow — is identical, but consumers of this
 * type never see dashj on their classpath.
 */
@JvmInline
value class Dash(val duffs: Long) : Comparable<Dash> {

    companion object {
        val ZERO = Dash(0)
        val COIN = Dash(Coin.COIN.value)

        fun valueOf(duffs: Long) = Dash(duffs)
        fun valueOf(coins: Int, cents: Int) = Dash(Coin.valueOf(coins, cents).value)

        /** Mirrors `Coin.parseCoin`: parses a decimal Dash amount, throws [IllegalArgumentException] on overflow/precision. */
        fun parse(str: String) = Dash(Coin.parseCoin(str).value)
    }

    private val coin: Coin get() = Coin.valueOf(duffs)

    fun add(value: Dash) = Dash(coin.add(Coin.valueOf(value.duffs)).value)
    operator fun plus(value: Dash) = add(value)
    fun subtract(value: Dash) = Dash(coin.subtract(Coin.valueOf(value.duffs)).value)
    operator fun minus(value: Dash) = subtract(value)
    fun multiply(factor: Long) = Dash(coin.multiply(factor).value)
    operator fun times(factor: Long) = multiply(factor)
    fun div(divisor: Long) = Dash(coin.div(divisor).value)
    fun divide(divisor: Dash): Long = coin.divide(Coin.valueOf(divisor.duffs))

    val isZero: Boolean get() = duffs == 0L
    val isPositive: Boolean get() = duffs > 0L
    val isNegative: Boolean get() = duffs < 0L
    fun isGreaterThan(other: Dash) = duffs > other.duffs
    fun isLessThan(other: Dash) = duffs < other.duffs
    override fun compareTo(other: Dash): Int = duffs.compareTo(other.duffs)

    /** Mirrors `Coin.toPlainString`: decimal representation without a currency code. */
    fun toPlainString(): String = coin.toPlainString()

    /** Mirrors `Coin.toFriendlyString`: denominated representation with a currency code. */
    fun toFriendlyString(): String = coin.toFriendlyString()

    fun toBigDecimal(): BigDecimal = BigDecimal(duffs).movePointLeft(8)

    override fun toString(): String = toPlainString()
}
