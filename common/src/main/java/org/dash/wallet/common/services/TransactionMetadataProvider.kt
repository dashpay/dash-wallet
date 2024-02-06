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
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata

interface TransactionMetadataProvider {
    suspend fun setTransactionMetadata(transactionMetadata: TransactionMetadata)
    suspend fun importTransactionMetadata(txId: Sha256Hash)
    suspend fun setTransactionTaxCategory(txId: Sha256Hash, taxCategory: TaxCategory)
    suspend fun setTransactionType(txId: Sha256Hash, type: Int)
    suspend fun setTransactionExchangeRate(txId: Sha256Hash, exchangeRate: ExchangeRate)
    suspend fun setTransactionMemo(txId: Sha256Hash, memo: String)
    suspend fun setTransactionService(txId: Sha256Hash, service: String)

    /**
     * Checks for missing data in the metadata cache vs the Transaction and ensures that both
     * are the same.
     *
     * @param tx The transaction to sync with the transaction metadata cache
     */
    suspend fun syncTransaction(tx: Transaction)
    fun syncTransactionBlocking(tx: Transaction)

    suspend fun getTransactionMetadata(txId: Sha256Hash): TransactionMetadata?
    fun observeTransactionMetadata(txId: Sha256Hash): Flow<TransactionMetadata?>

    /**
     * Mark a transaction as DashSpend gift card expense with an icon
     */
    suspend fun markGiftCardTransaction(txId: Sha256Hash, iconUrl: String?)
    suspend fun updateGiftCardMetadata(giftCard: GiftCard)
    suspend fun updateGiftCardBarcode(txId: Sha256Hash, barcodeValue: String, barcodeFormat: BarcodeFormat)

    suspend fun getAllTransactionMetadata(): List<TransactionMetadata>

    fun observePresentableMetadata(): Flow<Map<Sha256Hash, PresentableTxMetadata>>

    suspend fun getIcon(iconId: Sha256Hash): Bitmap?

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

    // Reset methods
    fun clear()
}
