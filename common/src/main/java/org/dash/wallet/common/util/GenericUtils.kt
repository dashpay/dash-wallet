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

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.LocaleList
import android.text.TextUtils
import org.dash.wallet.common.data.CurrencyInfo
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

    fun isInternetConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo != null && cm.activeNetworkInfo!!.isConnected
    }

    /**
     * Function which returns a concatenation of the currency code or currency symbol
     * For currencies used by multiple countries, we set a locale with any country using the currency
     * If the currentCurrencySymbol equals the currency code, we just use the currency code, otherwise we
     * get the symbol
     * @param currencyCode
     * @return
     */
    fun setCurrentCurrencySymbolWithCode(currencyCode: String): String? {
        var currentLocale = Locale("", "")
        var currentCurrencySymbol: String? = ""
        when (currencyCode.lowercase()) {
            "eur" -> currentLocale = Locale.FRANCE
            "xof" -> currentLocale = Locale("fr", "CM")
            "xaf" -> currentLocale = Locale("fr", "SN")
            "cfp" -> currentLocale = Locale("fr", "NC")
            "hkd" -> currentLocale = Locale("en", "HK")
            "bnd" -> currentLocale = Locale("ms", "BN")
            "aud" -> currentLocale = Locale("en", "AU")
            "gbp" -> currentLocale = Locale.UK
            "inr" -> currentLocale = Locale("en", "IN")
            "nzd" -> currentLocale = Locale("en", "NZ")
            "ils" -> currentLocale = Locale("iw", "IL")
            "jod" -> currentLocale = Locale("ar", "JO")
            "rub" -> currentLocale = Locale("ru", "RU")
            "zar" -> currentLocale = Locale("en", "ZA")
            "chf" -> currentLocale = Locale("fr", "CH")
            "try" -> currentLocale = Locale("tr", "TR")
            "usd" -> currentLocale = Locale.US
        }
        currentCurrencySymbol =
            if (TextUtils.isEmpty(currentLocale.language)) {
                currencySymbol(currencyCode.lowercase())
            } else {
                Currency.getInstance(
                    currentLocale
                ).symbol
            }
        return String.format(
            getDeviceLocale(),
            "%s",
            if (currencyCode.equals(currentCurrencySymbol, ignoreCase = true)) currencyCode else currentCurrencySymbol
        )
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
     * Keep numericals, minus, dot, comma
     */
    private fun stripLettersFromString(st: String): String {
        return st.replace("[^\\d,.-]".toRegex(), "")
    }

    /**
     * Remove currency symbols and codes from the string
     */
    private fun stripCurrencyFromString(st: String, symbol: String, code: String): String {
        return stripLettersFromString(st.replace(symbol.toRegex(), "").replace(code.toRegex(), ""))
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

    fun getLocaleCurrencyCode(): String? {
        val currency = Currency.getInstance(getDeviceLocale())
        var newCurrencyCode = currency.currencyCode
        if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
            newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
        }
        newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode)

        return newCurrencyCode
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
