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
    /**
     * Creates the metadata row for [txId] if it has none, from the wallet's own
     * record of the transaction (the dashj wallet, else the SDK store) and the
     * fetched platform metadata documents. A no-op when the row exists.
     */
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
    /**
     * Merges metadata fetched from platform into the row for [txId], creating the
     * row first when needed (see [importTransactionMetadata]; [metadata] itself is
     * the last-resort row). Non-empty platform fields win; txid and value are kept.
     * Stores [giftCard] and fetches [iconUrl] when given.
     */
    suspend fun syncPlatformMetadata(
        txId: TxId,
        metadata: TransactionMetadata,
        giftCard: GiftCard?,
        iconUrl: String?
    )

    /**
     * Runs [block], which will import metadata for [txIds] one at a time (for
     * example a platform metadata sync), with any per-transaction lookup work
     * for those txids done once for the whole batch. Purely an optimization:
     * the result is the same as running [block] alone, which is the default.
     */
    suspend fun <T> withBatchedImport(txIds: Collection<TxId>, block: suspend () -> T): T = block()

    /**
     * The metadata row for [txId], creating it first when the wallet holds the
     * transaction but has no row (see [importTransactionMetadata]). A missing tax
     * category is filled from address metadata. Null when no row can be made.
     */
    suspend fun getTransactionMetadata(txId: TxId): TransactionMetadata?
    fun observeTransactionMetadata(txId: TxId): Flow<TransactionMetadata?>

    /**
     * Mark a transaction as DashSpend gift card expense with an icon
     */
    suspend fun markGiftCardTransaction(txId: TxId, service: String, iconUrl: String?)
    suspend fun updateGiftCardMetadata(giftCard: GiftCard)
    suspend fun updateGiftCardBarcode(txId: TxId, index: Int, barcodeValue: String, barcodeFormat: BarcodeFormat)

    /**
     * Discards everything recorded locally about a transaction: its metadata, any gift cards
     * saved against it, and any changes still queued for Dash Platform.
     *
     * For payments recorded optimistically that turn out never to have reached the merchant, so
     * the transaction will never exist. Implementations must ignore a transaction that is present
     * in the wallet, since that would be real user data.
     *
     * @return true when nothing further is needed, either because the records were discarded or
     *   because the transaction turned out to be real; false when the cleanup could not be
     *   attempted and the caller should keep the payment and retry later.
     */
    suspend fun forgetTransaction(txId: Sha256Hash): Boolean

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
