package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize

@Parcelize
data class CoinBaseAccountAddressResponse(

    @field:SerializedName("pagination")
    val pagination: Pagination? = null,

    @field:SerializedName("data")
    val data: List<DataItem?>? = null
) : Parcelable

@Parcelize
data class CoinBaseAccountAddressInfo(
    @field:SerializedName("address")
    val address: String? = null
) : Parcelable

@Parcelize
data class WarningsItem(

    @field:SerializedName("image_url")
    val imageUrl: String? = null,

    @field:SerializedName("options")
    val options: List<OptionsItem?>? = null,

    @field:SerializedName("details")
    val details: String? = null,

    @field:SerializedName("type")
    val type: String? = null,

    @field:SerializedName("title")
    val title: String? = null
) : Parcelable

@Parcelize
data class OptionsItem(

    @field:SerializedName("style")
    val style: String? = null,

    @field:SerializedName("text")
    val text: String? = null,

    @field:SerializedName("id")
    val id: String? = null
) : Parcelable

@Parcelize
data class DataItem(

    @field:SerializedName("deposit_uri")
    val depositUri: String? = null,

    @field:SerializedName("address_info")
    val addressInfo: CoinBaseAccountAddressInfo? = null,

    @field:SerializedName("address")
    val address: String? = null,

    @field:SerializedName("resource")
    val resource: String? = null,

    @field:SerializedName("warnings")
    val warnings: List<WarningsItem?>? = null,

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
