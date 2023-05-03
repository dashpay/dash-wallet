package de.schildbach.wallet.database.entity

import androidx.room.Entity
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory

@Entity(tableName = "transaction_metadata_platform", primaryKeys = ["id", "txId"])
class TransactionMetadataDocument(
    val id: String,
    val timestamp: Long,
    var txId: Sha256Hash,
    var sentTimestamp: Long? = null,
    var taxCategory: TaxCategory? = null,
    var currencyCode: String? = null,
    var rate: Double? = null,
    var memo: String? = null,
    var service: String? = null,
    var customIconId: Sha256Hash? = null
) {

}