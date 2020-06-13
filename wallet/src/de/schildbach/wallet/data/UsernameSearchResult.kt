package de.schildbach.wallet.data

data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                val toContactRequest: DashPayContactRequest?,
                                val fromContactRequest: DashPayContactRequest?) {
    val requestSent = toContactRequest != null
    val requestReceived = fromContactRequest != null
    val isPendingRequest = requestReceived && ! requestSent
}