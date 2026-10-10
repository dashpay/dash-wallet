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
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.SyncStage
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
    suspend fun getMasternodeAPY(): Double
    suspend fun getLastMasternodeAPY(): Double

    fun getNetworkStatus(): NetworkStatus
    fun observeNetworkStatus(): Flow<NetworkStatus>

    /**
     * Peers currently connected. [NetworkStatus] is not a substitute: it only leaves CONNECTED by
     * way of DISCONNECTING, so it can read CONNECTED with no peers at all when P2P connectivity
     * disappears while the device still has internet.
     *
     * Counts the dashj peer group only. Once the SDK owns L1 that peer group is held, so this
     * reads 0 and says nothing about the network; the SDK engine exposes no peer count.
     */
    fun getConnectedPeerCount(): Int
    fun observeConnectedPeerCount(): Flow<Int>

    fun observeSyncStage(): Flow<SyncStage?>
    fun getSyncStage(): SyncStage
}
