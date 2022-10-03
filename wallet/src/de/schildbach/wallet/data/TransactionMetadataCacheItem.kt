package de.schildbach.wallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.TransactionMetadata

@Entity(tableName = "transaction_metadata_cache")
data class TransactionMetadataCacheItem(
    var cacheTimestamp: Long,  // time added to the table
    var txId: Sha256Hash,
    var sentTimestamp: Long? = null,
    var taxCategory: TaxCategory? = null,
    var currencyCode: String? = null,
    var rate: String? = null,
    var memo: String? = null,
    var service: String? = null
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    constructor(transactionMetadata: TransactionMetadata) : this(
        System.currentTimeMillis(),
        transactionMetadata.txId,
        transactionMetadata.timestamp,
        transactionMetadata.taxCategory,
        transactionMetadata.currencyCode,
        transactionMetadata.rate,
        transactionMetadata.memo.ifEmpty { null },
        transactionMetadata.service
    )

    fun isNotEmpty(): Boolean {
        return sentTimestamp != null || taxCategory != null || !memo.isNullOrEmpty() || currencyCode != null || rate != null || service != null
    }
}
