package de.schildbach.wallet.data

class NotificationItemContact(val usernameSearchResult: UsernameSearchResult, val isInvitationOfEstablished: Boolean = false)
    : NotificationItem() {

    override fun getId(): String {
        return when (usernameSearchResult.type) {
            UsernameSearchResult.Type.REQUEST_RECEIVED -> usernameSearchResult.fromContactRequest!!.userId
            UsernameSearchResult.Type.REQUEST_SENT,
            UsernameSearchResult.Type.CONTACT_ESTABLISHED -> usernameSearchResult.toContactRequest!!.toUserId
            UsernameSearchResult.Type.NO_RELATIONSHIP -> throw IllegalStateException()
        }
    }

    override fun getDate() = usernameSearchResult.date
}
