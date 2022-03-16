package org.dash.wallet.integration.coinbase_integration.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize

@Parcelize
data class AddressesResponse(

    @field:SerializedName("data")
    val addresses: Addresses? = null
) : Parcelable

@Parcelize
data class Addresses(

    @field:SerializedName("deposit_uri")
    val depositUri: String? = null,

    @field:SerializedName("address_info")
    val addressInfo: AddressInfo? = null,

    @field:SerializedName("address")
    val address: String? = null,

    @field:SerializedName("resource")
    val resource: String? = null,

    @field:SerializedName("warnings")
    val warnings: List<String?>? = null,

    @field:SerializedName("created_at")
    val createdAt: String? = null,

    @field:SerializedName("uri_scheme")
    val uriScheme: String? = null,

    @field:SerializedName("network")
    val network: String? = null,

    @field:SerializedName("callback_url")
    val callbackUrl: String? = null,

    @field:SerializedName("updated_at")
    val updatedAt: String? = null,

    @field:SerializedName("resource_path")
    val resourcePath: String? = null,

    @field:SerializedName("name")
    val name: String? = null,

    @field:SerializedName("id")
    val id: String? = null
) : Parcelable

