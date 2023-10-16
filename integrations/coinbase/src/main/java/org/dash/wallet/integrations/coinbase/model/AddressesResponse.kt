package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class AddressesResponse(

    @SerializedName("data")
    val addresses: Addresses? = null
) : Parcelable

@Parcelize
data class Addresses(

    @SerializedName("deposit_uri")
    val depositUri: String? = null,

    @SerializedName("address_info")
    val addressInfo: AddressInfo? = null,

    @SerializedName("address")
    val address: String? = null,

    @SerializedName("resource")
    val resource: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("uri_scheme")
    val uriScheme: String? = null,

    @SerializedName("network")
    val network: String? = null,

    @SerializedName("callback_url")
    val callbackUrl: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("resource_path")
    val resourcePath: String? = null,

    @SerializedName("name")
    val name: String? = null,

    @SerializedName("id")
    val id: String? = null
) : Parcelable

