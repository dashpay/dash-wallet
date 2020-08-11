package de.schildbach.wallet.data

class NotificationItemContact(val usernameSearchResult: UsernameSearchResult, override val isNew: Boolean = false, val isInvitationOfEstablished: Boolean = false)
    : NotificationItem() {

    override fun getId(): String {
        return when (usernameSearchResult.type) {
            UsernameSearchResult.Type.REQUEST_RECEIVED -> usernameSearchResult.fromContactRequest!!.userId
            UsernameSearchResult.Type.REQUEST_SENT -> usernameSearchResult.toContactRequest!!.toUserId
            UsernameSearchResult.Type.CONTACT_ESTABLISHED -> usernameSearchResult.toContactRequest!!.toUserId
        }
    }

    override fun getDate() = usernameSearchResult.date * 1000
}
