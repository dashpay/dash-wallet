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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockchainIdentityDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: BlockchainIdentityData)

    @Query("UPDATE blockchain_identity SET creationState = :state, creationStateErrorMessage = :creationErrorMessage WHERE id = :id")
    suspend fun updateCreationState(id: Int, state: BlockchainIdentityData.CreationState, creationErrorMessage: String?)

    @Query("SELECT * FROM blockchain_identity LIMIT 1")
    suspend fun load(): BlockchainIdentityData?

    @Query("SELECT id, creationState, creationStateErrorMessage, username, userId, restoring, creditFundingTxId, usingInvite, invite FROM blockchain_identity LIMIT 1")
    suspend fun loadBase(): BlockchainIdentityBaseData?

    @Query("SELECT id, creationState, creationStateErrorMessage, username, userId, restoring, creditFundingTxId, usingInvite, invite FROM blockchain_identity LIMIT 1")
    fun observeBase(): Flow<BlockchainIdentityBaseData?>

    @Query("DELETE FROM blockchain_identity")
    suspend fun clear()
}
