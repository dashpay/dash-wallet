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

/**
 * @author Eric Britten
 */
@Dao
interface TransactionMetadataDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactionMetadata: TransactionMetadataDocument)

    @Query("SELECT * FROM transaction_metadata_platform ORDER BY timestamp")
    suspend fun load(): List<TransactionMetadataDocument>

    @Query("SELECT COUNT(1) FROM transaction_metadata_platform WHERE id = :id")
    suspend fun exists(id: String): Boolean


    //@Query("SELECT * FROM transaction_metadata_platform WHERE txid = :txId")
    //fun observe(txId: Sha256Hash): Flow<TransactionMetadataDocument?>

    //@Query("SELECT id, txId, memo FROM transaction_metadata_platform WHERE memo != NULL")
    //fun observeMemos(): Flow<List<TransactionMetadataDocument>>

    //@Query("SELECT * FROM transaction_metadata_platform WHERE timestamp <= :end and timestamp >= :start")
    //fun observeByTimestampRange(start: Long, end: Long): Flow<List<TransactionMetadataDocument>>

    @Query("SELECT MAX(timestamp) FROM transaction_metadata_platform")
    fun getLastTimestamp() : Long

    @Query("SELECT COUNT(*) FROM transaction_metadata_platform")
    fun countAllRequests(): Int


    @Query("DELETE FROM transaction_metadata_platform")
    fun clear()
}