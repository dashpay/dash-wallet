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

import org.dash.wallet.common.Configuration
import java.math.RoundingMode
import java.util.Locale

/**
 * Immutable monetary formatter for [Dash] and [FiatValue] amounts. Mirrors the fluent API of
 * dashj's `MonetaryFormat` (and delegates to the self-contained [MonetaryFormat] port internally,
 * so output is identical), without exposing dashj types to feature/integration modules.
 */
class MoneyFormat internal constructor(internal val delegate: MonetaryFormat) {

    companion object {
        /** Mirrors [MonetaryFormat.BTC]: standard Dash denomination format. */
        val BTC = MoneyFormat(MonetaryFormat.BTC)
    }

    constructor() : this(MonetaryFormat())

    fun noCode() = MoneyFormat(delegate.noCode())
    fun postfixCode() = MoneyFormat(delegate.postfixCode())
    fun minDecimals(minDecimals: Int) = MoneyFormat(delegate.minDecimals(minDecimals))
    fun optionalDecimals(vararg groups: Int) = MoneyFormat(delegate.optionalDecimals(*groups))
    fun repeatOptionalDecimals(decimals: Int, repetitions: Int) =
        MoneyFormat(delegate.repeatOptionalDecimals(decimals, repetitions))
    fun withLocale(locale: Locale) = MoneyFormat(delegate.withLocale(locale))
    fun roundingMode(roundingMode: RoundingMode) = MoneyFormat(delegate.roundingMode(roundingMode))

    fun format(amount: Dash): CharSequence = delegate.format(Coin.valueOf(amount.duffs))
    fun format(amount: FiatValue): CharSequence = delegate.format(Fiat.valueOf(amount.currencyCode, amount.value))

    /** Mirrors [MonetaryFormat.parse]; throws on unparseable input. */
    fun parseDash(str: String): Dash = Dash(delegate.parse(str).value)
    fun parseFiat(currencyCode: String, str: String): FiatValue {
        val fiat = delegate.parseFiat(currencyCode, str)
        return FiatValue(fiat.currencyCode, fiat.value)
    }
}

/**
 * Neutral counterpart of [Configuration.getFormat] for feature/integration modules
 * that must not depend on dashj. Same user-configured Dash format, wrapped in [MoneyFormat].
 */
val Configuration.moneyFormat: MoneyFormat
    get() = MoneyFormat(format)
