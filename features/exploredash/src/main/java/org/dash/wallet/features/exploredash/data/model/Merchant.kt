package org.dash.wallet.features.exploredash.data.model

import androidx.room.Entity
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

@Entity(tableName = "merchant")
data class Merchant(
    @PropertyName("pluscode")
    var plusCode: String? = "",

    @PropertyName("adddate")
    var addDate: String? = "",

    @PropertyName("updatedate")
    var updateDate: String? = "",

    var address1: String? = "",
    var address2: String? = "",
    var address3: String? = "",
    var address4: String? = "",
    var latitude: Double? = 0.0,
    var longitude: Double? = 0.0,
    var territory: String? = "",
    var website: String? = "",
    var type: String? = "",

    @PropertyName("logolocation")
    var logoLocation: String? = "",

    @PropertyName("paymentmethod")
    var paymentMethod: String? = ""
) : SearchResult()