package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dashpay_contact_request")
data class DashPayContactRequest(@PrimaryKey val entropy: String,
                                 val userId: String,
                                 val toUserId: String,
                                 val privateData: ByteArray?,
                                 val encryptedPublicKey: ByteArray,
                                 val senderKeyIndex: Int,
                                 val recipientKeyIndex: Int,
                                 val timestamp: Double,
                                 val hidden: Boolean, // is the request from another user hidden (local)
                                 val dateAdded: Long  // when was this request created or accepted (local)
                                )