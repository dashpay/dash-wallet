package org.dash.wallet.integrations.crowdnode.transactions

import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TransactionWrapperFactory
import org.dash.wallet.common.transactions.TxInfo

class FullCrowdNodeSignUpTxSetFactory(networkId: String) :
    TransactionWrapperFactory {
    private val wrapper = FullCrowdNodeSignUpTxSet(networkId)
    override val averageTransactions: Long = 5
    override val wrappers = listOf(wrapper)

    override fun tryInclude(tx: TxInfo): Pair<Boolean, TransactionWrapper?> {
        if (wrapper.isComplete) {
            return Pair(false, wrapper)
        }

        return Pair(wrapper.tryInclude(tx), wrapper)
    }
}
