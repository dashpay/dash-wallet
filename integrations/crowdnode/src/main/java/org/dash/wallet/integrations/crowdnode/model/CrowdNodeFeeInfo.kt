package org.dash.wallet.integrations.crowdnode.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

data class FeeLadder(
    val name: String,
    val type: String,
    val amount: Double,
    val fee: Double
)

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
    @SerializedName("FeeLadder") val feeLadder: @RawValue List<FeeLadder>
) : Parcelable {
    companion object {
        const val DEFAULT_FEE = 35.0
        const val DEFAULT_AMOUNT = 100.0
        const val KEY_FEELADDER = "FeeLadder"
        const val TYPE_NORMAL = "Normal"
        const val TYPE_TRUSTLESS = "Trustless"
        val default = FeeInfo(listOf(FeeLadder("", TYPE_NORMAL, DEFAULT_AMOUNT, DEFAULT_FEE)))
    }

    fun getNormal() = feeLadder.find { it.type == FeeInfo.TYPE_NORMAL }
}
