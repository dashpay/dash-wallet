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

package de.schildbach.wallet.service

import android.content.Context
import android.database.sqlite.SQLiteException
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.BlockchainStateDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Block
import org.bitcoinj.core.BlockChain
import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.store.BlockStoreException
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.SyncStage
import org.dash.wallet.common.data.entity.BlockchainState.Impediment
import org.dash.wallet.common.services.BlockchainStateProvider
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.util.EnumSet
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Blockchain state data provider
 *
 * @property blockchainStateDao
 * @property walletDataProvider is used to determine the network parameters and DashJ Context
 * @property configuration is used to save some information
 *
 */

@Singleton
class BlockchainStateDataProvider @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val dashSystemService: DashSystemService,
    private val blockchainStateDao: BlockchainStateDao,
    private val walletDataProvider: WalletData,
    private val configuration: Configuration
) : BlockchainStateProvider {
    companion object {
        /**
         *  NetworkParameters.TARGET_SPACING is 2.5 min, but the DGW algorithm targets 2.625 min
         *  See [org.bitcoinj.params.AbstractBitcoinNetParams.DarkGravityWave]
         */
        const val BLOCK_TARGET_SPACING = 2.625 * 60 // seconds
        const val DAYS_PER_YEAR = 365.242199
        const val SECONDS_PER_DAY = 24 * 60 * 60
        val MASTERNODE_COST: Coin = Coin.valueOf(1000, 0)
        const val MASTERNODE_COUNT = 3800
    }

    // this coroutineScope should execute all jobs sequentially
    private val coroutineScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    private val networkStatusFlow = MutableStateFlow(NetworkStatus.UNKNOWN)
    private val syncStageFlow = MutableStateFlow<SyncStage?>(null)

    /**
     * The impediment set as last reported by the dashj service's
     * connectivity/storage/security monitors ([updateImpediments] /
     * [updateBlockchainState]) — those monitors keep running post-cutover
     * (they are Android-side, not peergroup-side), so they stay the source
     * of truth for connectivity while the SDK derivation
     * ([updateSdkBlockchainState]) contributes only the progress-stall
     * NETWORK bit. Both writers compose the row's impediments the same way
     * ([composeImpediments]); everything runs on the serial [coroutineScope].
     */
    private var serviceImpediments: Set<Impediment> = emptySet()

    /** Kill-list Step B: the SDK-derived progress-stall NETWORK impediment (post-cutover only). */
    private var sdkStallNetworkImpediment = false

    override suspend fun getState(): BlockchainState? {
        return blockchainStateDao.getState()
    }

    override fun observeState(): Flow<BlockchainState?> {
        return blockchainStateDao.observeState().distinctUntilChanged()
    }

    fun updateImpediments(impediments: Set<Impediment>) {
        coroutineScope.launch {
            serviceImpediments = impediments
            val blockchainState = blockchainStateDao.getState()
            if (blockchainState != null) {
                blockchainState.impediments.clear()
                blockchainState.impediments.addAll(composeImpediments())
                blockchainStateDao.saveState(blockchainState)
            }
        }
    }

    /**
     * Service-reported impediments plus the SDK-derived stall NETWORK bit.
     * Pre-cutover [sdkStallNetworkImpediment] is permanently false, so this
     * is exactly the service set — byte-identical to the pre-Step-B path.
     */
    private fun composeImpediments(): EnumSet<Impediment> {
        val impediments = EnumSet.noneOf(Impediment::class.java)
        impediments.addAll(serviceImpediments)
        if (sdkStallNetworkImpediment) {
            impediments.add(Impediment.NETWORK)
        }
        return impediments
    }

    /**
     * Kill-list Step B (sync-state track): apply one SDK-derived
     * [de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateUpdate]
     * to the persisted row — the post-cutover replacement for
     * [updateBlockchainState], fed by
     * [de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateService]'s
     * equality-gated poll of the SDK SPV progress. Null fields preserve the
     * row's current value (see the update class KDoc); `chainlockHeight`
     * is always preserved — the SDK exposes no chainlock-height feed yet
     * (documented gap), so the row keeps the last dashj-known value.
     * Serialized with every other writer on [coroutineScope].
     */
    internal fun updateSdkBlockchainState(update: de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateUpdate) {
        coroutineScope.launch {
            sdkStallNetworkImpediment = update.networkStalled
            val blockchainState = blockchainStateDao.getState() ?: BlockchainState()
            update.bestChainHeight?.let { blockchainState.bestChainHeight = it }
            update.bestChainDateMs?.let { blockchainState.bestChainDate = java.util.Date(it) }
            update.mnListHeight?.let { blockchainState.mnlistHeight = it }
            blockchainState.percentageSync = update.percentageSync
            // The SDK has no replay concept — its re-scan reads as percent < 100.
            blockchainState.replaying = false
            blockchainState.impediments = composeImpediments()
            blockchainStateDao.saveState(blockchainState)
            syncStageFlow.value = update.syncStage
        }
    }

    fun updateBlockchainState(blockChain: BlockChain, impediments: Set<Impediment>, percentageSync: Int, syncStage: PeerGroup.SyncStage?) {
        coroutineScope.launch {
            var blockchainState = blockchainStateDao.getState()
            if (blockchainState == null) {
                blockchainState = BlockchainState()
            }
            val chainHead: StoredBlock = blockChain.chainHead
            val chainLockHeight = dashSystemService.system.chainLockHandler.bestChainLockBlockHeight
            val mnListHeight: Int =
                dashSystemService.system.masternodeListManager.listAtChainTip.height.toInt()
            serviceImpediments = impediments
            blockchainState.bestChainDate = chainHead.header.time
            blockchainState.bestChainHeight = chainHead.height
            blockchainState.impediments = composeImpediments()
            blockchainState.chainlockHeight = chainLockHeight
            blockchainState.mnlistHeight = mnListHeight
            blockchainState.percentageSync = percentageSync
            blockchainStateDao.saveState(blockchainState)
            syncStageFlow.value = syncStage?.toNeutral()
        }
    }

    private fun PeerGroup.SyncStage.toNeutral(): SyncStage = when (this) {
        PeerGroup.SyncStage.OFFLINE -> SyncStage.OFFLINE
        PeerGroup.SyncStage.HEADERS -> SyncStage.HEADERS
        PeerGroup.SyncStage.MNLIST -> SyncStage.MNLIST
        PeerGroup.SyncStage.PREBLOCKS -> SyncStage.PREBLOCKS
        PeerGroup.SyncStage.BLOCKS -> SyncStage.BLOCKS
        PeerGroup.SyncStage.COMPLETE -> SyncStage.COMPLETE
    }

    fun resetBlockchainState() {
        coroutineScope.launch {
            blockchainStateDao.saveState(
                BlockchainState(true)
            )
        }
    }

    fun resetBlockchainSyncProgress() {
        coroutineScope.launch {
            val blockchainState: BlockchainState? = try {
                blockchainStateDao.getState()
            } catch (ex: SQLiteException) {
                null
            }
            if (blockchainState != null) {
                blockchainState.percentageSync = 0
                blockchainStateDao.saveState(blockchainState)
            }
        }
    }

    override suspend fun getLastMasternodeAPY(): Double {
        val apy = configuration.prefsKeyCrowdNodeStakingApy.toDouble()
        return if (apy != 0.0) {
            apy
        } else {
            withContext(Dispatchers.Default) {
                getEstimatedMasternodeAPY()
            }
        }
    }

    fun setNetworkStatus(networkStatus: NetworkStatus) {
        coroutineScope.launch {
            networkStatusFlow.emit(networkStatus)
        }
    }

    override fun getNetworkStatus(): NetworkStatus {
        return networkStatusFlow.value
    }

    override fun observeNetworkStatus(): Flow<NetworkStatus> {
        return networkStatusFlow
    }

    override fun observeSyncStage(): Flow<SyncStage?> {
        return syncStageFlow
    }

    override fun getSyncStage(): SyncStage {
        return syncStageFlow.value ?: SyncStage.OFFLINE
    }

    override suspend fun getMasternodeAPY(): Double {
        return withContext(Dispatchers.Default) {
            val masternodeListManager = dashSystemService.system.masternodeListManager
            val blockChain = dashSystemService.system.blockChain
            if (masternodeListManager != null && blockChain != null) {
                val mnlist = masternodeListManager.listAtChainTip
                if (mnlist.height != 0L) {
                    var prevBlock = try {
                        blockChain.blockStore.get(mnlist.height.toInt() - 1)
                    } catch (e: BlockStoreException) {
                        null
                    }
                    // if we cannot retrieve the previous block, use the chain tip
                    if (prevBlock == null) {
                        prevBlock = blockChain.chainHead
                    }

                    val validMNsCount = if (mnlist.size() != 0) {
                        var virtualMNCount = 0
                        mnlist.forEachMN(true) { entry ->
                            virtualMNCount += if (entry.isHPMN) 4 else 1
                        }
                        virtualMNCount
                    } else {
                        MASTERNODE_COUNT
                    }

                    if (prevBlock != null) {
                        val apy = getMasternodeAPY(
                            walletDataProvider.wallet!!.params,
                            mnlist.height.toInt(),
                            prevBlock.header.difficultyTarget,
                            validMNsCount
                        )
                        configuration.prefsKeyCrowdNodeStakingApy = apy.toFloat()
                        return@withContext apy
                    }
                }
            }
            return@withContext configuration.prefsKeyCrowdNodeStakingApy.toDouble()
        }
    }

    private val periods = arrayListOf(
        513,  // Period 1:  51.3%
        526,  // Period 2:  52.6%
        533,  // Period 3:  53.3%
        540,  // Period 4:  54%
        546,  // Period 5:  54.6%
        552,  // Period 6:  55.2%
        557,  // Period 7:  55.7%
        562,  // Period 8:  56.2%
        567,  // Period 9:  56.7%
        572,  // Period 10: 57.2%
        577,  // Period 11: 57.7%
        582,  // Period 12: 58.2%
        585,  // Period 13: 58.5%
        588,  // Period 14: 58.8%
        591,  // Period 15: 59.1%
        594,  // Period 16: 59.4%
        597,  // Period 17: 59.7%
        599,  // Period 18: 59.9%
        600 // Period 19: 60%
    )

    // this could very well be a part of DashJ
    // the masternode APY will
    // * decrease by about 7% per year
    // * increase every three months according to the periods list above
    private fun getMasternodePayment(
        params: NetworkParameters,
        height: Int,
        blockValue: Coin
    ): Coin {
        var ret = blockValue.div(5) // start at 20%

        val increaseBlockHeight: Int
        val period: Int
        val brrHeight: Int
        when (params.id) {
            NetworkParameters.ID_MAINNET -> {
                increaseBlockHeight = 158000
                period = 576 * 30
                brrHeight = 1374912
            }
            NetworkParameters.ID_TESTNET -> {
                increaseBlockHeight = 4030
                period = 10
                brrHeight = 387500
            }
            else -> {
                // devnets
                increaseBlockHeight = 4030
                period = 10
                brrHeight = 300
            }
        }
        // mainnet:
        if (height > increaseBlockHeight) ret =
            ret.add(blockValue.div(20)) // 158000 - 25.0% - 2014-10-24
        if (height > increaseBlockHeight + period) ret =
            ret.add(blockValue.div(20)) // 175280 - 30.0% - 2014-11-25
        if (height > increaseBlockHeight + period * 2) ret =
            ret.add(blockValue.div(20)) // 192560 - 35.0% - 2014-12-26
        if (height > increaseBlockHeight + period * 3) ret =
            ret.add(blockValue.div(40)) // 209840 - 37.5% - 2015-01-26
        if (height > increaseBlockHeight + period * 4) ret =
            ret.add(blockValue.div(40)) // 227120 - 40.0% - 2015-02-27
        if (height > increaseBlockHeight + period * 5) ret =
            ret.add(blockValue.div(40)) // 244400 - 42.5% - 2015-03-30
        if (height > increaseBlockHeight + period * 6) ret =
            ret.add(blockValue.div(40)) // 261680 - 45.0% - 2015-05-01
        if (height > increaseBlockHeight + period * 7) ret =
            ret.add(blockValue.div(40)) // 278960 - 47.5% - 2015-06-01
        if (height > increaseBlockHeight + period * 9) ret =
            ret.add(blockValue.div(40)) // 313520 - 50.0% - 2015-08-03
        if (height < brrHeight) {
            // Block Reward Realocation is not activated yet, nothing to do
            return ret
        }
        val superblockCycle = params.superblockCycle
        // Actual reallocation starts in the cycle next to one activation happens in
        val reallocStart =
            brrHeight - brrHeight % superblockCycle + superblockCycle
        if (height < reallocStart) {
            // Activated but we have to wait for the next cycle to start realocation, nothing to do
            return ret
        }

        if (Constants.NETWORK_PARAMETERS.isV20Active(height)) {
            // Once MNRewardReallocated activates, block reward is 80% of block subsidy (+ tx fees) since treasury is 20%
            // Since the MN reward needs to be equal to 60% of the block subsidy (according to the proposal), MN reward is set to 75% of the block reward.
            // Previous reallocation periods are dropped.
            return blockValue * 3 / 4
        }

        val reallocCycle = superblockCycle * 3
        val nCurrentPeriod: Int =
            min((height - reallocStart) / reallocCycle, periods.size - 1)
        return blockValue.multiply(periods[nCurrentPeriod].toLong()).div(1000)
    }

    /**
     * Get estimated masternode APY.  This uses the checkpoints file to estimate the current
     * height of the blockchain.
     */
    private fun getEstimatedMasternodeAPY(): Double {
        val masternodeCount = when (walletDataProvider.networkParameters.id) {
            NetworkParameters.ID_MAINNET -> MASTERNODE_COUNT
            NetworkParameters.ID_TESTNET -> 150
            else -> {
                walletDataProvider.networkParameters.defaultMasternodeList.size
            }
        }

        // get last checkpoint
        val currentTime = System.currentTimeMillis()/1000
        val lastCheckpoint = try {
            val checkpointsInputStream: InputStream =
                context.assets.open(Constants.Files.CHECKPOINTS_FILENAME)
            val checkpoints = CheckpointManager(Constants.NETWORK_PARAMETERS, checkpointsInputStream)
            val result = checkpoints.getCheckpointBefore(currentTime)
            checkpointsInputStream.close()
            result
        } catch (x: IOException) {
            // if there are no checkpoints, then use the genesis block
            StoredBlock(walletDataProvider.networkParameters.genesisBlock, BigInteger.valueOf(0), 0)
        }

        // Estimate current block height
        val timeElapsedSinceCheckpoint = currentTime - lastCheckpoint.header.time.time/1000
        val estimatedBlockHeight = (timeElapsedSinceCheckpoint / BLOCK_TARGET_SPACING).toInt() + lastCheckpoint.height

        val apy = getMasternodeAPY(
            walletDataProvider.networkParameters,
            estimatedBlockHeight,
            lastCheckpoint.header.difficultyTarget,
            masternodeCount)
        configuration.prefsKeyCrowdNodeStakingApy = apy.toFloat()
        return apy
    }

    private fun getMasternodeAPY(
        params: NetworkParameters,
        height: Int,
        prevDifficultyTarget: Long,
        masternodeCount: Int
    ): Double {
        val blockReward: Coin = Block.getBlockInflation(
            params,
            height,
            prevDifficultyTarget,
            false
        )
        val masternodeReward: Coin = getMasternodePayment(
            params,
            height,
            blockReward
        )

        val blocksPerYear = (DAYS_PER_YEAR * SECONDS_PER_DAY / BLOCK_TARGET_SPACING)
        val payoutsPerYear = blocksPerYear / masternodeCount

        return 100.0 * masternodeReward.value * payoutsPerYear / MASTERNODE_COST.value
    }
}
