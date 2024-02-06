package org.dash.wallet.integrations.uphold.data

import com.google.gson.annotations.SerializedName

class UpholdCardAddressList(
    @SerializedName("formats")
    val addresses: List<UpholdAddress>,
    val type: String
) {
    fun isEmpty() = addresses.isEmpty()
    fun firstOrNull() = addresses.firstOrNull()
}
