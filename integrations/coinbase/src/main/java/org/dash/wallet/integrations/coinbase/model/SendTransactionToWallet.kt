package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class SendTransactionToWalletResponse(
    val `data`: SendTransactionToWalletData?
) : Parcelable

@Parcelize
data class SendTransactionToWalletData(
    val amount: Amount? = null,
    val application: Application? = null,
    @SerializedName("created_at")
    val createdAt: String? = null,
    val description: String? = null,
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
) : Parcelable

@Parcelize
data class AddressInfo(
    val address: String? = null
) : Parcelable

@Parcelize
data class To(
    val address: String? = null,
    @SerializedName("address_info")
    val addressInfo: AddressInfo? = null,
    @SerializedName("address_url")
    val addressUrl: String? = null,
    val currency: String? = null,
    val resource: String? = null
) : Parcelable

@Parcelize
data class Application(
    val id: String? = null,
    val resource: String? = null,
    @SerializedName("resource_path")
    val resourcePath: String? = null
) : Parcelable

@Parcelize
data class Details(
    val header: String? = null,
    val health: String? = null,
    val subtitle: String? = null,
    val title: String? = null
) : Parcelable

@Parcelize
data class Network(
    val confirmations: Int? = null,
    val status: String? = null,
    @SerializedName("status_description")
    val statusDescription: String? = null,
    @SerializedName("transaction_amount")
    val transactionAmount: TransactionAmount? = null,
    @SerializedName("transaction_fee")
    val transactionFee: TransactionFee? = null
) : Parcelable

@Parcelize
data class TransactionFee(
    val amount: String? = null,
    val currency: String? = null
) : Parcelable

@Parcelize
data class TransactionAmount(
    val amount: String? = null,
    val currency: String? = null
) : Parcelable

@Parcelize
data class NativeAmount(
    val amount: String? = null,
    val currency: String? = null
) : Parcelable

@Parcelize
data class SendTransactionToWalletParams(
    val amount: String?,
    val currency: String?,
    val idem: String?,
    val to: String?,
    val type: String?,
    val description: String?="Dash Wallet App"
): Parcelable

sealed class TransactionType: Parcelable {
    @Parcelize object BuyDash: TransactionType()
    @Parcelize object SellSwap: TransactionType()
    @Parcelize object BuySwap: TransactionType()
    @Parcelize object TransferDash: TransactionType()
}

@Parcelize
data class CoinbaseTransactionParams(
    val params: SendTransactionToWalletParams,
    val type: TransactionType,
    val coinbaseWalletName:String?=null
): Parcelable