package de.schildbach.wallet.transactions.coinjoin

import de.schildbach.wallet.transactions.dashjTx
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TxInfo
import java.time.LocalDate

open class CoinJoinMixingTxSet(
    private val wallet: WalletEx
) : TransactionWrapper {
    override val id: String
        get() = "coinjoin_$groupDate"
    override val transactions: HashMap<String, TxInfo> = hashMapOf()
    final override var groupDate: LocalDate = LocalDate.now()
        private set

    override fun tryInclude(tx: TxInfo): Boolean {
        if (transactions.containsKey(tx.txId)) {
            transactions[tx.txId] = tx
            return true
        }

        val type = CoinJoinTransactionType.fromTx(tx.dashjTx, wallet)

        if (type == CoinJoinTransactionType.None || type == CoinJoinTransactionType.Send) {
            return false
        }

        val txDate = tx.groupDate

        if (transactions.isEmpty()) {
            groupDate = txDate
        } else if (!groupDate.isEqual(txDate)) {
            return false
        }

        transactions[tx.txId] = tx

        return true
    }

    override fun getValue(): Dash {
        var result = 0L

        for (pair in transactions) {
            result += pair.value.netValueDuffs
        }

        return Dash(result)
    }
}
