package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import org.dash.wallet.common.util.Constants
import org.dash.wallet.integrations.coinbase.CoinbaseConstants

@Parcelize
data class BuyOrderResponse(
    val `data`: BuyOrderData?
): Parcelable {
    companion object {
        val EMPTY_PLACE_BUY = PlaceBuyOrderUIModel("", "", "", "")
        val EMPTY_COMMIT_BUY = CommitBuyOrderUIModel("", "", "", "")
    }
}

@Parcelize
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
): Parcelable

@Parcelize
data class UnitPrice(
    val amount: String? = null,
    val currency: String? = null,
    val scale: Int? = null
): Parcelable

@Parcelize
data class PaymentMethod(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
): Parcelable

@Parcelize
data class Transaction(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
): Parcelable

@Parcelize
data class Amount(
    val amount: String? = null,
    val currency: String? = null
): Parcelable

@Parcelize
data class Total(
    val amount: String? = null,
    val currency: String? = null
): Parcelable

@Parcelize
data class Subtotal(
    val amount: String? = null,
    val currency: String? = null
): Parcelable

@Parcelize
data class Fee(
    val amount: String? = null,
    val currency: String? = null
): Parcelable

data class PlaceBuyOrderParams(
    val amount: String? = null,
    val currency: String? = null,
    val payment_method: String? = null,
    val commit: Boolean = false
)

@Parcelize
data class PlaceBuyOrderUIModel(
    val buyOrderId:String = "",
    val paymentMethodId:String = "",
    val purchaseAmount:String = "",
    val purchaseCurrency: String = "",
    val coinBaseFeeAmount:String = "",
    val coinbaseFeeCurrency: String = "",
    val totalAmount:String = "",
    val totalCurrency: String = "",
    val dashAmount:String = "",
): Parcelable

data class CommitBuyOrderUIModel(
    val dashAmount: String? = "",
    val dashCurrency: String = Constants.DASH_CURRENCY,
    val dashAddress: String? = "",
    val transactionType: String = CoinbaseConstants.TRANSACTION_TYPE_SEND
)