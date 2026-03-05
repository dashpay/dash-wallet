package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class NetworkResponse(
    @SerializedName("bond_reward_rune") val bondRewardRune: String,
    @SerializedName("gas_spent_cacao") val gasSpentCacao: String,
    @SerializedName("gas_withheld_cacao") val gasWithheldCacao: String,
    @SerializedName("outbound_fee_multiplier") val outboundFeeMultiplier: String,
    @SerializedName("total_asgard") val totalAsgard: String,
    @SerializedName("total_bond_units") val totalBondUnits: String,
    @SerializedName("total_reserve") val totalReserve: String
): Parcelable
