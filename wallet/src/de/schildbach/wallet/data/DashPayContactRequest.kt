package de.schildbach.wallet.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import kotlinx.android.parcel.IgnoredOnParcel
import org.bitcoinj.core.Base58
import kotlinx.android.parcel.Parcelize
import org.dashevo.dpp.document.Document
import org.dashevo.dpp.identifier.Identifier

@Parcelize
@Entity(tableName = "dashpay_contact_request", primaryKeys = ["userId", "toUserId", "accountReference"])
data class DashPayContactRequest(val userId: String,
                                 val toUserId: String, //The contract has this as a binary field
                                 val accountReference: Int,
                                 val encryptedPublicKey: ByteArray,
                                 val senderKeyIndex: Int,
                                 val recipientKeyIndex: Int,
                                 val timestamp: Long,
                                 val encryptedAccountLabel: ByteArray?,
                                 val autoAcceptProof: ByteArray?
) : Parcelable {
    companion object {
        fun fromDocument(document: Document): DashPayContactRequest {
            val timestamp: Long = if (document.createdAt != null) document.createdAt!! else 0L
            val toUserId = Base58.encode(document.data["toUserId"] as ByteArray)

            val encryptedAccountLabel: ByteArray? = if (document.data.containsKey("encryptedAccountLabel"))
                document.data["encryptedAccountLabel"] as ByteArray
            else null

            val accountReference: Int = if (document.data.containsKey("accountReference"))
                document.data["accountReference"] as Int
            else 0

            val autoAcceptProof: ByteArray? = if (document.data.containsKey("autoAcceptProof"))
                document.data["autoAcceptProof"] as ByteArray
            else null

            return DashPayContactRequest(document.ownerId.toString(), toUserId,
                    accountReference,
                    document.data["encryptedPublicKey"] as ByteArray,
                    document.data["senderKeyIndex"] as Int,
                    document.data["recipientKeyIndex"] as Int,
                    timestamp,
                    encryptedAccountLabel,
                    autoAcceptProof)
        }
    }

    @delegate:Ignore
    val userIdentifier by lazy {
        Identifier.from(userId)
    }

    @delegate:Ignore
    val rawUserId by lazy {
        userIdentifier.toBuffer()
    }

    @IgnoredOnParcel
    @delegate:Ignore
    val toUserIdentifier by lazy {
        Identifier.from(toUserId)
    }

    @IgnoredOnParcel
    @delegate:Ignore
    val rawToUserId by lazy {
        toUserIdentifier.toBuffer()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DashPayContactRequest

        if (userId != other.userId) return false
        if (toUserId != other.toUserId) return false
        if (accountReference != other.accountReference) return false

        return true
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + toUserId.hashCode()
        result = 31 * result + accountReference.hashCode()
        return result
    }

    @IgnoredOnParcel
    val version: Int
        get() = accountReference ushr 28
}