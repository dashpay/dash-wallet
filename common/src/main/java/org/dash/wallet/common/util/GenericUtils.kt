/*
 * Copyright 2023 Dash Core Group.
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

import android.os.Build
import android.os.LocaleList
import org.bitcoinj.utils.MonetaryFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.text.ParseException


/**
 * @author Andreas Schildbach
 */
object GenericUtils {
    private var percentFormat = NumberFormat.getPercentInstance().apply {
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }

    private val isRunningUnitTest: Boolean
        get() = try {
            Class.forName("org.junit.Test")
            true
        } catch (e: ClassNotFoundException) {
            false
        }

    fun startsWithIgnoreCase(string: String, prefix: String): Boolean =
        string.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

    fun currencySymbol(currencyCode: String): String {
        return try {
            val currency = Currency.getInstance(currencyCode)
            currency.symbol
        } catch (x: IllegalArgumentException) {
            currencyCode
        }
    }

    fun getDeviceLocale(): Locale {
        val countryCode = if (!isRunningUnitTest) {
            LocaleList.getDefault()[0].country
        } else {
            Locale.getDefault().country
        }
        val deviceLocaleLanguage = Locale.getDefault().language

        return Locale(deviceLocaleLanguage, countryCode)
    }

    fun getDefaultLocale(): Locale {
        return Locale.US
    }

    /**
     * To perform some operations on our fiat values (ex: parse to double, convert fiat to Coin), it needs to be properly formatted
     * In case our fiat value is in a currency that has a comma, we need to strip it away so as to have our value as a decimal
     * @param fiatValue
     * @return
     */
    fun formatFiatWithoutComma(fiatValue: String): String {
        val fiatValueContainsCommaWithDecimal = fiatValue.contains(".") && fiatValue.contains(",")

        return if (fiatValueContainsCommaWithDecimal) {
            fiatValue.replace(",", "")
        } else {
            fiatValue.replace(",", ".")
                .replace("٫", ".")
        }
    }

    fun getLocalCurrencySymbol(currencyCode: String?): String? {
        val numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale())
        val currency = Currency.getInstance(currencyCode)
        numberFormat.currency = currency
        return currency.getSymbol(getDeviceLocale())
    }

    fun getCoinIcon(code: String): String {
        return "https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/" +
            code.lowercase(Locale.getDefault()) + ".png"
    }

    /**
     *
     * @param percent The number as a double where 1.00 is 1.00%
     * @return
     */
    fun formatPercent(percent: Double): String? {
        // the formatter translates 0.01 to 1.00%
        return percentFormat.format(percent / 100)
    }

    fun isCurrencySymbolFirst(): Boolean {
        val locale = getDeviceLocale()
        // val currency: Currency = Currency.getInstance(locale)
        val currencyFormat = NumberFormat.getCurrencyInstance(locale)

        val pattern = (currencyFormat as DecimalFormat).toPattern()
        println("Currency Pattern: $pattern")

        return pattern.startsWith("¤")
    }

    fun getCurrencyDigits(): Int {
        val locale = getDeviceLocale()
        val currency: Currency? = Currency.getInstance(locale)
        return currency?.defaultFractionDigits ?: 0
    }

    private fun stringToBigDecimal(value: String): BigDecimal {
        return try {
            val format = NumberFormat.getNumberInstance(getDeviceLocale())
            val number = format.parse(value)
            if (number != null) BigDecimal(number.toString()) else BigDecimal.ZERO
        } catch (e: ParseException) {
            BigDecimal.ZERO
        }
    }

    fun toScaledBigDecimal(value: String, localized: Boolean = false, scale: Int = 8): BigDecimal {
        return if (localized) {
            stringToBigDecimal(value).setScale(scale, RoundingMode.HALF_UP)
        } else {
            value.toBigDecimal().setScale(scale, RoundingMode.HALF_UP)
        }
    }

    private val dashFormat: MonetaryFormat = MonetaryFormat().withLocale(getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals(2, 2, 2)
    private val fiatFormat: MonetaryFormat = MonetaryFormat().withLocale(getDeviceLocale())
        .noCode().minDecimals(getCurrencyDigits()).optionalDecimals()

    fun toLocalizedString(value: BigDecimal, isCrypto: Boolean, currencyCode: String): String {
        return if (isCrypto) {
            dashFormat.format(value.toCoin())
        } else {
            fiatFormat.format(value.toFiat(currencyCode))
        }.toString()
    }
}
