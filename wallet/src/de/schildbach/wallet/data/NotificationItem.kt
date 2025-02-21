package de.schildbach.wallet.data

abstract class NotificationItem {
    abstract fun getId(): String
    abstract fun getDate(): Long
}