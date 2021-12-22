package org.dash.wallet.integration.coinbase_integration.model

import com.google.gson.annotations.SerializedName

data class PlaceBuyOrderResponse(
    val `data`: PlaceBuyOrderData
)

data class PlaceBuyOrderData(
    val amount: Amount,
    val committed: Boolean,
    @SerializedName("created_at")
    val created_at: String,
    val fee: Fee,
    val hold_days: Int,
    val hold_until: String,
    val id: String,
    val idem: String,
    val instant: Boolean,
    @SerializedName("is_first_buy")
    val isFirstBuy: Boolean,
    val next_step: Any,
    @SerializedName("payment_method")
    val paymentMethod: PaymentMethod,
    @SerializedName("payout_at")
    val payoutAt: String,
    @SerializedName("requires_completion_step")
    val requiresCompletionStep: Boolean,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String,
    val status: String,
    val subtotal: Subtotal,
    val total: Total,
    val transaction: Transaction,
    @SerializedName("unit_price")
    val unitPrice: UnitPrice,
    @SerializedName("updated_at")
    val updatedAt: String,
    @SerializedName("user_reference")
    val userReference: String
)

data class UnitPrice(
    val amount: String,
    val currency: String,
    val scale: Int
)

data class PaymentMethod(
    val id: String,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String
)

data class Transaction(
    val id: String,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String
)

data class Amount(
    val amount: String,
    val currency: String
)

data class Total(
    val amount: String,
    val currency: String
)

data class Subtotal(
    val amount: String,
    val currency: String
)

data class Fee(
    val amount: String,
    val currency: String
)

data class PlaceBuyOrderParams(
    val amount: String,
    val currency: String,
    val payment_method: String,
    val commit: Boolean = false,
    val quote: Boolean = true
)