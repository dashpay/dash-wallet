package org.dash.wallet.integration.coinbase_integration.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

//package org.dash.wallet.integration.coinbase_integration.model
//
//import com.google.gson.annotations.SerializedName
//
//data class CoinBaseUserAccountInfo(
//    @field:SerializedName("data")
//    val `data`: List<CoinBaseUserAccountData>?,
//    @field:SerializedName("pagination")
//    val pagination: Pagination?
//)
//data class CoinBaseUserAccountData(
//    @field:SerializedName("allow_deposits")
//    val allowDeposits: Boolean?,
//    @field:SerializedName("allow_withdrawals")
//    val allowWithdrawals: Boolean?,
//    @field:SerializedName("balance")
//    val balance: CoinBaseBalance?,
//    @field:SerializedName("created_at")
//    val createdAt: String? = null,
//    @field:SerializedName("currency")
//    val coinBaseCurrency: CoinBaseCurrency?,
//    @field:SerializedName("id")
//    val id: String? = null,
//    @field:SerializedName("name")
//    val name: String? = null,
//    @field:SerializedName("primary")
//    val primary: Boolean?,
//    @field:SerializedName("resource")
//    val resource: String? = null,
//    @field:SerializedName("resource_path")
//    val resourcePath: String? = null,
//    @field:SerializedName("rewards")
//    val rewards: Rewards?,
//    @field:SerializedName("type")
//    val type: String? = null,
//    @field:SerializedName("updated_at")
//    val updatedAt: String? = null
//)
//data class CoinBaseCurrency(
//    @field:SerializedName("address_regex")
//    val addressRegex: String? = null,
//    @field:SerializedName("asset_id")
//    val assetId: String? = null,
//    @field:SerializedName("code")
//    val code: String? = null,
//    @field:SerializedName("color")
//    val color: String? = null,
//    @field:SerializedName("destination_tag_name")
//    val destinationTagName: String? = null,
//    @field:SerializedName("destination_tag_regex")
//    val destinationTagRegex: String? = null,
//    @field:SerializedName("exponent")
//    val exponent: Int?,
//    @field:SerializedName("name")
//    val name: String? = null,
//    @field:SerializedName("slug")
//    val slug: String? = null,
//    @field:SerializedName("sort_index")
//    val sortIndex: Int?,
//    @field:SerializedName("type")
//    val type: String? = null
//)
//
//data class Pagination(
//    @field:SerializedName("ending_before")
//    val endingBefore: Any?,
//    @field:SerializedName("limit")
//    val limit: Int?,
//    @field:SerializedName("next_starting_after")
//    val nextStartingAfter: Any?,
//    @field:SerializedName("order")
//    val order: String? = null,
//
//)
//
//data class Rewards(
//    @field:SerializedName("apy")
//    val apy: String? = null,
//    @field:SerializedName("formatted_apy")
//    val formattedApy: String? = null,
//    @field:SerializedName("label")
//    val label: String? = null
//)
//
//data class CoinBaseBalance(
//    @field:SerializedName("amount")
//    val amount: String? = null,
//    @field:SerializedName("currency")
//    val currency: String? = null
//)

@Parcelize
data class CoinBaseUserAccountInfo(
    val `data`: List<CoinBaseUserAccountData>? = null,
    val pagination: Pagination? = null
) : Parcelable

@Parcelize
data class CoinBaseUserAccountData(
    val allow_deposits: Boolean? = null,
    val allow_withdrawals: Boolean? = null,
    val balance: CoinBaseBalance? = null,
    val created_at: String? = null,
    val currency: CoinBaseCurrency? = null,
    val id: String? = null,
    val name: String? = null,
    val primary: Boolean? = null,
    val resource: String? = null,
    val resource_path: String? = null,
    val type: String? = null,
    val updated_at: String? = null
) : Parcelable

@Parcelize
data class Pagination(
    val ending_before: String? = null,
    val limit: Int? = null,
    val next_starting_after: String? = null,
    val next_uri: String? = null,
    val order: String? = null,
    val previous_ending_before: String? = null,
    val previous_uri: String? = null,
    val starting_after: String? = null
) : Parcelable

@Parcelize
data class CoinBaseCurrency(
    val address_regex: String? = null,
    val asset_id: String? = null,
    val code: String? = null,
    val color: String? = null,
    val destination_tag_name: String? = null,
    val destination_tag_regex: String? = null,
    val exponent: Int? = null,
    val name: String? = null,
    val slug: String? = null,
    val sort_index: Int? = null,
    val type: String? = null
) : Parcelable

@Parcelize
data class CoinBaseBalance(
    val amount: String? = null,
    val currency: String? = null
) : Parcelable
