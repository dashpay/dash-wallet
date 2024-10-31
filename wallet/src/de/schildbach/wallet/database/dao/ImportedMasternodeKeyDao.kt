/*
 * Copyright 2024 Dash Core Group.
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
import de.schildbach.wallet.database.entity.ImportedMasternodeKey
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Sha256Hash

// This table only stores imported masternode keys
// Masternode keys that are managed by the "Masternode keys" screen are not
// kept in this table

@Dao
interface ImportedMasternodeKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(importedMasternodeKey: ImportedMasternodeKey)

    @Update
    suspend fun update(importedMasternodeKey: ImportedMasternodeKey)

    @Query("SELECT * FROM imported_masternode_keys")
    suspend fun getAll(): List<ImportedMasternodeKey>

    @Query("SELECT * FROM imported_masternode_keys")
    fun observeAll(): Flow<List<ImportedMasternodeKey>>

    @Query("DELETE FROM imported_masternode_keys")
    suspend fun clear()

    @Query("DELETE FROM imported_masternode_keys WHERE proTxHash = :proTxHash")
    suspend fun remove(proTxHash: Sha256Hash)

    @Query("SELECT COUNT(*) > 0 FROM imported_masternode_keys WHERE proTxHash = :proTxHash")
    suspend fun contains(proTxHash: Sha256Hash?): Boolean
}
