package org.dash.wallet.integration.coinbase_integration.model

import com.google.gson.annotations.SerializedName

data class SendTransactionToWalletResponse(
    val `data`: SendTransactionToWalletData
)

data class SendTransactionToWalletData(
    val amount: Amount,
    val application: Application,
    @SerializedName("created_at")
    val createdAt: String,
    val description: Any,
    val details: Details,
    @SerializedName("hide_native_amount")
    val hideNativeAmount: Boolean,
    val id: String,
    val idem: String,
    @SerializedName("instant_exchange")
    val isInstantExchange: Boolean,
    @SerializedName("native_amount")
    val nativeAmount: NativeAmount,
    val network: Network,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String,
    val status: String,
    val to: To,
    val type: String,
    @SerializedName("updated_at")
    val updatedAt: String
)

data class AddressInfo(
    val address: String
)

data class To(
    val address: String,
    @SerializedName("address_info")
    val addressInfo: AddressInfo,
    @SerializedName("address_url")
    val addressUrl: String,
    val currency: String,
    val resource: String
)

data class Application(
    val id: String,
    val resource: String,
    @SerializedName("resource_path")
    val resourcePath: String
)

data class Details(
    val header: String,
    val health: String,
    val subtitle: String,
    val title: String
)

data class Network(
    val confirmations: Int,
    val status: String,
    @SerializedName("status_description")
    val statusDescription: String,
    @SerializedName("transaction_amount")
    val transactionAmount: TransactionAmount,
    @SerializedName("transaction_fee")
    val transactionFee: TransactionFee
)

data class TransactionFee(
    val amount: String,
    val currency: String
)

data class TransactionAmount(
    val amount: String,
    val currency: String
)

data class NativeAmount(
    val amount: String,
    val currency: String
)


data class SendTransactionToWalletParams(
    val amount: String,
    val currency: String,
    val idem: String,
    val to: String,
    val type: String
)