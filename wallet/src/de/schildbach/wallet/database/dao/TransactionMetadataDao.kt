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
import org.bitcoinj.core.Sha256Hash
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
    suspend fun exists(txId: Sha256Hash): Boolean

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txId")
    suspend fun load(txId: Sha256Hash): TransactionMetadata?

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txId")
    fun observe(txId: Sha256Hash): Flow<TransactionMetadata?>

    @MapInfo(keyColumn = "txId")
    @Query(
        """SELECT txId, memo, service, customIconId FROM transaction_metadata 
        WHERE memo != '' OR service IS NOT NULL OR customIconId IS NOT NULL"""
    )
    fun observePresentableMetadata(): Flow<Map<Sha256Hash, PresentableTxMetadata>>

    @Query("SELECT * FROM transaction_metadata WHERE timestamp <= :end and timestamp >= :start")
    fun observeByTimestampRange(start: Long, end: Long): Flow<List<TransactionMetadata>>

    @Query("UPDATE transaction_metadata SET taxCategory = :taxCategory WHERE txid = :txId")
    suspend fun updateTaxCategory(txId: Sha256Hash, taxCategory: TaxCategory)

    @Query("UPDATE transaction_metadata SET timestamp = :timestamp WHERE txid = :txId")
    suspend fun updateSentTime(txId: Sha256Hash, timestamp: Long)

    @Query("UPDATE transaction_metadata SET memo = :memo WHERE txid = :txId")
    suspend fun updateMemo(txId: Sha256Hash, memo: String)

    @Query("UPDATE transaction_metadata SET currencyCode = :currencyCode, rate = :rate WHERE txId = :txId")
    suspend fun updateExchangeRate(txId: Sha256Hash, currencyCode: String, rate: String)

    @Query("UPDATE transaction_metadata SET service = :service WHERE txid = :txId")
    suspend fun updateService(txId: Sha256Hash, service: String)

    @Query("UPDATE transaction_metadata SET customIconId = :iconId WHERE txId = :txId")
    suspend fun updateIconId(txId: Sha256Hash, iconId: Sha256Hash)

    @Query("DELETE FROM transaction_metadata")
    suspend fun clear()
}
