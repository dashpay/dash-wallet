/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.util

import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.toFiat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.*

// the purpose of these methods is to directly convert Coin and Fiat to BigDecimal
// without first converting to a string.  If the strings are localized, BigDecimal
// may throw a NumberFormatException (e.g. "0,43")

fun Coin.toBigDecimal() : BigDecimal {
    return BigDecimal(this.value).scaleByPowerOfTen(-Coin.SMALLEST_UNIT_EXPONENT)
}

fun Fiat.toBigDecimal() : BigDecimal {
    return BigDecimal(this.value).scaleByPowerOfTen(-Fiat.SMALLEST_UNIT_EXPONENT)
}

fun Fiat.toDouble(): Double {
    return toBigDecimal().toDouble()
}

fun BigDecimal.toCoin() : Coin {
    return Coin.valueOf(this.scaleByPowerOfTen(Coin.SMALLEST_UNIT_EXPONENT).toLong())
}

fun BigDecimal.toFiat(currency: String) : Fiat {
    return Fiat.valueOf(currency, this.scaleByPowerOfTen(Fiat.SMALLEST_UNIT_EXPONENT).toLong())
}

/** Neutral counterpart of [BigDecimal.toCoin] for modules that don't depend on dashj. */
fun BigDecimal.toDash(): Dash {
    return Dash(this.scaleByPowerOfTen(Coin.SMALLEST_UNIT_EXPONENT).toLong())
}

/** Neutral counterpart of [BigDecimal.toFiat] for modules that don't depend on dashj. */
fun BigDecimal.toFiatValue(currency: String): FiatValue {
    return FiatValue(currency, this.scaleByPowerOfTen(Fiat.SMALLEST_UNIT_EXPONENT).toLong())
}

/** Neutral counterpart of [Fiat.isCurrencyFirst] for modules that don't depend on dashj. */
fun FiatValue.isCurrencyFirst(): Boolean {
    return toFiat().isCurrencyFirst()
}

/** Neutral counterpart of [Fiat.toFormattedString] for modules that don't depend on dashj. */
fun FiatValue.toFormattedString(): String {
    return toFiat().toFormattedString()
}

/** Neutral counterpart of [Fiat.toFormattedStringRoundUp] for modules that don't depend on dashj. */
fun FiatValue.toFormattedStringRoundUp(): String {
    return toFiat().toFormattedStringRoundUp()
}

/** Neutral counterpart of [Fiat.discountBy] for modules that don't depend on dashj. */
fun FiatValue.discountBy(fraction: Double): FiatValue =
    FiatValue(currencyCode, (value * (1.0 - fraction)).toLong())

/** Neutral counterpart of [Fiat.toFormattedStringNoCode] for modules that don't depend on dashj. */
fun FiatValue.toFormattedStringNoCode(): String {
    return toFiat().toFormattedStringNoCode()
}

val Fiat.currencySymbol: String
    get() = GenericUtils.currencySymbol(currencyCode)

fun Fiat.toFormattedString(): String {
    val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
    return toFormattedString(format)
}

fun Fiat.toFormattedStringRoundUp(): String {
    val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode().roundingMode(RoundingMode.UP)
    return toFormattedString(format)
}

fun Fiat.toFormattedString(format: MonetaryFormat): String {
    val numberFormat = NumberFormat.getCurrencyInstance(GenericUtils.getDeviceLocale())
    val currency = Currency.getInstance(currencyCode)
    numberFormat.currency = currency
    val currencySymbol = currency.getSymbol(GenericUtils.getDeviceLocale())
    val isCurrencyFirst = numberFormat.format(1.0).startsWith(currencySymbol)

    return if (isCurrencyFirst) {
        currencySymbol + " " + format.format(this)
    } else {
        format.format(this).toString() + " " + currencySymbol
    }
}

fun Fiat.isCurrencyFirst(): Boolean {
    val numberFormat = NumberFormat.getCurrencyInstance(GenericUtils.getDeviceLocale())
    val currency = Currency.getInstance(currencyCode)
    numberFormat.currency = currency
    val currencySymbol = currency.getSymbol(GenericUtils.getDeviceLocale())
    return numberFormat.format(1.0).startsWith(currencySymbol)
}

fun Fiat.toFormattedStringNoCode(): String {
    val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
    return format.format(this).toString()
}

fun Fiat.discountBy(fraction: Double): Fiat =
    Fiat.valueOf(currencyCode, (value * (1.0 - fraction)).toLong())
