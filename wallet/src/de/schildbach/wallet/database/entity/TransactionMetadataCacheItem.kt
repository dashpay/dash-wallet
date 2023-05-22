/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.data.entity.GiftCard

@Entity(tableName = "transaction_metadata_cache")
data class TransactionMetadataCacheItem(
    var cacheTimestamp: Long, // time added to the table
    var txId: Sha256Hash,
    var sentTimestamp: Long? = null,
    var taxCategory: TaxCategory? = null,
    var currencyCode: String? = null,
    var rate: String? = null,
    var memo: String? = null,
    var service: String? = null,
    var customIconUrl: String? = null,
    var giftCardNumber: String? = null,
    var giftCardPin: String? = null,
    var merchantName: String? = null,
    var originalPrice: Double? = null,
    var barcodeValue: String? = null,
    var barcodeFormat: String? = null,
    var merchantUrl: String? = null
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    constructor(
        transactionMetadata: TransactionMetadata,
        giftCard: GiftCard? = null,
        customIconUrl: String? = null
    ) : this(
        System.currentTimeMillis(),
        transactionMetadata.txId,
        transactionMetadata.timestamp,
        transactionMetadata.taxCategory,
        transactionMetadata.currencyCode,
        transactionMetadata.rate,
        transactionMetadata.memo.ifEmpty { null },
        transactionMetadata.service,
        customIconUrl,
        giftCard?.number,
        giftCard?.pin,
        giftCard?.merchantName,
        giftCard?.price,
        giftCard?.barcodeValue,
        giftCard?.barcodeFormat?.toString(),
        giftCard?.merchantUrl
    )

    fun isNotEmpty(): Boolean {
        return sentTimestamp != null || taxCategory != null || !memo.isNullOrEmpty() ||
            currencyCode != null || rate != null || service != null || customIconUrl != null ||
            giftCardNumber != null || giftCardPin != null || merchantName != null || originalPrice != null ||
            barcodeValue != null || barcodeFormat != null || merchantUrl != null
    }
}
