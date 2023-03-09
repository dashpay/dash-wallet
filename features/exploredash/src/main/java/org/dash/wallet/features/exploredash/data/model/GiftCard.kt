package org.dash.wallet.features.exploredash.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GiftCard(
    val merchantName: String? = "",
    val merchantLogo: String? = "",
    val barcodeImg: String? = "https://api.giftango.com/cards/WR23RS63MGW/barcode?token=b4262f79aa5a6d5b0251eca2197ca9374fc69d146157882079a62cc4c506b794",
    val price: String? = "",
    val checkCurrentBalanceUrl: String? = "",
    val number: String? = "",
    val pin: String? = "",
    val transactionId: String? = ""
) : Parcelable
