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

import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toFormattedString
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Money rules for the currency a gift card is priced in: the merchant's, not always USD. */
object GiftCardCurrency {
    /** Upper-case ISO 4217 code, or null when [code] is blank or not a currency with minor units. */
    fun normalize(code: String?): String? {
        val candidate = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return try {
            candidate.takeIf { Currency.getInstance(it).defaultFractionDigits >= 0 }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun fractionDigits(currencyCode: String): Int = Currency.getInstance(currencyCode).defaultFractionDigits

    /** False when [amount] has more decimals than the currency has, e.g. 1000.5 JPY. */
    fun isRepresentable(amount: Double, currencyCode: String): Boolean =
        amount.isFinite() &&
            BigDecimal.valueOf(amount).stripTrailingZeros().scale() <= fractionDigits(currencyCode)

    /** Locale-independent amount for the provider's API. Throws rather than round a user's amount. */
    fun orderAmount(amount: Double, currencyCode: String): String =
        BigDecimal.valueOf(amount)
            .setScale(fractionDigits(currencyCode), RoundingMode.UNNECESSARY)
            .toPlainString()

    // Fiat counts in 1/10000 units, enough for every ISO 4217 currency (JPY 0, KWD 3 decimals)
    fun toFiat(amount: Double, currencyCode: String): Fiat =
        Fiat.valueOf(
            currencyCode,
            BigDecimal.valueOf(amount)
                .setScale(Fiat.SMALLEST_UNIT_EXPONENT, RoundingMode.HALF_UP)
                .unscaledValue()
                .longValueExact()
        )

    /** Formats for display in the user's locale, with the currency's own number of decimals. */
    fun displayFormat(currencyCode: String, trimZeroDecimals: Boolean = false): NumberFormat {
        val currency = Currency.getInstance(currencyCode)
        return NumberFormat.getCurrencyInstance().apply {
            this.currency = currency
            maximumFractionDigits = currency.defaultFractionDigits
            minimumFractionDigits = if (trimZeroDecimals) 0 else currency.defaultFractionDigits
        }
    }

    /** A saved card without a currency was bought before cards had one, when every card was USD. */
    fun savedCardFormat(savedCurrency: String?): NumberFormat =
        displayFormat(normalize(savedCurrency) ?: Constants.USD_CURRENCY)
}

fun Fiat.toCardFormattedString(roundUp: Boolean = false): String {
    val format = Constants.SEND_PAYMENT_LOCAL_FORMAT.noCode()
        .minDecimals(GiftCardCurrency.fractionDigits(currencyCode))
        .optionalDecimals()
    return toFormattedString(if (roundUp) format.roundingMode(RoundingMode.UP) else format)
}
