/*
 * Copyright (c) 2022. Dash Core Group.
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

package org.dash.wallet.common.services

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata

interface TransactionMetadataProvider {
    suspend fun setTransactionMetadata(transactionMetadata: TransactionMetadata)
    suspend fun importTransactionMetadata(txId: TxId)

    /**
     * @param fallbackMetadata a minimal row to create when the tx has no existing metadata
     *   AND no dashj wallet Transaction to derive one from (an SDK-only tx). Ignored when a
     *   row already exists or the dashj wallet holds the tx. Lets user edits persist for
     *   transactions the dashj wallet does not hold.
     */
    suspend fun setTransactionTaxCategory(
        txId: TxId,
        taxCategory: TaxCategory,
        isSyncingPlatform: Boolean = false,
        fallbackMetadata: TransactionMetadata? = null
    )
    suspend fun setTransactionType(txId: TxId, type: Int, isSyncingPlatform: Boolean = false)
    suspend fun setTransactionExchangeRate(txId: TxId, exchangeRate: ExchangeRate, isSyncingPlatform: Boolean = false)
    suspend fun setTransactionMemo(
        txId: TxId,
        memo: String,
        isSyncingPlatform: Boolean = false,
        fallbackMetadata: TransactionMetadata? = null
    )
    suspend fun setTransactionService(txId: TxId, service: String, isSyncingPlatform: Boolean = false)
    suspend fun setTransactionSentTime(txId: TxId, timestamp: Long, isSyncingPlatform: Boolean = false)
    suspend fun syncPlatformMetadata(
        txId: TxId,
        metadata: TransactionMetadata,
        giftCard: GiftCard?,
        iconUrl: String?
    )

    suspend fun getTransactionMetadata(txId: TxId): TransactionMetadata?
    fun observeTransactionMetadata(txId: TxId): Flow<TransactionMetadata?>

    /**
     * Mark a transaction as DashSpend gift card expense with an icon
     */
    suspend fun markGiftCardTransaction(txId: TxId, service: String, iconUrl: String?)
    suspend fun updateGiftCardMetadata(giftCard: GiftCard)
    suspend fun updateGiftCardBarcode(txId: TxId, index: Int, barcodeValue: String, barcodeFormat: BarcodeFormat)

    suspend fun getAllTransactionMetadata(): List<TransactionMetadata>

    fun observePresentableMetadata(): Flow<Map<TxId, PresentableTxMetadata>>
    suspend fun getIcon(iconId: TxId): Bitmap?

    // Address methods
    /**
     * mark an address with a tax category.  This will replace existing data
     *
     * @param address the address to mark
     * @param isInput the address is an input in a transaction
     * @param taxCategory the tax category
     * @param service the name of the service associated with this address
     */
    suspend fun markAddressWithTaxCategory(
        address: String,
        isInput: Boolean,
        taxCategory: TaxCategory,
        service: String
    )

    /**
     * mark an address with a tax category if it hasn't been marked
     *
     * @param address the address to mark
     * @param isInput the address is an input in a transaction
     * @param taxCategory the tax category
     * @param service the name of the service associated with this address
     */
    suspend fun maybeMarkAddressWithTaxCategory(
        address: String,
        isInput: Boolean,
        taxCategory: TaxCategory,
        service: String
    ): Boolean

    /**
     * Same as [markAddressWithTaxCategory] but as a non-blocking call
     */
    fun markAddressAsync(address: String, isInput: Boolean, taxCategory: TaxCategory, service: String)

    /**
     * Mark a destination address as TransferOut
     */
    fun markAddressAsTransferOutAsync(address: String, service: String) {
        markAddressAsync(address, false, TaxCategory.TransferOut, service)
    }

    /**
     * Mark a receiving address as TransferIn
     */
    fun markAddressAsTransferInAsync(address: String, service: String) {
        markAddressAsync(address, false, TaxCategory.TransferIn, service)
    }

    /**
     * check if the tx metadata table has metadata for the given tx.
     */
    suspend fun exists(txId: TxId): Boolean

    // Reset methods
    suspend fun clear()
}
