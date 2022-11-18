package de.schildbach.wallet.util

import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.DefaultRiskAnalysis
import org.bitcoinj.wallet.RiskAnalysis
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.max

/**
 * This risk analyzer is PeerGroup aware and will use peers and some events to gather information
 * about the current network blockchain height and the time.
 *
 * This Risk Analyzer will consider Version Two transactions with locktime to be considered Final
 * if:
 * 1. the locktime value (block height) is less than or equal to the most common block height
 *    among connected peers + 1.
 * 2. the locktime value (time) is less than or equal to the median network time
 *
 * With these changes, it will be as if the wallet was fully synced, though the blockchain
 * sync is not yet complete.
 *
 * The result for the user is that Version 2 transactions sent from other Dash wallets (such as
 * Dash Core) will appear immediately in the transaction list, even if the wallet is still syncing.
 * Previously these transactions would not appear (if the wallet was still syncing) until the v2
 * transaction appeared in a mined block.  For fully synced wallets, no difference will be noticed.
 *
 * Version 2 transactions that have relative time locks still are considered risky and non-final.
 */

class AllowLockTimeRiskAnalysis(
    wallet: Wallet,
    private val tx: Transaction,
    private val dependencies: List<Transaction>,
    private val commonHeight: Int,
    private val commonTime: Long
) :
    RiskAnalysis {

    companion object {
        val log = LoggerFactory.getLogger(AllowLockTimeRiskAnalysis::class.java)
    }

    private val defaultRiskAnalysis: DefaultRiskAnalysis =
        DefaultRiskAnalysis.FACTORY.create(wallet, tx, dependencies)

    /**
     * This taken from [DefaultRiskAnalysis.analyzeIsFinal]
     */
    private fun isTransactionFinal(height: Int, time: Long): Boolean {
        val adjustedHeight: Int = height + 1

        if (!tx.isFinal(adjustedHeight, time)) {
            return false
        }
        for (dep in dependencies) {
            if (!dep.isFinal(adjustedHeight, time)) {
                return false
            }
        }
        return true
    }

    /**
     * Uses [DefaultRiskAnalysis.analyze] to get an initial result.  If the tx is non-final, then
     * determine if it should be final using the common block height and time of the network
     */
    override fun analyze(): RiskAnalysis.Result {
        val result = defaultRiskAnalysis.analyze()
        if (result == RiskAnalysis.Result.NON_FINAL) {
            // check to if this is a V2 TX with a lock time, but not a relative lock time
            if (!tx.hasRelativeLockTime() && tx.isTimeLocked) {
                // We need to check the height vs peers in case the blockchain
                // has not finished syncing.  Instead of using the wallet height and time like
                // DefaultRiskAnalysis, we will use the supplied common height and time
                // if commonHeight are not initialized by the network, which can be the case if
                // the app is restarted, then we will use the number of nodes that have announced.
                // We cannot use the IS locks because if the blockchain is not synced, then IS locks
                // are not processed or verified

                if (isTransactionFinal(commonHeight, commonTime)) {
                    return RiskAnalysis.Result.OK
                } else if (tx.confidence.numBroadcastPeers() > 1) {
                    return RiskAnalysis.Result.OK
                }
            }
        }
        return result
    }

    // This is intended for use before the app connects to the network
    class OfflineAnalyzer(
        private val maxChainHeight: Int,
        private val maxCurrentTime: Long
    ) : RiskAnalysis.Analyzer {

        override fun create(
            wallet: Wallet,
            tx: Transaction,
            dependencies: List<Transaction>
        ): AllowLockTimeRiskAnalysis {
            return AllowLockTimeRiskAnalysis(
                wallet,
                tx,
                dependencies,
                maxChainHeight,
                maxCurrentTime
            )
        }
    }

    // This is intended for use after the app connects to the network
    class Analyzer(private val peerGroup: PeerGroup) : RiskAnalysis.Analyzer {
        companion object {
            private val log = LoggerFactory.getLogger(Analyzer::class.java)
            private fun currentTime(): Long {
                return System.currentTimeMillis() / 1000
            }

            private fun med(list: List<Long>) = list.sorted().let {
                if (it.size % 2 == 0)
                    (it[it.size / 2] + it[(it.size - 1) / 2]) / 2
                else
                    it[it.size / 2]
            }
        }

        private var chainHeight = -1

        // A common time difference is not part of dashj PeerGroup,so we will implement it here
        //
        // In Dash Core, the last connected peers (200 max) are used to determine a median network
        // time difference.  A time difference is calculated for each peer, then the median is used
        // to determine the network time when a transaction needs to be analyzed.
        private val networkTimeDifferences = LinkedList<Long>()

        private val networkTimeDiffMedian: Long
            get() = med(networkTimeDifferences)

        private fun addNetworkTimeDiff(timeDiff: Long) {
            if (networkTimeDifferences.size == 24) {
                networkTimeDifferences.removeFirst()
            }
            networkTimeDifferences.addLast(timeDiff)
        }

        override fun create(
            wallet: Wallet,
            tx: Transaction,
            dependencies: List<Transaction>
        ): AllowLockTimeRiskAnalysis {
            return AllowLockTimeRiskAnalysis(
                wallet,
                tx,
                dependencies,
                chainHeight,
                currentTime() + networkTimeDiffMedian
            )
        }

        // when connecting to a new node determine the most common chain height and the time diff
        private val peerConnectedEventListener = PeerConnectedEventListener { peer, _ ->
            val networkTimeDifference = peer.peerVersionMessage.time - currentTime()
            addNetworkTimeDiff(networkTimeDifference)
            log.info("risk analysis: net time diff ${networkTimeDiffMedian}; peer time diff $networkTimeDifference ${peer.peerVersionMessage.time} ${currentTime()}")
            updateHeight()
        }

        // as the blockchain is synced, update the chainHeight if the blockchain gets longer
        private val blocksDownloadedEventListener = BlocksDownloadedEventListener { _, _, _, _ ->
            updateHeight()
        }

        init {
            // add listeners
            peerGroup.addConnectedEventListener(Threading.SAME_THREAD, peerConnectedEventListener)
            peerGroup.addBlocksDownloadedEventListener(
                Threading.SAME_THREAD,
                blocksDownloadedEventListener
            )
        }

        // call this when the peerGroup is shutdown. remove listeners
        fun shutdown() {
            peerGroup.removeConnectedEventListener(peerConnectedEventListener)
            peerGroup.removeBlocksDownloadedEventListener(blocksDownloadedEventListener)
        }

        private fun updateHeight() {
            chainHeight = max(chainHeight, peerGroup.mostCommonChainHeight)
        }
    }
}