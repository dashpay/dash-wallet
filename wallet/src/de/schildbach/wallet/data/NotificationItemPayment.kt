package de.schildbach.wallet.data

import org.bitcoinj.core.Transaction

data class NotificationItemPayment(val tx: Transaction? = null) : NotificationItem() {

    override fun getId() = tx!!.txId.toString()
    // updateTime.time is already epoch milliseconds; every other NotificationItem returns
    // millis too, so the sort key and relative-time display stay consistent across types.
    override fun getDate() = tx!!.updateTime.time
}

