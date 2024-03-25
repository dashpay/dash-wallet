package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class Account(
    val uuid: UUID,
    val name: String,
    val currency: String,
    @SerializedName("available_balance")
    val availableBalance: Balance,
    val default: Boolean,
    val active: Boolean,
    val type: String,
    val ready: Boolean
) : Parcelable {
    companion object {
        val EMPTY = Account(
            UUID.randomUUID(),
            "",
            "",
            Balance("", ""),
            default = false,
            active = false,
            type = "",
            ready = false
        )
    }
}

@Parcelize
data class Balance(
    val value: String,
    val currency: String
) : Parcelable
