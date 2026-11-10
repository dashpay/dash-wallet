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

package org.dash.wallet.integrations.coinbase.viewmodels

import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * Pure decision logic behind the "enter amount to transfer" screen.
 *
 * MO-995: the screen used to derive the amount text, the span that styles its currency
 * label, and the keypad's decimal limit from three different places -- the picker's
 * visible LABEL, the ViewModel's `formattedValue`/`fiatBalance`/`inputValue` fields, and
 * a hard-coded threshold keyed off the picker index. A stale label was enough to make
 * them disagree, which showed up as a crash, a frozen keypad and a mislabelled amount.
 *
 * Everything here takes plain values and returns plain values so it can be unit tested
 * on the host JVM, and so [EnterAmountToTransferViewModel.applyNewValue] can publish the
 * text, the span and the decimal limit together, from the one branch it actually took.
 */

/** Which kind of money the amount currently on screen is denominated in. */
internal enum class AmountMode {
    DASH,
    FIAT;

    /** Digits allowed after the decimal separator in this mode. */
    val maxDecimals: Int
        get() = if (this == FIAT) FIAT_MAX_DECIMALS else DASH_MAX_DECIMALS
}

/**
 * The decimal separator every value in this pipeline is stored, counted, gated and parsed
 * with.
 *
 * It is the character the numeric keypad inserts, and the one `BigDecimal`, `Coin.parseCoin`
 * and `Fiat.parseFiat` all require. The *device locale's* separator is a display concern
 * only. Mixing the two is what made the decimal gate inert on every comma-decimal locale:
 * the keypad wrote "12.34" while the gate looked for "12,34", found no separator, and let
 * a fiat amount grow to 8 decimal places (MO-995 follow-up).
 */
internal const val CANONICAL_SEPARATOR = '.'

/** Fiat is quoted to cents. */
internal const val FIAT_MAX_DECIMALS = 2

/** Duffs give Dash 8 decimal places. */
internal const val DASH_MAX_DECIMALS = 8

/** The slice of the amount text that renders in the smaller currency style. */
internal data class AmountCurrencySpan(val from: Int, val to: Int)

/**
 * The mode the screen should format in.
 *
 * Deliberately takes the picker's boolean, not its title: the title is a currency code
 * that arrives asynchronously, so comparing it against the selected currency produced a
 * false "these differ" whenever the label was still the seeded default (MO-995 C/D).
 */
internal fun amountMode(isFiatSelected: Boolean): AmountMode =
    if (isFiatSelected) AmountMode.FIAT else AmountMode.DASH

/** Number of digits after [separator] in [input]; 0 when there is no separator. */
internal fun decimalCount(input: String, separator: Char): Int {
    val index = input.indexOf(separator)
    return if (index < 0) 0 else input.length - index - 1
}

/**
 * True when typing one more digit onto [current] would push it past [maxDecimals]
 * decimal places, so the keypad should swallow the press.
 *
 * [maxDecimals] must come from the mode that produced the text now on screen. Gating
 * DASH-scale text on the fiat limit of 2 rejects every digit and looks like a dead
 * keypad (MO-995 B).
 */
internal fun wouldExceedDecimals(current: String, separator: Char, maxDecimals: Int): Boolean =
    current.indexOf(separator) >= 0 && decimalCount(current, separator) + 1 > maxDecimals

/**
 * Bounds of the currency label inside [text], given the [amountPart] that [text] was
 * built around.
 *
 * Both branches measure against the same [amountPart] the formatter just produced, which
 * is what keeps them consistent: the old code measured the currency-last fiat branch
 * against the raw `inputValue` instead, and since `fiatBalance` can be SHORTER than the
 * raw input (a conversion rounds to 2 places), `from` overshot `to` and
 * `Spannable.setSpan` threw (MO-995 A).
 *
 * Returns null when [amountPart] cannot describe [text] -- an unstyled amount is a far
 * better outcome than a crash.
 */
internal fun amountCurrencySpan(
    text: String,
    amountPart: String,
    isCurrencyFirst: Boolean
): AmountCurrencySpan? {
    if (amountPart.isEmpty() || amountPart.length >= text.length) return null

    val span = if (isCurrencyFirst) {
        // text == "<symbol> <amountPart>" -- style the leading symbol.
        AmountCurrencySpan(0, text.length - amountPart.length)
    } else {
        // text == "<amountPart> <symbol>" -- style the trailing symbol or code.
        AmountCurrencySpan(amountPart.length, text.length)
    }

    return if (span.from < 0 || span.to > text.length || span.from >= span.to) null else span
}

/**
 * Everything the view needs to render one amount, produced by a single pass so the text,
 * the bounds that style its currency label and the keypad's decimal limit can never
 * describe different amounts.
 */
internal data class TransferAmount(
    val text: String,
    /** [text] without its currency symbol or code. */
    val amountPart: String,
    val currencySpan: AmountCurrencySpan?,
    /** The raw value the rest of the screen reads back; re-scaled in fiat mode. */
    val inputValue: String,
    val fiatAmount: Fiat?,
    val mode: AmountMode
) {
    /** Decimal places the keypad may accept for [text]. */
    val maxDecimals: Int
        get() = mode.maxDecimals
}

/**
 * Formats [value] for the transfer screen.
 *
 * Takes [isFiat] -- the boolean the currency picker sets -- rather than the picker's
 * visible title. The title is the selected currency code, loaded asynchronously, so
 * comparing it against [localCurrencyCode] reported "these differ" for as long as the
 * label was still the seeded default and pushed fiat amounts down the DASH branch.
 */
internal fun transferAmount(
    value: String,
    isFiat: Boolean,
    localCurrencyCode: String,
    /** The DEVICE locale's separator -- used only to normalise [fiatFormat]'s output. */
    decimalSeparator: Char,
    fiatFormat: MonetaryFormat
): TransferAmount {
    val mode = amountMode(isFiat)
    var inputValue = value.ifEmpty { CoinbaseConstants.VALUE_ZERO }

    val amountPart: String
    val text: String
    val isCurrencyFirst: Boolean
    val fiatAmount: Fiat?

    if (mode == AmountMode.FIAT) {
        // A value arriving from a conversion (MAX, or a currency switch) is scaled to 8
        // places, but fiat is quoted to cents -- and leaving the extra digits in would let
        // the keypad gate and the rendered figure disagree about how precise the amount is.
        // Hand the re-scaled value back too: the keypad and the transfer button read it.
        if (decimalCount(inputValue, CANONICAL_SEPARATOR) > FIAT_MAX_DECIMALS) {
            inputValue = truncateToDecimals(inputValue, FIAT_MAX_DECIMALS)
        }
        val fiat = parseFiatOrZero(localCurrencyCode, GenericUtils.formatFiatWithoutComma(inputValue))
        fiatAmount = fiat
        isCurrencyFirst = fiat.isCurrencyFirst()
        amountPart = if (decimalCount(inputValue, CANONICAL_SEPARATOR) >= FIAT_MAX_DECIMALS) {
            // fiatFormat is locale-aware and emits the locale's separator; bring it back to
            // canonical form so the value the keypad reads next stays parseable.
            canonicalizeSeparator(fiatFormat.format(fiat).toString(), decimalSeparator)
        } else {
            inputValue
        }
        val symbol = GenericUtils.getLocalCurrencySymbol(localCurrencyCode)
        text = if (isCurrencyFirst) "$symbol $amountPart" else "$amountPart $symbol"
    } else {
        fiatAmount = null
        isCurrencyFirst = false
        amountPart = formatDashInput(inputValue)
        text = "$amountPart ${Constants.DASH_CURRENCY}"
    }

    return TransferAmount(
        text = text,
        amountPart = amountPart,
        currencySpan = amountCurrencySpan(text, amountPart, isCurrencyFirst),
        inputValue = inputValue,
        fiatAmount = fiatAmount,
        mode = mode
    )
}

private fun formatDashInput(input: String): String {
    val isFraction = input.indexOf(CANONICAL_SEPARATOR) > -1
    val lengthOfDecimalPart = input.length - input.indexOf(CANONICAL_SEPARATOR)
    val rawFormattedValue = if (input.contains("E")) {
        DecimalFormat("########.########").format(input.toDouble())
    } else {
        input
    }

    // Limit to 8 decimal places max using BigDecimal for proper rounding
    return if (rawFormattedValue.endsWith(".") || rawFormattedValue.isEmpty()) {
        // Don't process incomplete decimal entries
        rawFormattedValue
    } else if (isFraction && lengthOfDecimalPart <= DASH_MAX_DECIMALS) {
        // Preserve input format for incomplete decimal entries like "0.0"
        rawFormattedValue
    } else {
        try {
            rawFormattedValue.toBigDecimal()
                .setScale(DASH_MAX_DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        } catch (e: Exception) {
            rawFormattedValue
        }
    }
}

/**
 * Truncates -- never rounds up -- to [maxDecimals] places, so a MAX transfer cannot end up
 * asking for more than the balance it was derived from.
 */
internal fun truncateToDecimals(input: String, maxDecimals: Int): String =
    GenericUtils.formatFiatWithoutComma(input).toBigDecimalOrNull()
        ?.setScale(maxDecimals, RoundingMode.DOWN)
        ?.toPlainString()
        ?: input

/**
 * Rewrites [value] from the device locale's decimal separator to [CANONICAL_SEPARATOR].
 *
 * Only the decimal mark moves: bitcoinj's `MonetaryFormat` emits no grouping separator, so
 * the result is the same length as [value] and the span bounds measured against it stay
 * valid for the localised text.
 */
internal fun canonicalizeSeparator(value: String, localeSeparator: Char): String =
    if (localeSeparator == CANONICAL_SEPARATOR) value else value.replace(localeSeparator, CANONICAL_SEPARATOR)

/** Partial input such as "" or "0." must render a zero, not throw out of the screen. */
internal fun parseFiatOrZero(currencyCode: String, value: String): Fiat =
    try {
        Fiat.parseFiat(currencyCode, value)
    } catch (e: Exception) {
        Fiat.valueOf(currencyCode, 0)
    }
