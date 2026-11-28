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

import org.dash.wallet.common.data.entity.ExchangeRate

// ---------------------------------------------------------------------------------------------
// Adapters between the value-class money types ([Dash]/[FiatValue]) and the self-contained
// money core ([Coin]/[Fiat]).
// ---------------------------------------------------------------------------------------------

fun Dash.toCoin(): Coin = Coin.valueOf(duffs)
fun Coin.toDash(): Dash = Dash(value)

fun FiatValue.toFiat(): Fiat = Fiat.valueOf(currencyCode, value)
fun Fiat.toFiatValue(): FiatValue = FiatValue(currencyCode, value)

// ---------------------------------------------------------------------------------------------
// Neutral conversion API on the app's ExchangeRate entity. Delegates to the ExchangeRate port
// so rounding matches dashj exactly.
// ---------------------------------------------------------------------------------------------

val ExchangeRate.fiatValue: FiatValue?
    get() = rate?.let { fiat.toFiatValue() }

/** Converts a Dash amount to fiat at this rate. Mirrors `ExchangeRate.coinToFiat`. */
fun ExchangeRate.dashToFiat(amount: Dash): FiatValue {
    return ExchangeRateCalc(Coin.COIN, fiat).coinToFiat(amount.toCoin()).toFiatValue()
}

/** Converts a fiat amount to Dash at this rate. Mirrors `ExchangeRate.fiatToCoin`. */
fun ExchangeRate.fiatToDash(amount: FiatValue): Dash {
    return ExchangeRateCalc(Coin.COIN, fiat).fiatToCoin(amount.toFiat()).toDash()
}

/**
 * Treats this fiat amount as the price of one Dash and converts [amount] to fiat.
 * Mirrors `ExchangeRate(fiat).coinToFiat(coin)` for rates that aren't
 * backed by the app's ExchangeRate entity (e.g. rates restored from transaction metadata).
 */
fun FiatValue.dashToFiat(amount: Dash): FiatValue {
    return ExchangeRateCalc(Coin.COIN, toFiat()).coinToFiat(amount.toCoin()).toFiatValue()
}

private typealias ExchangeRateCalc = org.dash.wallet.common.money.ExchangeRate
