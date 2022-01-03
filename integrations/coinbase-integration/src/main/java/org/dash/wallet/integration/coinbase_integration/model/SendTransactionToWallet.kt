package org.dash.wallet.integration.coinbase_integration.model

import com.google.gson.annotations.SerializedName

data class SendTransactionToWalletResponse(
    val `data`: SendTransactionToWalletData?
) {
    companion object {
        val EMPTY = SendTransactionToWalletUIModel("")
    }
}

data class SendTransactionToWalletData(
    val amount: Amount? = null,
    val application: Application? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    val description: Any? = null,
    val details: Details? = null,
    @SerializedName("hide_native_amount")
    val hideNativeAmount: Boolean? = null,
    val id: String? = null,
    val idem: String? = null,
    @SerializedName("instant_exchange")
    val isInstantExchange: Boolean? = null,
    @SerializedName("native_amount")
    val nativeAmount: NativeAmount? = null,
    val network: Network? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null,
    val status: String? = null,
    val to: To? = null,
    val type: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)

data class AddressInfo(
    val address: String? = null
)

data class To(
    val address: String? = null,
    @SerializedName("address_info")
    val addressInfo: AddressInfo? = null,
    @SerializedName("address_url")
    val addressUrl: String? = null,
    val currency: String? = null,
    val resource: String? = null
)

data class Application(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
)

data class Details(
    val header: String? = null,
    val health: String? = null,
    val subtitle: String? = null,
    val title: String? = null
)

data class Network(
    val confirmations: Int? = null,
    val status: String? = null,
    @SerializedName("status_description")
    val statusDescription: String? = null,
    @SerializedName("transaction_amount")
    val transactionAmount: TransactionAmount? = null,
    @SerializedName("transaction_fee")
    val transactionFee: TransactionFee? = null
)

data class TransactionFee(
    val amount: String? = null,
    val currency: String? = null
)

data class TransactionAmount(
    val amount: String? = null,
    val currency: String? = null
)

data class NativeAmount(
    val amount: String? = null,
    val currency: String? = null
)


data class SendTransactionToWalletParams(
    val amount: String?,
    val currency: String?,
    val idem: String?,
    val to: String?,
    val type: String?
)

data class SendTransactionToWalletUIModel(
    val sendTransactionStatus: String? = "",
)