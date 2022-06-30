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

package de.schildbach.wallet.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.TransactionMetadata

/**
 * @author Eric Britten
 */
@Dao
interface TransactionMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactionMetadata: TransactionMetadata)

    @Query("SELECT * FROM transaction_metadata")
    suspend fun load(): List<TransactionMetadata>

    @Query("SELECT COUNT(1) FROM transaction_metadata WHERE txid = :txid;")
    suspend fun exists(txid: Sha256Hash): Boolean

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txid")
    suspend fun load(txid: Sha256Hash): TransactionMetadata?

    @Query("SELECT * FROM transaction_metadata WHERE txid = :txid")
    fun observe(txid: Sha256Hash): Flow<TransactionMetadata?>

    @Query("SELECT * FROM transaction_metadata WHERE timestamp <= :end and timestamp >= :start")
    fun observeByTimestampRange(start: Long, end: Long): Flow<List<TransactionMetadata>>

    @Query("UPDATE transaction_metadata SET taxCategory = :taxCategory WHERE txid = :txid")
    suspend fun updateTaxCategory(txid: Sha256Hash, taxCategory: TaxCategory)

    @Query("DELETE FROM transaction_metadata")
    suspend fun clear()
}