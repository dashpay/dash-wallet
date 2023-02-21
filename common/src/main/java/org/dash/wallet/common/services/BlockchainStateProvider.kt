package org.dash.wallet.common.services

import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.AbstractBlockChain
import org.dash.wallet.common.data.BlockchainState
import org.dash.wallet.common.data.NetworkStatus

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

    fun getNetworkStatus(): NetworkStatus
    fun observeNetworkStatus(): Flow<NetworkStatus>

    fun getBlockChain(): AbstractBlockChain?
    fun observeBlockChain(): Flow<AbstractBlockChain?>
}