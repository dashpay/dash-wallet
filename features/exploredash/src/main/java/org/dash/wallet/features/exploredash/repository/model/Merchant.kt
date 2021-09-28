package org.dash.wallet.features.exploredash.repository.model

import com.google.firebase.database.PropertyName

object PaymentMethod {
    const val DASH = "dash"
    const val GIFT_CARD = "gift_card"
}

object MerchantType {
    const val ONLINE = "online"
    const val PHYSICAL = "physical"
    const val BOTH = "both"
}

data class Merchant(
    @PropertyName("pluscode")
    val plusCode: String? = "",

    @PropertyName("adddate")
    val addDate: String? = "",

    @PropertyName("updatedate")
    val updateDate: String? = "",

    val address1: String? = "",
    val address2: String? = "",
    val address3: String? = "",
    val address4: String? = "",
    val latitude: Double? = 0.0,
    val longitude: Double? = 0.0,
    val territory: String? = "",
    val city: String? = "",
    val website: String? = "",
    val type: String? = "",

    @PropertyName("logolocation")
    val logoLocation: String? = "",

    @PropertyName("paymentmethod")
    val paymentMethod: String? = ""
) : SearchResult()