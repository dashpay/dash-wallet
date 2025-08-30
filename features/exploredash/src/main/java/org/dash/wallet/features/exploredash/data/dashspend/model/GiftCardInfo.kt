package org.dash.wallet.features.exploredash.data.dashspend.model

enum class GiftCardStatus {
    UNPAID,
    PAID,
    FULFILLED,
    REJECTED
}

data class GiftCardInfo(
    val id: String,
    val merchantName: String? = "",
    val status: GiftCardStatus = GiftCardStatus.UNPAID, // unpaid, paid, fulfilled, rejected
    val barcodeUrl: String? = "",
    // val barcodeFormat: String? = "",
    val cardNumber: String? = "",
    val cardPin: String? = "",

    val cryptoAmount: String? = "",
    val cryptoCurrency: String? = "",
    val paymentCryptoNetwork: String = "",
    val paymentId: String = "",
    val percentDiscount: String = "",
    val rate: String = "",
    val redeemUrl: String? = "",
    val fiatAmount: String? = "",
    val fiatCurrency: String? = "",
    val paymentUrls: Map<String, String>? = buildMap { }
)
