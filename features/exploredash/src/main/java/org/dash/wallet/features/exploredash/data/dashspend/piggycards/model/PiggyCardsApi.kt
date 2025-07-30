package org.dash.wallet.features.exploredash.data.dashspend.piggycards.model

import com.google.gson.annotations.SerializedName

data class Brand(
    val id: String,
    val name: String
)

data class GiftcardResponse(
    val code: Int,
    val message: String,
    val data: List<Giftcard>?
)

data class Giftcard(
    val id: Int,
    val name: String,
    val description: String,
    val image: String,
    @SerializedName("price_type") val priceType: String,
    val currency: String,
    @SerializedName("discount_percentage") val discountPercentage: Double,
    @SerializedName("min_denomination") val minDenomination: Int,
    @SerializedName("max_denomination") val maxDenomination: Int,
    val denomination: String,
    val fee: Int,
    val quantity: Int,
    @SerializedName("brand_id") val brandId: Int
) {
    val isFixed
        get() = priceType.trim().lowercase() == "fixed"
}