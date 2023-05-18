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

package de.schildbach.wallet.database.dao

import androidx.room.*
import de.schildbach.wallet.database.entity.TransactionMetadataDocument
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.ExchangeRate

/**
 * @author Eric Britten
 */
@Dao
interface TransactionMetadataDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactionMetadata: TransactionMetadataDocument)

    @Query("SELECT * FROM transaction_metadata_platform ORDER BY timestamp")
    suspend fun load(): List<TransactionMetadataDocument>

    @Query("SELECT COUNT(*) FROM transaction_metadata_platform WHERE id = :id")
    suspend fun count(id: String): Int

    @Query("""
        SELECT sentTimestamp 
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND sentTimestamp is NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getSentTimestamp(txId: Sha256Hash): Long?

    @Query("""
        SELECT memo 
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND memo is NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getTransactionMemo(txId: Sha256Hash): String?

    @Query("""
        SELECT service 
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND service is NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getTransactionService(txId: Sha256Hash): String?

    @Query("""
        SELECT taxCategory 
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND taxCategory is NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getTransactionTaxCategory(txId: Sha256Hash): String?

    @Query("""
        SELECT rate, currencyCode
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND rate IS NOT NULL AND currencyCode IS NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getTransactionExchangeRate(txId: Sha256Hash): ExchangeRate?

    @Query("""
        SELECT customIconUrl 
        FROM transaction_metadata_platform 
        WHERE txId = :txId 
            AND customIconUrl is NOT NULL
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun getTransactionIconUrl(txId: Sha256Hash): String?

    @Query("SELECT MAX(timestamp) FROM transaction_metadata_platform")
    fun getLastTimestamp() : Long

    @Query("SELECT COUNT(*) FROM transaction_metadata_platform")
    fun countAllRequests(): Int

    @Query("DELETE FROM transaction_metadata_platform")
    suspend fun clear()
}
