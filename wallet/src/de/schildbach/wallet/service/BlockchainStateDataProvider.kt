package de.schildbach.wallet.service

import de.schildbach.wallet.data.BlockchainStateDao
import kotlinx.coroutines.flow.Flow
import org.dash.wallet.common.data.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import javax.inject.Inject

class BlockchainStateDataProvider @Inject constructor(val blockchainStateDao: BlockchainStateDao) : BlockchainStateProvider {
    override suspend fun getState(): BlockchainState? {
        return blockchainStateDao.loadSync()
    }

    override fun observeState(): Flow<BlockchainState?> {
        return blockchainStateDao.observeState()
    }
}