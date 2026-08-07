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

package de.schildbach.wallet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.RoundingMode
import java.util.Locale

/**
 * Byte-for-byte parity between the self-contained money core in common
 * (`org.dash.wallet.common.money`) and the dashj originals it replaced. Every formatted
 * string, parsed value and conversion must be identical.
 */
class MoneyFormattingParityTest {

    private val values = longArrayOf(
        0, 1, -1, 5, 99, 100, 546, 1000, 12345, 20002, 54321, 100000, 1050000,
        12345678, 100000000, 123456789, 149999999, 150000000, 150000001,
        1000000000, 123456789012, 999999999999, 2200000000000000, -123456789,
        -100000000, -149999999, 43000000
    )

    private data class FormatPair(
        val dashj: org.bitcoinj.utils.MonetaryFormat,
        val neutral: org.dash.wallet.common.money.MonetaryFormat,
        val name: String
    )

    private val formatPairs: List<FormatPair> = buildList {
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat(), org.dash.wallet.common.money.MonetaryFormat(), "default"))
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat(true), org.dash.wallet.common.money.MonetaryFormat(true), "symbol"))
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat.BTC, org.dash.wallet.common.money.MonetaryFormat.BTC, "BTC"))
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat.MBTC, org.dash.wallet.common.money.MonetaryFormat.MBTC, "MBTC"))
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat.UBTC, org.dash.wallet.common.money.MonetaryFormat.UBTC, "UBTC"))
        add(FormatPair(org.bitcoinj.utils.MonetaryFormat.FIAT, org.dash.wallet.common.money.MonetaryFormat.FIAT, "FIAT"))
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat.BTC.minDecimals(2).repeatOptionalDecimals(1, 6).postfixCode(),
                org.dash.wallet.common.money.MonetaryFormat.BTC.minDecimals(2).repeatOptionalDecimals(1, 6).postfixCode(),
                "friendly"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat.BTC.minDecimals(0).repeatOptionalDecimals(1, 8).noCode(),
                org.dash.wallet.common.money.MonetaryFormat.BTC.minDecimals(0).repeatOptionalDecimals(1, 8).noCode(),
                "plain"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat.BTC.minDecimals(1).repeatOptionalDecimals(1, 3).postfixCode(),
                org.dash.wallet.common.money.MonetaryFormat.BTC.minDecimals(1).repeatOptionalDecimals(1, 3).postfixCode(),
                "crowdnode"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().noCode().minDecimals(0).optionalDecimals(),
                org.dash.wallet.common.money.MonetaryFormat().noCode().minDecimals(0).optionalDecimals(),
                "no-optionals"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().noCode().minDecimals(8),
                org.dash.wallet.common.money.MonetaryFormat().noCode().minDecimals(8),
                "min8"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().shift(3).minDecimals(2).optionalDecimals(2),
                org.dash.wallet.common.money.MonetaryFormat().shift(3).minDecimals(2).optionalDecimals(2),
                "shift3"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().noCode().roundingMode(RoundingMode.DOWN),
                org.dash.wallet.common.money.MonetaryFormat().noCode().roundingMode(RoundingMode.DOWN),
                "round-down"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().noCode().roundingMode(RoundingMode.UP),
                org.dash.wallet.common.money.MonetaryFormat().noCode().roundingMode(RoundingMode.UP),
                "round-up"
            )
        )
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale("ar"))) {
            add(
                FormatPair(
                    org.bitcoinj.utils.MonetaryFormat().withLocale(locale).noCode().minDecimals(2),
                    org.dash.wallet.common.money.MonetaryFormat().withLocale(locale).noCode().minDecimals(2),
                    "locale-$locale"
                )
            )
            add(
                FormatPair(
                    org.bitcoinj.utils.MonetaryFormat().withLocale(locale).withGroupingSeparator().noCode(),
                    org.dash.wallet.common.money.MonetaryFormat().withLocale(locale).withGroupingSeparator().noCode(),
                    "grouped-$locale"
                )
            )
        }
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().code(0, "USD").prefixCode(),
                org.dash.wallet.common.money.MonetaryFormat().code(0, "USD").prefixCode(),
                "usd-prefix"
            )
        )
        add(
            FormatPair(
                org.bitcoinj.utils.MonetaryFormat().code(0, "EUR").postfixCode().codeSeparator('_'),
                org.dash.wallet.common.money.MonetaryFormat().code(0, "EUR").postfixCode().codeSeparator('_'),
                "eur-postfix-sep"
            )
        )
    }

    @Test
    fun coinFormatting_identicalToDashj() {
        for (pair in formatPairs) {
            for (value in values) {
                val expected = pair.dashj.format(org.bitcoinj.core.Coin.valueOf(value)).toString()
                val actual = pair.neutral.format(org.dash.wallet.common.money.Coin.valueOf(value)).toString()
                assertEquals("format ${pair.name} of $value", expected, actual)
            }
        }
    }

    @Test
    fun fiatFormatting_identicalToDashj() {
        for (pair in formatPairs) {
            for (value in values) {
                val expected = pair.dashj.format(org.bitcoinj.utils.Fiat.valueOf("USD", value)).toString()
                val actual = pair.neutral.format(org.dash.wallet.common.money.Fiat.valueOf("USD", value)).toString()
                assertEquals("fiat format ${pair.name} of $value", expected, actual)
            }
        }
    }

    @Test
    fun plainAndFriendlyStrings_identicalToDashj() {
        for (value in values) {
            assertEquals(
                org.bitcoinj.core.Coin.valueOf(value).toPlainString(),
                org.dash.wallet.common.money.Coin.valueOf(value).toPlainString()
            )
            assertEquals(
                org.bitcoinj.core.Coin.valueOf(value).toFriendlyString(),
                org.dash.wallet.common.money.Coin.valueOf(value).toFriendlyString()
            )
            assertEquals(
                org.bitcoinj.utils.Fiat.valueOf("USD", value).toPlainString(),
                org.dash.wallet.common.money.Fiat.valueOf("USD", value).toPlainString()
            )
            assertEquals(
                org.bitcoinj.utils.Fiat.valueOf("USD", value).toFriendlyString(),
                org.dash.wallet.common.money.Fiat.valueOf("USD", value).toFriendlyString()
            )
        }
    }

    @Test
    fun parsing_identicalToDashj() {
        val strings = listOf(
            "0", "1", "0.1", "0.023", "1.23", "0.00000001", "21.00000001", "-1.5", "100", "0.43",
            "1.123456789", "1e3", "0.000000001", "junk", "", "1.2.3", "٥", "1000000000000"
        )
        for (str in strings) {
            val expected = runCatching { org.bitcoinj.core.Coin.parseCoin(str).value }
            val actual = runCatching { org.dash.wallet.common.money.Coin.parseCoin(str).value }
            assertEquals("parseCoin($str) outcome", expected.isSuccess, actual.isSuccess)
            if (expected.isSuccess) {
                assertEquals("parseCoin($str)", expected.getOrNull(), actual.getOrNull())
            }

            val expectedFiat = runCatching { org.bitcoinj.utils.Fiat.parseFiat("USD", str).value }
            val actualFiat = runCatching { org.dash.wallet.common.money.Fiat.parseFiat("USD", str).value }
            assertEquals("parseFiat($str) outcome", expectedFiat.isSuccess, actualFiat.isSuccess)
            if (expectedFiat.isSuccess) {
                assertEquals("parseFiat($str)", expectedFiat.getOrNull(), actualFiat.getOrNull())
            }

            val fmtExpected = runCatching { org.bitcoinj.utils.MonetaryFormat().noCode().parse(str).value }
            val fmtActual = runCatching { org.dash.wallet.common.money.MonetaryFormat().noCode().parse(str).value }
            assertEquals("format.parse($str) outcome", fmtExpected.isSuccess, fmtActual.isSuccess)
            if (fmtExpected.isSuccess) {
                assertEquals("format.parse($str)", fmtExpected.getOrNull(), fmtActual.getOrNull())
            }
        }
    }

    @Test
    fun exchangeRateConversions_identicalToDashj() {
        val rates = longArrayOf(1, 99, 2438000000, 100000000, 12345678901)
        for (rate in rates) {
            val dashjRate = org.bitcoinj.utils.ExchangeRate(
                org.bitcoinj.core.Coin.COIN,
                org.bitcoinj.utils.Fiat.valueOf("USD", rate)
            )
            val neutralRate = org.dash.wallet.common.money.ExchangeRate(
                org.dash.wallet.common.money.Coin.COIN,
                org.dash.wallet.common.money.Fiat.valueOf("USD", rate)
            )
            for (value in values) {
                val expected = runCatching { dashjRate.coinToFiat(org.bitcoinj.core.Coin.valueOf(value)).value }
                val actual = runCatching {
                    neutralRate.coinToFiat(org.dash.wallet.common.money.Coin.valueOf(value)).value
                }
                assertEquals("coinToFiat($value @ $rate) outcome", expected.isSuccess, actual.isSuccess)
                assertEquals("coinToFiat($value @ $rate)", expected.getOrNull(), actual.getOrNull())

                val expectedCoin = runCatching { dashjRate.fiatToCoin(org.bitcoinj.utils.Fiat.valueOf("USD", value)).value }
                val actualCoin = runCatching {
                    neutralRate.fiatToCoin(org.dash.wallet.common.money.Fiat.valueOf("USD", value)).value
                }
                assertEquals("fiatToCoin($value @ $rate) outcome", expectedCoin.isSuccess, actualCoin.isSuccess)
                assertEquals("fiatToCoin($value @ $rate)", expectedCoin.getOrNull(), actualCoin.getOrNull())
            }
        }
    }

    @Test
    fun hashCodeAndEquals_identicalToDashj() {
        // `values` includes >32-bit magnitudes (123456789012, 999999999999, 2200000000000000)
        // where a wrong hash formula diverges from dashj.
        for (value in values) {
            assertEquals(
                "Coin.hashCode($value)",
                org.bitcoinj.core.Coin.valueOf(value).hashCode(),
                org.dash.wallet.common.money.Coin.valueOf(value).hashCode()
            )
            for (code in listOf("USD", "EUR", "JPY", "VES")) {
                assertEquals(
                    "Fiat.hashCode($code, $value)",
                    org.bitcoinj.utils.Fiat.valueOf(code, value).hashCode(),
                    org.dash.wallet.common.money.Fiat.valueOf(code, value).hashCode()
                )
            }
        }
        // equals parity spot checks
        assertEquals(
            org.dash.wallet.common.money.Fiat.valueOf("USD", 123456789012),
            org.dash.wallet.common.money.Fiat.valueOf("USD", 123456789012)
        )
        assertTrue(
            org.dash.wallet.common.money.Fiat.valueOf("USD", 1) !=
                org.dash.wallet.common.money.Fiat.valueOf("EUR", 1)
        )
        assertTrue(
            org.dash.wallet.common.money.Fiat.valueOf("USD", 1) !=
                org.dash.wallet.common.money.Fiat.valueOf("USD", 2)
        )
        assertEquals(
            org.dash.wallet.common.money.Coin.valueOf(2200000000000000),
            org.dash.wallet.common.money.Coin.valueOf(2200000000000000)
        )
    }

    @Test
    fun arithmetic_identicalToDashj() {
        // spot-check overflow behavior parity
        val big = Long.MAX_VALUE / 2 + 1
        assertTrue(
            runCatching { org.bitcoinj.core.Coin.valueOf(big).add(org.bitcoinj.core.Coin.valueOf(big)) }.isFailure
        )
        assertTrue(
            runCatching {
                org.dash.wallet.common.money.Coin.valueOf(big).add(org.dash.wallet.common.money.Coin.valueOf(big))
            }.isFailure
        )
        assertEquals(
            org.bitcoinj.core.Coin.valueOf(3, 25).value,
            org.dash.wallet.common.money.Coin.valueOf(3, 25).value
        )
    }
}
