package org.dash.wallet.integrations.crowdnode.transactions

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory

class FullCrowdNodeSignUpTxSetFactory(params: NetworkParameters, transactionBag: TransactionBag) :
    TransactionWrapperFactory {
    private val wrapper = FullCrowdNodeSignUpTxSet(params, transactionBag)
    override val wrappers = listOf(wrapper)

    override fun tryInclude(tx: Transaction): Pair<Boolean, TransactionWrapper?> {
        return Pair(wrapper.tryInclude(tx), wrapper)
    }
}
