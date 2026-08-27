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
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.TxId
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata

/**
 * @author Eric Britten
 */
@Dao
interface TransactionMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactionMetadata: TransactionMetadata)

    @Update(entity = TransactionMetadata::class)
    suspend fun update(transactionMetadata: TransactionMetadata)

    @Query("SELECT * FROM transaction_metadata")
    suspend fun load(): List<TransactionMetadata>

    @Query("SELECT COUNT(1) FROM transaction_metadata WHERE txid = :txId;")
    suspend fun exists(txId: TxId): Boolean

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txId")
    suspend fun load(txId: TxId): TransactionMetadata?

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txId")
    fun observe(txId: TxId): Flow<TransactionMetadata?>

    @MapInfo(keyColumn = "txId")
    @Query(
        """SELECT txId, memo, service, customIconId FROM transaction_metadata
        WHERE memo != '' OR service IS NOT NULL OR customIconId IS NOT NULL"""
    )
    fun observePresentableMetadata(): Flow<Map<TxId, PresentableTxMetadata>>

    /** One-shot [observePresentableMetadata] restricted to [txIds] (callers chunk below SQLite's 999-variable cap). */
    @MapInfo(keyColumn = "txId")
    @Query(
        """SELECT txId, memo, service, customIconId FROM transaction_metadata
        WHERE txId IN (:txIds) AND (memo != '' OR service IS NOT NULL OR customIconId IS NOT NULL)"""
    )
    suspend fun loadPresentableMetadata(txIds: List<TxId>): Map<TxId, PresentableTxMetadata>

    @Query("SELECT * FROM transaction_metadata WHERE timestamp <= :end and timestamp >= :start")
    fun observeByTimestampRange(start: Long, end: Long): Flow<List<TransactionMetadata>>

    @Query("UPDATE transaction_metadata SET taxCategory = :taxCategory WHERE txid = :txId")
    suspend fun updateTaxCategory(txId: TxId, taxCategory: TaxCategory)

    @Query("UPDATE transaction_metadata SET timestamp = :timestamp WHERE txid = :txId")
    suspend fun updateSentTime(txId: TxId, timestamp: Long)

    @Query("UPDATE transaction_metadata SET memo = :memo WHERE txid = :txId")
    suspend fun updateMemo(txId: TxId, memo: String)

    @Query("UPDATE transaction_metadata SET currencyCode = :currencyCode, rate = :rate WHERE txId = :txId")
    suspend fun updateExchangeRate(txId: TxId, currencyCode: String, rate: String)

    @Query("UPDATE transaction_metadata SET service = :service WHERE txid = :txId")
    suspend fun updateService(txId: TxId, service: String)

    @Query("UPDATE transaction_metadata SET customIconId = :iconId WHERE txId = :txId")
    suspend fun updateIconId(txId: TxId, iconId: TxId)

    @Query("DELETE FROM transaction_metadata")
    suspend fun clear()
}
