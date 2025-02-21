package de.schildbach.wallet.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class NotificationItemUserAlert(@StringRes val stringResId: Int,
                                     @DrawableRes val iconResId: Int,
                                     val createdAt: Long = System.currentTimeMillis()) : NotificationItem() {

    override fun getId(): String {
        return stringResId.toString()
    }

    override fun getDate(): Long {
        return createdAt
    }

}
