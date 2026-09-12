/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import org.bitcoinj.core.Sha256Hash

/**
 * Erases every local record of a single transaction. The deletes span three tables, so they live
 * on one DAO to run inside a single Room transaction: a partial cleanup would leave rows behind
 * with nothing left to retry them.
 *
 * Only for transactions that were recorded optimistically and turned out never to have been
 * broadcast. Callers must confirm the wallet does not hold the transaction first.
 */
@Dao
interface TransactionRecordsDao {
    @Query("SELECT COUNT(*) FROM gift_cards WHERE txId = :txId")
    suspend fun countGiftCards(txId: Sha256Hash): Int

    @Query("DELETE FROM gift_cards WHERE txId = :txId")
    suspend fun removeGiftCards(txId: Sha256Hash)

    /** Queued Dash Platform changes; dropping these stops the metadata being published. */
    @Query("DELETE FROM transaction_metadata_cache WHERE txId = :txId")
    suspend fun removeQueuedMetadataChanges(txId: Sha256Hash)

    @Query("DELETE FROM transaction_metadata WHERE txid = :txId")
    suspend fun removeMetadata(txId: Sha256Hash)

    /**
     * Drops the gift cards, the queued platform changes and the metadata together.
     * Queued changes go before the metadata so a concurrent publish cannot re-add it.
     *
     * @return how many gift cards were removed
     */
    @Transaction
    suspend fun forgetTransaction(txId: Sha256Hash): Int {
        val cards = countGiftCards(txId)
        removeGiftCards(txId)
        removeQueuedMetadataChanges(txId)
        removeMetadata(txId)
        return cards
    }
}
