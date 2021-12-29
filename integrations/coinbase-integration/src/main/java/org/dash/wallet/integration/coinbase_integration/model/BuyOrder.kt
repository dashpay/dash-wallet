package org.dash.wallet.integration.coinbase_integration.model

import com.google.gson.annotations.SerializedName
import org.dash.wallet.integration.coinbase_integration.TRANSACTION_TYPE_SEND

data class BuyOrderResponse(
    val `data`: BuyOrderData?
) {
    companion object {
        val EMPTY_PLACE_BUY = PlaceBuyOrderUIModel("", "", "", "")
        val EMPTY_COMMIT_BUY = CommitBuyOrderUIModel("", "", "", "")
    }
}

data class BuyOrderData(
    val amount: Amount? = null,
    val committed: Boolean? = null,
    @SerializedName("created_at")
    val created_at: String? = null,
    val fee: Fee? = null,
    val hold_days: Int? = null,
    val hold_until: String? = null,
    val id: String? = null,
    val idem: String? = null,
    val instant: Boolean? = null,
    @SerializedName("is_first_buy")
    val isFirstBuy: Boolean? = null,
    val next_step: Any? = null,
    @SerializedName("payment_method")
    val paymentMethod: PaymentMethod? = null,
    @SerializedName("payout_at")
    val payoutAt: String? = null,
    @SerializedName("requires_completion_step")
    val requiresCompletionStep: Boolean,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null,
    val status: String? = null,
    val subtotal: Subtotal? = null,
    val total: Total? = null,
    val transaction: Transaction? = null,
    @SerializedName("unit_price")
    val unitPrice: UnitPrice? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    @SerializedName("user_reference")
    val userReference: String? = null
) {
    fun mapToPlaceBuyUIModel(): PlaceBuyOrderUIModel {
        return PlaceBuyOrderUIModel(this.id ?:"", this.paymentMethod?.id?:"", this.fee?.amount?: "", this.fee?.currency?: "")
    }

    fun mapToCommitBuyUIModel(): CommitBuyOrderUIModel {
        return CommitBuyOrderUIModel(dashAmount = this.amount?.amount?: "")
    }
}

data class UnitPrice(
    val amount: String? = null,
    val currency: String? = null,
    val scale: Int? = null
)

data class PaymentMethod(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
)

data class Transaction(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
)

data class Amount(
    val amount: String? = null,
    val currency: String? = null
)

data class Total(
    val amount: String? = null,
    val currency: String? = null
)

data class Subtotal(
    val amount: String? = null,
    val currency: String? = null
)

data class Fee(
    val amount: String? = null,
    val currency: String? = null
)

data class PlaceBuyOrderParams(
    val amount: String? = null,
    val currency: String? = null,
    val payment_method: String? = null,
    val commit: Boolean = false,
    val quote: Boolean = true
)


data class PlaceBuyOrderUIModel(
    val buyOrderId: String? = "",
    val paymentMethodId: String? = "",
    val coinbaseFee: String? = "",
    val coinbaseFeeCurrency: String? = ""
)

data class CommitBuyOrderUIModel(
    val dashAmount: String? = "",
    val dashCurrency: String = "DASH",
    val dashAddress: String? = "",
    val transactionType: String = TRANSACTION_TYPE_SEND
)