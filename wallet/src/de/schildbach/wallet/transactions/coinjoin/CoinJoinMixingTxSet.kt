package de.schildbach.wallet.transactions.coinjoin

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.*
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.TransactionComparator
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.filters.TransactionFilter
import org.slf4j.LoggerFactory

open class CoinJoinMixingTxSet(
    private val networkParams: NetworkParameters,
    private val wallet: WalletEx
) : TransactionWrapper {
    private val log = LoggerFactory.getLogger(CoinJoinMixingTxSet::class.java)
    private var isFinished = false

    private val coinjoinTxFilters = mutableListOf(
        CreateDenominationTxFilter(wallet),
        MakeCollateralTxFilter(wallet),
        MixingFeeTxFilter(wallet),
        MixingTxFilter(wallet)
    )

    private val matchedFilters = mutableListOf<TransactionFilter>()
    override val transactions = sortedSetOf(TransactionComparator())

    override fun tryInclude(tx: Transaction): Boolean {
        if (isFinished || transactions.any { it.txId == tx.txId }) {
            return false
        }

//        if (tx.isEntirelySelf(bag)) {
//            // We might not have our CrowdNode account address by the time the topUp
//            // transaction is found, which means we need to check its `spentBy`
//            for (output in tx.outputs) {
//                output.spentBy?.let {
//                    if (signUpFilter.matches(it.parentTransaction)) {
//                        val accountAddress = signUpFilter.fromAddresses.first()
//                        coinjoinTxFilters.add(CrowdNodeTopUpTx(accountAddress, bag))
//                    }
//                }
//            }
//        }

        val matchedFilter = coinjoinTxFilters.firstOrNull { it.matches(tx) }

        if (matchedFilter != null) {
            // log.info("wrapper: CoinJoinMixingTxSet: {}, {}, {}", CoinJoinTransactionType.fromTx(tx, wallet), tx.txId, transactions.size)
            transactions.add(tx)
            matchedFilters.add(matchedFilter)
            return true
        }

        return false
    }

    override fun getValue(bag: TransactionBag): Coin {
        var result = Coin.ZERO

        for (tx in transactions) {
            val value = tx.getValue(bag)
            result = result.add(value)
        }

        return result
    }

//    override fun shouldSplit(tx: Transaction): Boolean {
//        val matchedFilter = coinjoinTxFilters.firstOrNull { it.matches(tx) }
//
//        if (matchedFilter != null && transactions.isNotEmpty()) {
//            val first = transactions.first()
//            if (first != null) {
//                return (tx.updateTime.time - first.updateTime.time) > (1000 * 60 * 60) // 1 hours
//            }
//        }
//        return false
//    }
//
//    override fun split(): TransactionWrapper {
//        isFinished = true
//        return CoinJoinMixingTxSet(networkParams, wallet)
//    }
//
//    override fun isFinished(): Boolean = isFinished
}
