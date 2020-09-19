package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.bitcoinj.core.Base58
import org.dashevo.dpp.document.Document

@Entity(tableName = "dashpay_contact_request", primaryKeys = ["userId", "toUserId"])
data class DashPayContactRequest(val userId: String,
                                 val toUserId: String, //The contract has this as a binary field
                                 val encryptedPublicKey: ByteArray,
                                 val senderKeyIndex: Int,
                                 val recipientKeyIndex: Int,
                                 val timestamp: Long,
                                 val encryptedAccountLabel: ByteArray?
) {
    companion object {
        fun fromDocument(document: Document): DashPayContactRequest {
            val timestamp: Long = if (document.createdAt != null) document.createdAt!! else 0L
            val toUserId = Base58.encode(document.data["toUserId"] as ByteArray)

            val encryptedAccountLabel: ByteArray? = if (document.data.containsKey("encryptedAccountLabel"))
                document.data["encryptedAccountLabel"] as ByteArray
            else null

            return DashPayContactRequest(document.ownerId, toUserId,
                    document.data["encryptedPublicKey"] as ByteArray,
                    document.data["senderKeyIndex"] as Int,
                    document.data["recipientKeyIndex"] as Int,
                    timestamp,
                    encryptedAccountLabel)
        }
    }
}