/*
 * Copyright 2022 Dash Core Group.
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

import org.bitcoinj.core.Block
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkDataProvider
import javax.inject.Inject
import kotlin.math.min

class DashNetworkDataProvider @Inject constructor(val walletDataProvider: WalletDataProvider) :
    NetworkDataProvider {

    override fun getMasternodeAPY(): Double {
        val masternodeListManager = walletDataProvider.wallet?.context?.masternodeListManager
        val blockChain = walletDataProvider.wallet?.context?.blockChain
        if (masternodeListManager != null && blockChain != null) {
            val mnlist = masternodeListManager.listAtChainTip
            if (mnlist.height != 0L) {
                val prevBlock = mnlist.storedBlock.getPrev(blockChain.blockStore)
                return getMasternodeAPY(
                    walletDataProvider.wallet!!.params,
                    mnlist.storedBlock.height,
                    prevBlock.header.difficultyTarget,
                    mnlist.validMNsCount
                )
            }
        }
        return 0.00
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
        // Actual realocation starts in the cycle next to one activation happens in
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

    fun getMasternodeAPY(
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

        val blocksPerYear = (365.2425 * 24 * 60 * 60 / NetworkParameters.TARGET_SPACING)
        val payoutsPerYear = blocksPerYear / masternodeCount
        val masternodeCost = Coin.valueOf(1000,0 )

        return 100.0 * masternodeReward.value * payoutsPerYear / masternodeCost.value
    }
}