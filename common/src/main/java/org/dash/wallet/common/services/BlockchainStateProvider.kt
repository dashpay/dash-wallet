package org.dash.wallet.common.services

import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.BlockchainState

interface BlockchainStateProvider {
    suspend fun getState(): BlockchainState?
    fun observeState() : Flow<BlockchainState?>
}