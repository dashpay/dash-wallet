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

import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.ui.CurrencyTextView
import org.dash.wallet.common.util.MonetarySpannable
import org.dash.wallet.common.util.currencySymbol
import org.dash.wallet.common.util.isCurrencyFirst
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringNoCode
import org.dash.wallet.common.util.toFormattedStringRoundUp
import java.math.BigDecimal

// ---------------------------------------------------------------------------------------------
// Bridging overloads so wallet code can keep feeding dashj money values into the (now neutral)
// common UI/formatting APIs. Each shim only converts types; behavior is unchanged.
// ---------------------------------------------------------------------------------------------

fun CurrencyTextView.setAmount(amount: org.bitcoinj.core.Monetary?) =
    setAmount(amount?.toNeutralMonetary())

fun CurrencyTextView.setFiatAmount(
    amount: org.bitcoinj.core.Coin,
    exchangeRate: org.bitcoinj.utils.ExchangeRate?,
    format: MonetaryFormat,
    exchangeCurrencyCode: String?
) = setFiatAmount(
    amount.toNeutralCoin(),
    exchangeRate?.let { org.dash.wallet.common.money.ExchangeRate(it.coin.toNeutralCoin(), it.fiat.toNeutralFiat()) },
    format,
    exchangeCurrencyCode
)

/** [MonetarySpannable] over a dashj monetary value. */
fun MonetarySpannable(format: MonetaryFormat, signed: Boolean, monetary: org.bitcoinj.core.Monetary?): MonetarySpannable =
    MonetarySpannable(format, signed, monetary?.toNeutralMonetary())

/** [MonetarySpannable] over a dashj monetary value. */
fun MonetarySpannable(format: MonetaryFormat, monetary: org.bitcoinj.core.Monetary?): MonetarySpannable =
    MonetarySpannable(format, monetary?.toNeutralMonetary())

// MonetaryExt twins for dashj receivers
fun org.bitcoinj.core.Coin.toBigDecimal(): BigDecimal = toNeutralCoin().toBigDecimal()
fun org.bitcoinj.utils.Fiat.toBigDecimal(): BigDecimal = toNeutralFiat().toBigDecimal()
fun org.bitcoinj.utils.Fiat.toDouble(): Double = toBigDecimal().toDouble()
fun org.bitcoinj.utils.Fiat.toFormattedString(): String = toNeutralFiat().toFormattedString()
fun org.bitcoinj.utils.Fiat.toFormattedStringRoundUp(): String = toNeutralFiat().toFormattedStringRoundUp()
fun org.bitcoinj.utils.Fiat.toFormattedStringNoCode(): String = toNeutralFiat().toFormattedStringNoCode()
fun org.bitcoinj.utils.Fiat.isCurrencyFirst(): Boolean = toNeutralFiat().isCurrencyFirst()
val org.bitcoinj.utils.Fiat.currencySymbol: String
    get() = toNeutralFiat().currencySymbol
