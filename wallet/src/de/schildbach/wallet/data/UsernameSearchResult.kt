package de.schildbach.wallet.data

import kotlin.math.max

data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                var toContactRequest: DashPayContactRequest?,
                                var fromContactRequest: DashPayContactRequest?) {
    val requestSent: Boolean
            get() = toContactRequest != null
    val requestReceived: Boolean
            get () = fromContactRequest != null
    val isPendingRequest: Boolean
            get() = requestReceived && !requestSent
    val date: Long // milliseconds
            get() {
                return when (requestSent to requestReceived) {
                    true to true -> {
                        fromContactRequest!!.timestamp
                    }
                    true to false -> {
                        toContactRequest!!.timestamp
                    }
                    false to true -> {
                        fromContactRequest!!.timestamp
                    }
                    else -> 0.00
                }.toLong()
            }
}