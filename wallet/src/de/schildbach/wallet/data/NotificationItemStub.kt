package de.schildbach.wallet.data

class NotificationItemStub(private val idValue: String) : NotificationItem() {

    override fun getId() = idValue
    override fun getDate() = System.currentTimeMillis()
}
