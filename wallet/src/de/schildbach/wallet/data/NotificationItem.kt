package de.schildbach.wallet.data

import org.bitcoinj.core.Transaction
import java.util.*

data class NotificationItem private constructor(val type: Type,
                                                val usernameSearchResult: UsernameSearchResult? = null,
                                                val tx: Transaction? = null) {

    constructor(usernameSearchResult: UsernameSearchResult) : this(Type.CONTACT_REQUEST, usernameSearchResult = usernameSearchResult)

    constructor(tx: Transaction) : this(Type.PAYMENT, tx = tx)

    enum class Type {
        CONTACT_REQUEST,
        CONTACT,
        PAYMENT
    }

    /* date is in milliseconds */
    val date = when (type) {
        Type.CONTACT_REQUEST, Type.CONTACT -> usernameSearchResult!!.date * 1000
        else -> tx!!.updateTime.time * 1000
    }

    val id = when (type) {
        Type.CONTACT_REQUEST -> usernameSearchResult!!.fromContactRequest!!.userId
        Type.CONTACT -> usernameSearchResult!!.toContactRequest!!.toUserId
        Type.PAYMENT -> tx!!.txId.toString()
    }
}
