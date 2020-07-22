package de.schildbach.wallet.data

import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletTransaction
import java.util.*

data class NotificationItem private constructor(val type: Type,
                             val usernameSearchResult: UsernameSearchResult? = null,
                             val tx: WalletTransaction? = null) {

    constructor(usernameSearchResult: UsernameSearchResult) : this(Type.CONTACT_REQUEST, usernameSearchResult = usernameSearchResult)

    constructor(tx: WalletTransaction) : this(Type.PAYMENT, tx = tx)

    enum class Type {
        CONTACT_REQUEST,
        CONTACT,
        PAYMENT
    }

    val date = when (type) {
        Type.CONTACT_REQUEST, Type.CONTACT -> usernameSearchResult!!.date * 1000
        else -> throw IllegalStateException("only contact requests handled for now")
    }

    val id = when (type) {
        Type.CONTACT_REQUEST -> usernameSearchResult!!.fromContactRequest!!.userId
        Type.CONTACT -> usernameSearchResult!!.toContactRequest!!.toUserId
        else -> throw IllegalStateException("only contact requests handled for now")
    }
}