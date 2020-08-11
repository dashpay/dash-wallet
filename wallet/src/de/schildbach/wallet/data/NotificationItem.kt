package de.schildbach.wallet.data

abstract class NotificationItem {

    abstract val isNew: Boolean

    abstract fun getId(): String
    abstract fun getDate(): Long
}