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

package org.dash.wallet.integrations.maya.model

import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.utils.MayaConstants
import java.math.BigDecimal
data class PoolInfo(
    val annualPercentageRate: String,
    val asset: String,
    val assetDepth: String,
    val assetPrice: String,
    val assetPriceUSD: String,
    val liquidityUnits: String,
    val poolAPY: String,
    val runeDepth: String,
    val status: String,
    val synthSupply: String,
    val synthUnits: String,
    val units: String,
    val volume24h: String
) {
    var assetPriceFiat: Fiat = Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 0)

    fun getAssetPriceUSD(): Fiat {
        return Fiat.parseFiatInexact("USD", assetPriceUSD)
    }

    fun setAssetPrice(fiatExchangeRate: Fiat) {
        assetPriceFiat = BigDecimal(assetPriceUSD)
            .multiply(fiatExchangeRate.toBigDecimal())
            .toFiat(fiatExchangeRate.currencyCode)
    }

    val currencyCode: String
        get() {
            val codeIndex = asset.indexOf('.')
            val smartContract = asset.indexOf('-')
            return asset.substring(codeIndex + 1, if (smartContract != -1) smartContract else asset.length)
        }
}
