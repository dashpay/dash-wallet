package de.schildbach.wallet.data

import org.dashevo.dpp.document.Document

// This result data structure contains the Documents for several items
// At a later time we may wish to abstract away the usage of documents
// in the app for cleaner code or with other classes that handle this
// data.  Additionally the information may be coming from a database

data class UsernameSearchResult(val username: String,
                                val domainDocument: Document,
                                val toContactRequest: Document?,
                                val fromContactRequest: Document?) {
    val hasRequested = toContactRequest != null
    val hasAccepted = fromContactRequest != null
}