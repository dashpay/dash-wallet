package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transaction_metadata_platform")
class TransactionMetadataDocument(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val documentData: ByteArray
) {

}