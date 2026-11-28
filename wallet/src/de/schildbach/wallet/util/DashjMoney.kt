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

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.ExchangeRate as DashjExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue

// ---------------------------------------------------------------------------------------------
// Conversions between dashj money/hash types and the neutral types in common. Wallet-module
// only; feature/integration modules never see dashj.
// ---------------------------------------------------------------------------------------------

fun Dash.toCoin(): Coin = Coin.valueOf(duffs)
fun Coin.toDash(): Dash = Dash(value)

fun FiatValue.toFiat(): Fiat = Fiat.valueOf(currencyCode, value)
fun Fiat.toFiatValue(): FiatValue = FiatValue(currencyCode, value)

// neutral money core <-> dashj
fun org.dash.wallet.common.money.Coin.toDashjCoin(): Coin = Coin.valueOf(value)
fun Coin.toNeutralCoin(): org.dash.wallet.common.money.Coin = org.dash.wallet.common.money.Coin.valueOf(value)
fun org.dash.wallet.common.money.Fiat.toDashjFiat(): Fiat = Fiat.valueOf(currencyCode, value)
fun Fiat.toNeutralFiat(): org.dash.wallet.common.money.Fiat =
    org.dash.wallet.common.money.Fiat.valueOf(currencyCode, value)

fun org.dash.wallet.common.money.Monetary.toDashjMonetary(): org.bitcoinj.core.Monetary = when (this) {
    is org.dash.wallet.common.money.Coin -> toDashjCoin()
    is org.dash.wallet.common.money.Fiat -> toDashjFiat()
    else -> throw IllegalArgumentException("unknown monetary type: $javaClass")
}

fun org.bitcoinj.core.Monetary.toNeutralMonetary(): org.dash.wallet.common.money.Monetary = when (this) {
    is Coin -> toNeutralCoin()
    is Fiat -> toNeutralFiat()
    else -> throw IllegalArgumentException("unknown monetary type: $javaClass")
}

/** dashj exchange rate (1 DASH = entity rate) from the app's ExchangeRate entity. */
fun ExchangeRate.toDashjExchangeRate(): DashjExchangeRate =
    DashjExchangeRate(Coin.COIN, fiat.toDashjFiat())

// hashes
fun Sha256Hash.toTxId(): TxId = TxId.wrap(bytes)
fun TxId.toSha256Hash(): Sha256Hash = Sha256Hash.wrap(bytes)

// ---------------------------------------------------------------------------------------------
// Bridging overloads so wallet code can keep passing dashj monetary values to the (now neutral)
// formatting APIs in common.
// ---------------------------------------------------------------------------------------------

/** Formats a dashj [org.bitcoinj.core.Monetary] with a neutral MonetaryFormat. */
fun org.dash.wallet.common.money.MonetaryFormat.format(monetary: org.bitcoinj.core.Monetary): CharSequence =
    format(monetary.toNeutralMonetary())

/** Parses with a neutral MonetaryFormat, returning a dashj Coin. */
fun org.dash.wallet.common.money.MonetaryFormat.parseDashjCoin(str: String): Coin = Coin.valueOf(parse(str).value)

/** Java-callable twins of the (now neutral) common AddressUtil, on dashj types. */
object AddressUtilsDashj {
    /** Mirrors the old `AddressUtil.getParametersFromAddress`: testnet-space addresses resolve to [current]. */
    @JvmStatic
    fun paramsFromAddress(address: String, current: org.bitcoinj.core.NetworkParameters): org.bitcoinj.core.NetworkParameters {
        val params = org.bitcoinj.core.Address.getParametersFromAddress(address)
        return if (params == org.bitcoinj.params.TestNet3Params.get()) current else params
    }
}
