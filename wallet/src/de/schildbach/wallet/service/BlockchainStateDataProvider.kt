package de.schildbach.wallet.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.dao.BlockchainStateDao
import kotlinx.coroutines.flow.Flow
import org.bitcoinj.core.Block
import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.store.BlockStoreException
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import javax.inject.Inject
import kotlin.math.min

/**
 * Blockchain state data provider
 *
 * @property blockchainStateDao
 * @property walletDataProvider is used to determine the network parameters and DashJ Context
 * @property configuration is used to save some information
 *
 */

class BlockchainStateDataProvider @Inject constructor(
    @ApplicationContext
    private val context: Context,
    private val blockchainStateDao: BlockchainStateDao,
    private val walletDataProvider: WalletDataProvider,
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

    override suspend fun getState(): BlockchainState? {
        return blockchainStateDao.loadSync()
    }

    override fun observeState(): Flow<BlockchainState?> {
        return blockchainStateDao.observeState()
    }

    override fun getLastMasternodeAPY(): Double {
        val apy = configuration.prefsKeyCrowdNodeStakingApy.toDouble()
        return if (apy != 0.0) {
            apy
        } else {
            getEstimatedMasternodeAPY()
        }
    }

    override fun getMasternodeAPY(): Double {
        val masternodeListManager = walletDataProvider.wallet?.context?.masternodeListManager
        val blockChain = walletDataProvider.wallet?.context?.blockChain
        if (masternodeListManager != null && blockChain != null) {
            val mnlist = masternodeListManager.listAtChainTip
            if (mnlist.height != 0L) {
                var prevBlock = try {
                    mnlist.storedBlock.getPrev(blockChain.blockStore)
                } catch (e: BlockStoreException) {
                    null
                }
                // if we cannot retrieve the previous block, use the mnlist tip
                if (prevBlock == null) {
                    prevBlock = mnlist.storedBlock
                }

                val validMNsCount = if (mnlist.size() != 0) {
                    mnlist.validMNsCount
                } else {
                    MASTERNODE_COUNT
                }

                if (prevBlock != null) {
                    val apy = getMasternodeAPY(
                        walletDataProvider.wallet!!.params,
                        mnlist.storedBlock.height,
                        prevBlock.header.difficultyTarget,
                        validMNsCount
                    )
                    configuration.prefsKeyCrowdNodeStakingApy = apy.toFloat()
                    return apy
                }
            }
        }
        return configuration.prefsKeyCrowdNodeStakingApy.toDouble()
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