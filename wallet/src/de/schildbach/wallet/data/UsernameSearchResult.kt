package de.schildbach.wallet.data

import kotlin.math.max

data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                val toContactRequest: DashPayContactRequest?,
                                val fromContactRequest: DashPayContactRequest?) {
    val requestSent = toContactRequest != null
    val requestReceived = fromContactRequest != null
    val isPendingRequest = requestReceived && !requestSent
    val date: Long // milliseconds
            get() {
                return when (requestSent to requestReceived) {
                    true to true -> {
                        max(toContactRequest!!.timestamp, fromContactRequest!!.timestamp)
                    }
                    true to false -> {
                        toContactRequest!!.timestamp
                    }
                    false to true -> {
                        fromContactRequest!!.timestamp
                    }
                    else -> 0.00
                }.toLong() * 1000
            }
}