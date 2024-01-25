package org.dash.wallet.integrations.uphold.data

import com.google.gson.annotations.SerializedName

class UpholdCardAddressList(
    @SerializedName("formats")
    val addresses: List<UpholdCardAddress2>,
    val type: String
) {
    fun isEmpty() = addresses.isEmpty()
    fun firstOrNull() = addresses.firstOrNull()
}

data class UpholdCardAddress2(
    val format: String,
    val value: String
)
