package org.dash.wallet.integration.coinbase_integration.model

import com.google.gson.annotations.SerializedName

data class PaymentMethods(
    val `data`: List<PaymentMethodsData>
)

data class PaymentMethodsData(
    @SerializedName("allow_buy")
    val isBuyingAllowed: Boolean,
    @SerializedName("allow_deposit")
    val isDepositAllowed: Boolean,
    @SerializedName("allow_sell")
    val isSellingAllowed: Boolean,
    @SerializedName("allow_withdraw")
    val isWithdrawalAllowed: Boolean,
    @SerializedName("created_at")
    val createdAt: String,
    val currency: String,
    @SerializedName("fiat_account")
    val fiatAccount: FiatAccount,
    val id: String,
    @SerializedName("instant_buy")
    val isInstantBuyAllowed: Boolean,
    @SerializedName("instant_sell")
    val isInstantSellAllowed: Boolean,
    val limits: Limits,
    @SerializedName("minimum_purchase_amount")
    val minimumPurchaseAmount: MinimumPurchaseAmount,
    val name: String,
    @SerializedName("primary_buy")
    val isPrimaryBuying: Boolean,
    @SerializedName("primary_sell")
    val isPrimarySelling: Boolean,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String,
    val type: String,
    @SerializedName("updated_at")
    val updatedAt: String,
    val verified: Boolean
)

data class FiatAccount(
    val id: String,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String
)

data class Limits(
    val name: String,
    val type: String
)

data class MinimumPurchaseAmount(
    val amount: String,
    val currency: String
)