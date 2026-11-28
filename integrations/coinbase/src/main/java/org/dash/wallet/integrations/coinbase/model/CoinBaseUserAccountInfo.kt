/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.fiatToDash
import org.dash.wallet.common.util.toFormattedString
import java.math.BigDecimal
import java.math.RoundingMode

@Parcelize
data class CoinBaseUserAccountDataUIModel(
    override val coinbaseAccount: CoinbaseAccount,
    val currencyToCryptoCurrencyExchangeRate: BigDecimal,
    override val currencyToDashExchangeRate: BigDecimal,
    override val currencyToUSDExchangeRate: BigDecimal
) : CoinbaseToDashExchangeRateUIModel(
    coinbaseAccount,
    currencyToDashExchangeRate,
    currencyToUSDExchangeRate
) {
    fun getCryptoToDashExchangeRate(): BigDecimal {
        return currencyToDashExchangeRate / currencyToCryptoCurrencyExchangeRate
    }
}

fun CoinBaseUserAccountDataUIModel.getCoinBaseExchangeRateConversion(
    currentExchangeRate: ExchangeRate
): Pair<String, Dash> {
    val cleanedValue =
        this.coinbaseAccount.availableBalance.value.toBigDecimal() /
            this.currencyToCryptoCurrencyExchangeRate
    val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

    val fiatAmount = FiatValue.parseFiat(currentExchangeRate.currencyCode, bd.toString())
    val dashAmount = currentExchangeRate.fiatToDash(fiatAmount)

    return Pair(fiatAmount.toFormattedString(), dashAmount)
}

@Parcelize
open class CoinbaseToDashExchangeRateUIModel(
    open val coinbaseAccount: CoinbaseAccount,
    open val currencyToDashExchangeRate: BigDecimal,
    open val currencyToUSDExchangeRate: BigDecimal
): Parcelable {
    companion object {
        val EMPTY = CoinbaseToDashExchangeRateUIModel(
            CoinbaseAccount.EMPTY,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )
    }
}
