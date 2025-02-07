package org.dash.wallet.integrations.crowdnode.model

import android.os.Parcelable
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.parcelize.Parcelize

@Parcelize
data class FeeLadder(
    val name: String,
    val type: String,
    val amount: Double,
    val fee: Double
) : Parcelable

/*
  [
    {
        "Key":"FeeLadder",
        "Value":"[
            {
                \"name\":\"Up to 10 Dash and above\",
                \"type\":\"Normal\",
                \"amount\":10.0,\"fee\":35.0
            },
            {
                \"name\":\"Trustless up to 100 Dash and above\",
                \"type\":\"Trustless\",
                \"amount\":100.0,
                \"fee\":20.0
            }
       ]"
    }
  ]
 */
@Parcelize
data class FeeInfo(
    @SerializedName("Key") val key: String,
    @SerializedName("Value") val rawValue: String
) : Parcelable {
    companion object {
        const val DEFAULT_FEE = 35.0
        const val DEFAULT_AMOUNT = 100.0
        const val KEY_FEELADDER = "FeeLadder"
        const val TYPE_NORMAL = "Normal"
        const val TYPE_TRUSTLESS = "Trustless"
        const val DEFAULT_FEE_LADDER = """[
        {
            \"name\":\"Up to 10 Dash and above\",
            \"type\":\"Normal\",
            \"amount\":10.0,\"fee\":35.0
        },
        {
            \"name\":\"Trustless up to 100 Dash and above\",
            \"type\":\"Trustless\",
            \"amount\":100.0,
            \"fee\":20.0
        }
        ]"""
        val default = FeeInfo(KEY_FEELADDER, DEFAULT_FEE_LADDER)
    }
    val value: List<FeeLadder> by lazy {
        Gson().fromJson(rawValue, object : TypeToken<List<FeeLadder>>() {}.type)
    }

    fun getNormal() = value.find { it.type == TYPE_NORMAL }
}

@Parcelize
data class FeeInfoResponse(
    @SerializedName("FeeInfo") val feeInfoList: List<FeeInfo>
) : Parcelable {
    companion object {
        val default = FeeInfoResponse(listOf(FeeInfo.default))
    }
    fun getNormal() = feeInfoList.first().value.find { it.type == FeeInfo.TYPE_NORMAL }
}
