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
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.*

/**
 * @author Andreas Schildbach
 */
object GenericUtils {
    private var percentFormat = NumberFormat.getPercentInstance().apply {
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
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
        val countryCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault()[0].country
        } else {
            Locale.getDefault().country
        }
        val deviceLocaleLanguage = Locale.getDefault().language

        return Locale(deviceLocaleLanguage, countryCode)
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
}
