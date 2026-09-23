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
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.entity.BlockchainState

/**
 * @author Samuel Barbosa
 */
@Dao
abstract class BlockchainStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(blockchainState: BlockchainState)

    /**
     * [clearReplayAtHundredPercent]: the dashj-era rule that a row at 100% is
     * no longer replaying. Right ONLY for the writer whose percent IS the scan
     * position — dashj's `updateBlockchainState`, which opts in. Every other
     * writer preserves the flag it was handed: the SDK writer's display
     * percent reaches 100 at the iOS aggregate threshold while the engine may
     * still be replaying (plan §34/§35) and carries the flag from a lifecycle
     * signal of its own, and an impediment-only rewrite of that same row
     * (`updateImpediments`, on every connectivity callback) must not end the
     * replay it did not touch (review, 2026-09-23). Hence default false.
     */
    suspend fun saveState(blockchainState: BlockchainState, clearReplayAtHundredPercent: Boolean = false) {
        if (clearReplayAtHundredPercent && blockchainState.replaying && blockchainState.percentageSync == 100) {
            blockchainState.replaying = false
        }
        insert(blockchainState)
    }

    @Query("SELECT * FROM blockchain_state LIMIT 1")
    abstract suspend fun getState(): BlockchainState?

    @Query("SELECT * FROM blockchain_state LIMIT 1")
    abstract fun observeState(): Flow<BlockchainState?>
}
