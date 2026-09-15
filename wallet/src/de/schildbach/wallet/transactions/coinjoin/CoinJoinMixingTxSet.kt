package de.schildbach.wallet.transactions.coinjoin

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Transaction
import org.bitcoinj.wallet.WalletEx
import org.slf4j.LoggerFactory
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

        // MO-995: the classification below is dashj's SHAPE HEURISTIC
        // (CoinJoinTransactionType.fromTx), evaluated against the DASHJ wallet.
        // Post-cutover that wallet is held with its balance frozen at the
        // cutover snapshot, while the transaction itself originates from the
        // SDK — so a stale wallet is being asked about a new transaction, and
        // fromTx depends on wallet context (which inputs are mine, input
        // values, denomination/collateral matching). QA saw an ordinary
        // 0.0002 DASH send labelled "Mixing" on BOTH the sending and receiving
        // wallet, with a coinjoin balance of 0 for the whole session — i.e. no
        // mixing had occurred. 0.0002 DASH is not a CoinJoin denomination, so
        // amount-matching is not the explanation; wallet context is the
        // suspect. This log line records what fromTx actually returned so the
        // next field report settles it.
        val raw = tx.raw
        if (raw !is Transaction) {
            // `TxInfo.raw` is `Any?`, and the old code did `raw as Transaction`
            // unconditionally — a ClassCastException on null or on any
            // non-dashj payload. Post-cutover TxInfo can be built from the SDK,
            // so treat a non-dashj payload as "not CoinJoin" rather than
            // throwing out of the transaction list.
            log.info(
                "coinjoin grouping skipped for {}: TxInfo.raw is {}, not a dashj Transaction",
                tx.txId, raw?.javaClass?.simpleName ?: "null"
            )
            return false
        }

        val type = CoinJoinTransactionType.fromTx(raw, wallet)

        if (type == CoinJoinTransactionType.None || type == CoinJoinTransactionType.Send) {
            return false
        }

        // Only logged when a tx is about to be GROUPED as CoinJoin, so the
        // volume is bounded by actual (mis)classifications, not by list size.
        log.info(
            "coinjoin grouping {} as {} (inputs={} outputs={} dashjCoinJoinBalance={})",
            tx.txId, type, raw.inputs.size, raw.outputs.size,
            runCatching { wallet.coinJoinBalance }.getOrNull()
        )

        val txDate = tx.groupDate

        if (transactions.isEmpty()) {
            groupDate = txDate
        } else if (!groupDate.isEqual(txDate)) {
            return false
        }

        transactions[tx.txId] = tx

        return true
    }

    private companion object {
        private val log = LoggerFactory.getLogger(CoinJoinMixingTxSet::class.java)
    }

    override fun getValue(): Dash {
        var result = 0L

        for (pair in transactions) {
            result += pair.value.netValueDuffs
        }

        return Dash(result)
    }
}
