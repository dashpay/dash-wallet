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
import kotlinx.parcelize.IgnoredOnParcel
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
    @IgnoredOnParcel
    var assetPriceFiat: Fiat = Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 0)

    /**
     * SwapKit only: true when this asset is routable from DASH **exclusively via
     * MAYACHAIN** (no NEAR/other-provider fallback). Such assets inherit Maya's
     * halt exposure and the OP_RETURN-memo constraint. Computed by
     * [org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator] from the
     * provider token-list intersection (see SWAPKIT_PROTOCOL.md → "Detecting
     * Maya-only Assets"). Always false for the native Maya backend, where every
     * asset is Maya-routed by definition.
     */
    @IgnoredOnParcel
    var mayaOnly: Boolean = false

    /**
     * SwapKit only: true when this asset is routable from DASH **exclusively via
     * NEAR** (in NEAR's token list but NOT MAYACHAIN's) — the mirror of [mayaOnly].
     * When BOTH [mayaOnly] and [nearOnly] are false the asset is routable via both
     * providers (or via neither, for an unclassified reachable asset), and the
     * picker shows no route-provider label. Always false for the native Maya backend.
     */
    @IgnoredOnParcel
    var nearOnly: Boolean = false

    /**
     * SwapKit only: true when [mayaOnly] and Maya currently reports this asset's
     * chain as halted / trading-paused (or global trading paused). For non-Maya-only
     * assets this stays false because a NEAR route keeps them tradable even during a
     * Maya halt.
     */
    @IgnoredOnParcel
    var mayaHalted: Boolean = false

    @IgnoredOnParcel
    val assetPriceInCacao: BigDecimal
        get() {
            val assetBd = balanceAsset.toBigDecimalOrNull() ?: return BigDecimal.ZERO
            if (assetBd.signum() == 0) return BigDecimal.ZERO
            val cacaoBd = balanceCacao.toBigDecimalOrNull() ?: return BigDecimal.ZERO
            return cacaoBd.divide(assetBd, 8, java.math.RoundingMode.HALF_UP)
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
