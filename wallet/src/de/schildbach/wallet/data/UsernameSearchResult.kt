package de.schildbach.wallet.data

// This result data structure contains the Documents for several items
// At a later time we may wish to abstract away the usage of documents
// in the app for cleaner code or with other classes that handle this
// data.  Additionally the information may be coming from a database

data class UsernameSearchResult(val username: String,
                                val dashPayProfile: DashPayProfile,
                                val toContactRequest: DashPayContactRequest?,
                                val fromContactRequest: DashPayContactRequest?) {
    val haveWeRequested = toContactRequest != null
    val hasOtherRequested = fromContactRequest != null
    val isPendingRequest = hasOtherRequested && ! haveWeRequested
}