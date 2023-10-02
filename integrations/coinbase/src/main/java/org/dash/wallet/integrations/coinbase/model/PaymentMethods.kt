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

import com.google.gson.annotations.SerializedName

data class PaymentMethodsResponse(
    val `data`: List<PaymentMethodsData>?
)

data class PaymentMethodsData(
    @SerializedName("allow_buy")
    val isBuyingAllowed: Boolean? = null,
    @SerializedName("allow_deposit")
    val isDepositAllowed: Boolean? = null,
    @SerializedName("allow_sell")
    val isSellingAllowed: Boolean? = null,
    @SerializedName("allow_withdraw")
    val isWithdrawalAllowed: Boolean? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    val currency: String? = null,
    @SerializedName("fiat_account")
    val fiatAccount: FiatAccount? = null,
    val id: String? = null,
    @SerializedName("instant_buy")
    val isInstantBuyAllowed: Boolean? = null,
    @SerializedName("instant_sell")
    val isInstantSellAllowed: Boolean? = null,
    val limits: Limits? = null,
    @SerializedName("minimum_purchase_amount")
    val minimumPurchaseAmount: MinimumPurchaseAmount? = null,
    val name: String? = null,
    @SerializedName("primary_buy")
    val isPrimaryBuying: Boolean? = null,
    @SerializedName("primary_sell")
    val isPrimarySelling: Boolean? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null,
    val type: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    val verified: Boolean? = null
)

data class FiatAccount(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
)

data class Limits(
    val name: String? = null,
    val type: String? = null
)

data class MinimumPurchaseAmount(
    val amount: String? = null,
    val currency: String? = null
)
