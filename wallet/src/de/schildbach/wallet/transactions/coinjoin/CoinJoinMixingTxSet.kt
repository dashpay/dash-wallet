package de.schildbach.wallet.transactions.coinjoin

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.*
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.transactions.TransactionWrapper
import java.time.LocalDate
import java.time.ZoneId

open class CoinJoinMixingTxSet(
    private val wallet: WalletEx
) : TransactionWrapper {
    override val id: String
        get() = "coinjoin_$groupDate"
    override val transactions: HashMap<Sha256Hash, Transaction> = hashMapOf()
    final override var groupDate: LocalDate = LocalDate.now()
        private set

    override fun tryInclude(tx: Transaction): Boolean {
        if (transactions.containsKey(tx.txId)) {
            transactions[tx.txId] = tx
            return true
        }

        val type = CoinJoinTransactionType.fromTx(tx, wallet)

        if (type == CoinJoinTransactionType.None || type == CoinJoinTransactionType.Send) {
            return false
        }

        val txDate = tx.updateTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()

        if (transactions.isEmpty()) {
            groupDate = txDate
        } else if (!groupDate.isEqual(txDate)) {
            return false
        }

        transactions[tx.txId] = tx

        return true
    }

    override fun getValue(bag: TransactionBag): Coin {
        var result = Coin.ZERO

        for (pair in transactions) {
            val value = pair.value.getValue(bag)
            result = result.add(value)
        }

        return result
    }
}
