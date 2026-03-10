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

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.utils.MayaConstants
import java.math.BigDecimal

@Parcelize
data class PoolInfo(
    @SerializedName("annualPercentageRate") val annualPercentageRate: String = "",
    @SerializedName("asset") val asset: String = "",
    @SerializedName("assetDepth") val assetDepth: String = "",
    @SerializedName("assetPrice") val assetPrice: String = "",
    @SerializedName("assetPriceUSD") val assetPriceUSD: String = "",
    @SerializedName("liquidityUnits") val liquidityUnits: String = "",
    @SerializedName("poolAPY") val poolAPY: String = "",
    @SerializedName("runeDepth") val runeDepth: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("synthSupply") val synthSupply: String = "",
    @SerializedName("synthUnits") val synthUnits: String = "",
    @SerializedName("units") val units: String = "",
    @SerializedName("volume24h") val volume24h: String = ""
) : Parcelable {
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
