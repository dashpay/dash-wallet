package org.dash.wallet.features.exploredash.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GiftCardDetailsDialogModel(
    val merchantName: String? = "",
    val merchantLogo: String? = "",
    val barcodeImg: String? = "",
    val giftCardPrice: String? = "",
    val giftCardCheckCurrentBalance: String? = "",
    val giftCardNumber: String? = "",
    val giftCardPin: String? = "",
    val transactionId: String? = ""
) : Parcelable
