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
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata

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
        return taxCategory != null || !memo.isNullOrEmpty() ||
            currencyCode != null || rate != null || service != null || customIconUrl != null ||
            giftCardNumber != null || giftCardPin != null || merchantName != null || originalPrice != null ||
            barcodeValue != null || barcodeFormat != null || merchantUrl != null
    }

    fun isEmpty(): Boolean = !isNotEmpty()

    /** only store changes (this - other) */
    operator fun minus(other: TransactionMetadataCacheItem): TransactionMetadataCacheItem {
        if (other.txId != txId) {
            throw IllegalArgumentException("other has a different txId ${other.txId} (other) != $txId (this)")
        }
        return TransactionMetadataCacheItem(
            System.currentTimeMillis(),
            txId,
            sentTimestamp = if (sentTimestamp == other.sentTimestamp) null else sentTimestamp,
            taxCategory = if (taxCategory == other.taxCategory) null else taxCategory,
            currencyCode = if (currencyCode == other.currencyCode) null else currencyCode,
            rate = if (rate == other.rate) null else rate,
            memo = if (memo == other.memo) null else memo,
            service = if (service == other.service) null else service,
            customIconUrl = if (customIconUrl == other.customIconUrl) null else customIconUrl,
            giftCardNumber = if (giftCardNumber == other.giftCardNumber) null else giftCardNumber,
            giftCardPin = if (giftCardPin == other.giftCardPin) null else giftCardPin,
            merchantName = if (merchantName == other.merchantName) null else merchantName,
            originalPrice = if (originalPrice == other.originalPrice) null else originalPrice,
            barcodeValue = if (barcodeValue == other.barcodeValue) null else barcodeValue,
            barcodeFormat = if (barcodeFormat == other.barcodeFormat) null else barcodeFormat,
            merchantUrl = if (merchantUrl == other.merchantUrl) null else merchantUrl,
        )
    }

    fun compare(currentItem: TransactionMetadata, giftCard: GiftCard?): Boolean {
        val txData =  txId == currentItem.txId &&
                (this.memo ?: "") == currentItem.memo &&
                this.taxCategory == currentItem.taxCategory &&
                this.service == currentItem.service &&
                this.currencyCode == currentItem.currencyCode &&
                this.rate == currentItem.rate
        val giftCardEquals = giftCard?.let {
            this.giftCardNumber == giftCard.number &&
                    this.giftCardPin == giftCard.pin &&
                    this.barcodeValue == giftCard.barcodeValue &&
                    this.barcodeFormat == giftCard.barcodeFormat.toString() &&
                    this.merchantName == giftCard.merchantName &&
                    this.merchantUrl == giftCard.merchantUrl &&
                    this.originalPrice == giftCard.price
        }

        return txData && if (giftCard != null) giftCardEquals!! else true
    }
}
