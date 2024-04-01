package de.schildbach.wallet.transactions.coinjoin

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

        val matchedFilter = coinjoinTxFilters.firstOrNull { it.matches(tx) }

        if (matchedFilter != null) {
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
}
