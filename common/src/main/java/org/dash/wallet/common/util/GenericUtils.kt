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

    /** Characters any supported locale may use as a decimal or grouping mark. */
    private const val SEPARATORS = ".,٫"

    /**
     * Normalises a human-formatted amount into one BigDecimal, Coin.parseCoin and
     * Fiat.parseFiat will accept: a single '.' decimal mark and no grouping marks.
     *
     * Which mark is the decimal one is decided, never guessed. The decimal mark can occur
     * at most once in a number, so the LAST separator is the decimal mark precisely when
     * that character appears exactly once; every other separator is grouping and is
     * dropped. When a character repeats it cannot be a decimal mark, so all its
     * occurrences are grouping.
     *
     * This used to test only "contains a dot AND a comma" and then strip the commas, which
     * silently mangled every European-formatted amount: "1.234,56" became "1.23456", a
     * thousandfold error in a value on its way to being parsed and sent. A single
     * separator is genuinely ambiguous ("1,234" is 1.234 in one locale and 1234 in
     * another), so that case is left reading as a decimal mark, exactly as before.
     */
    fun formatFiatWithoutComma(fiatValue: String): String {
        val cleaned = normalizeSeparators(fiatValue)

        // Limit to 8 decimal places to prevent rounding errors in Coin.parseCoin
        val decimalIndex = cleaned.indexOf('.')
        return if (decimalIndex != -1 && cleaned.length > decimalIndex + 9) {
            cleaned.substring(0, decimalIndex + 9)
        } else {
            cleaned
        }
    }

    private fun normalizeSeparators(value: String): String {
        val lastSeparator = value.indexOfLast { it in SEPARATORS }

        if (lastSeparator < 0) {
            return value
        }

        // A repeated character cannot be the decimal mark, so it is grouping throughout.
        val decimalIndex = if (value.count { it == value[lastSeparator] } == 1) lastSeparator else -1

        return buildString(value.length) {
            value.forEachIndexed { index, character ->
                when {
                    index == decimalIndex -> append('.')
                    character in SEPARATORS -> Unit // grouping mark: drop it
                    else -> append(character)
                }
            }
        }
    }

    fun getLocalCurrencySymbol(currencyCode: String?): String? {
        val numberFormat = NumberFormat.getCurrencyInstance(getDeviceLocale())
        val currency = Currency.getInstance(currencyCode)
        numberFormat.currency = currency
        return currency.getSymbol(getDeviceLocale())
    }

    /**
     * Ordered list of candidate icon URLs for a coin, to be tried in sequence until
     * one loads.
     *
     * When a SwapKit [identifier] is supplied (e.g. "ETH.USDC-0x...") the SwapKit
     * token-list bucket is tried first: it keys off the full chain-qualified
     * identifier, so it disambiguates same-ticker tokens across chains and has the
     * widest coverage of the assets the wallet can route. The bucket only serves
     * fully-lowercased identifier filenames. CoinCap (broader generic coverage,
     * includes Solana memecoins like WIF that the older jsupa repo lacks) and the
     * jsupa repo follow as ticker-keyed fallbacks.
     *
     * Some assets (e.g. Solana tokens like $WIF) carry a leading '$' or other
     * non-alphanumeric characters in their symbol; the ticker-keyed hosts key off
     * the plain ticker (wif), so strip anything that isn't alphanumeric.
     */
    fun getCoinIconUrls(code: String, identifier: String? = null): List<String> {
        val sanitized = code.lowercase(Locale.getDefault()).filter { it.isLetterOrDigit() }
        val urls = mutableListOf<String>()
        if (!identifier.isNullOrEmpty()) {
            val swapKitId = identifier.lowercase(Locale.getDefault())
            urls.add("https://storage.googleapis.com/token-list-swapkit/images/$swapKitId.png")
        }
        urls.add("https://assets.coincap.io/assets/icons/$sanitized@2x.png")
        urls.add("https://raw.githubusercontent.com/jsupa/crypto-icons/main/icons/$sanitized.png")
        return urls
    }

    fun getCoinIcon(code: String, identifier: String? = null): String {
        return getCoinIconUrls(code, identifier).first()
    }

    /**
     *
     * @param percent The number as a double where 0.01 is 1.00%
     * @return
     */
    fun formatPercent(fraction: Double): String? {
        // the formatter translates 0.01 to 1.00%
        return percentFormat.format(fraction)
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

    fun getCurrencyDigits(code: String): Int {
        val currency: Currency? = Currency.getInstance(code)
        return currency?.defaultFractionDigits ?: 2
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

    val dashFormat: MonetaryFormat
        get() = MonetaryFormat().withLocale(getDeviceLocale()).noCode().minDecimals(0).repeatOptionalDecimals(1, 8)
    val fiatFormat: MonetaryFormat
        get() = MonetaryFormat().withLocale(getDeviceLocale()).noCode().minDecimals(getCurrencyDigits())

    fun toLocalizedString(value: BigDecimal, isCrypto: Boolean, currencyCode: String): String {
        return if (isCrypto) {
            dashFormat.format(value.toCoin())
        } else {
            fiatFormat.format(value.toFiat(currencyCode))
        }.toString()
    }
}
