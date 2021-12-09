package org.dash.wallet.integration.coinbase_integration.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

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
