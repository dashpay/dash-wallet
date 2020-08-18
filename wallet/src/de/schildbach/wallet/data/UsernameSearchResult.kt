package de.schildbach.wallet.data

data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                var toContactRequest: DashPayContactRequest?,
                                var fromContactRequest: DashPayContactRequest?) {
    val requestSent: Boolean
        get() = toContactRequest != null
    val requestReceived: Boolean
        get() = fromContactRequest != null
    val isPendingRequest: Boolean
        get() = requestReceived && !requestSent

    val date: Long // milliseconds
        get() {
            return when (type) {
                Type.CONTACT_ESTABLISHED -> {
                    fromContactRequest!!.timestamp
                }
                Type.REQUEST_SENT -> {
                    toContactRequest!!.timestamp
                }
                Type.REQUEST_RECEIVED -> {
                    fromContactRequest!!.timestamp
                }
            }.toLong()
        }

    val type: Type
        get() = when (requestSent to requestReceived) {
            true to true -> Type.CONTACT_ESTABLISHED
            false to true -> Type.REQUEST_RECEIVED
            true to false -> Type.REQUEST_SENT
            else -> throw IllegalStateException("toContactRequest and fromContactRequest can't both be null at the same time")
        }

    enum class Type {
        REQUEST_SENT,
        REQUEST_RECEIVED,
        CONTACT_ESTABLISHED
    }
}