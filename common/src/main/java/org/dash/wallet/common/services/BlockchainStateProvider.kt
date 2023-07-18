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

package org.dash.wallet.common.services

import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.entity.BlockchainState

/**
 * Blockchain state provider
 *
 * This data provider gives access to the current BlockchainState (block height, isSynced)
 * as well as other data that depends on the current state of the blockchain.
 *
 * This includes:
 *  - Masternode APY (function of block height, difficulty, and network)
 */

interface BlockchainStateProvider {
    suspend fun getState(): BlockchainState?
    fun observeState() : Flow<BlockchainState?>
    fun getMasternodeAPY(): Double
    fun getLastMasternodeAPY(): Double
}
