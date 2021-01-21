package de.schildbach.wallet.data

import androidx.annotation.StringRes

data class NotificationItemAlert(@StringRes val resourceId: Int) : NotificationItem() {

    override fun getId(): String {
        return resourceId.toString()
    }

    override fun getDate(): Long {
        return System.currentTimeMillis()
    }

}
