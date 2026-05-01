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
    @SerializedName("balance_cacao") val balanceCacao: String = "",
    @SerializedName("balance_asset") val balanceAsset: String = "",
    @SerializedName("asset") val asset: String = "",
    @SerializedName("LP_units") val lpUnits: String = "",
    @SerializedName("pool_units") val poolUnits: String = "",
    /** status must be "Available" */
    @SerializedName("status") val status: String = "",
    @SerializedName("synth_units") val synthUnits: String = "",
    @SerializedName("synth_supply") val synthSupply: String = "",
    @SerializedName("pending_inbound_cacao") val pendingInboundCacao: String = "",
    @SerializedName("pending_inbound_asset") val pendingInboundAsset: String = "",
    @SerializedName("synth_mint_paused") val synthMintPaused: Boolean = false,
    @SerializedName("bondable") val bondable: Boolean = false
) : Parcelable {
    var assetPriceFiat: Fiat = Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 0)

    val assetPriceInCacao: BigDecimal
        get() {
            val asset = balanceAsset.toBigDecimalOrNull() ?: return BigDecimal.ZERO
            if (asset.signum() == 0) return BigDecimal.ZERO
            return BigDecimal(balanceCacao).divide(asset, 8, java.math.RoundingMode.HALF_UP)
        }

    fun setAssetPrice(cacaoToFiatRate: Fiat) {
        assetPriceFiat = assetPriceInCacao
            .multiply(cacaoToFiatRate.toBigDecimal())
            .toFiat(cacaoToFiatRate.currencyCode)
    }

    val currencyCode: String
        get() {
            val codeIndex = asset.indexOf('.')
            val smartContract = asset.indexOf('-')
            return asset.substring(codeIndex + 1, if (smartContract != -1) smartContract else asset.length)
        }
}
