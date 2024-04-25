/*
 * Copyright 2024 Dash Core Group.
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
import org.bitcoinj.core.Sha256Hash

@Parcelize
data class SwapTradeResponse(
    @SerializedName("data")
    val `data`: SwapTradeResponseData? = null
) : Parcelable {
    companion object {
        val EMPTY_SWAP_TRADE = SwapTradeUIModel(Amount(), "", "", "", "", Amount(), "")
    }
}

@Parcelize
data class SwapTradeResponseData(
    val fee: SwapApiAmount? = null,
    val id: String? = null,
    @SerializedName("applied_subscription_benefit")
    val applied_subscription_benefit: Boolean? = null,
    @SerializedName("created_at")
    val created_at: String? = null,
    @SerializedName("display_input_amount")
    val display_input_amount: SwapApiAmount? = null,
    @SerializedName("exchange_rate")
    val exchange_rate: SwapApiAmount? = null,
    @SerializedName("input_amount")
    val input_amount: SwapApiAmount? = null,
    @SerializedName("output_amount")
    val output_amount: SwapApiAmount? = null,
    val status: String? = null,
    @SerializedName("unit_price")
    val unit_price: SwapUnitPrice? = null,
    @SerializedName("updated_at")
    val updated_at: String? = null
) : Parcelable
@Parcelize
data class SwapUnitPrice(
    val target_to_fiat: SwapApiAmount? = null,
    val target_to_source: SwapApiAmount? = null
) : Parcelable

@Parcelize
data class SwapApiAmount(
    val amount: String? = null,
    val currency: String? = null
) : Parcelable

@Parcelize
data class SwapTradeUIModel(
    val amount: Amount,
    val vaultAddress: String,
    val destinationAddress: String,
    val swapTradeId: String = "",
    val outputAsset: String = "",
    val feeAmount: Amount = Amount(),
    var inputCurrencyName: String = "",
    var outputCurrencyName: String = "",
    var txid: Sha256Hash = Sha256Hash.ZERO_HASH
) : Parcelable {
    @IgnoredOnParcel
    val inputCurrency = amount.dashCode
    val outputCurrency = amount.cryptoCode
}
