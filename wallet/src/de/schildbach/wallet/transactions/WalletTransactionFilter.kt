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

package de.schildbach.wallet.transactions

import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.filters.TransactionFilter

/**
 * Dashj-typed transaction filter for wallet-module internals (the former
 * `org.dash.wallet.common.transactions.filters.TransactionFilter`, which is now neutral).
 */
interface WalletTransactionFilter {
    fun matches(tx: Transaction): Boolean
}

/**
 * Adapts a neutral [TransactionFilter] to the dashj-typed [WalletTransactionFilter] by converting
 * the dashj transaction through [TxInfoConverter] and delegating. Used to push neutral filters
 * back down into `WalletObserver`, where they gate both emission and confidence-listener
 * registration — exactly like the old dashj-typed filters did.
 */
class NeutralFilterAdapter(
    private val filter: TransactionFilter,
    private val bag: TransactionBag,
    private val params: NetworkParameters
) : WalletTransactionFilter {
    override fun matches(tx: Transaction): Boolean = filter.matches(tx.toTxInfo(bag, params))
}

/** Dashj-typed twin of the neutral `LockedTransaction` filter (identical logic). */
class LockedTransaction(private val topUpTxId: Sha256Hash? = null) : WalletTransactionFilter {
    constructor() : this(null)

    override fun matches(tx: Transaction): Boolean {
        val confidence = tx.confidence
        val type = confidence.confidenceType
        val isLocked = confidence.isTransactionLocked ||
            type == TransactionConfidence.ConfidenceType.BUILDING ||
            (type == TransactionConfidence.ConfidenceType.PENDING && confidence.numBroadcastPeers() > 1)

        return if (topUpTxId != null) {
            tx.txId == topUpTxId && isLocked
        } else {
            isLocked
        }
    }
}

/** Dashj-typed twin of the neutral `NotFromAddressTxFilter` (identical logic). */
class NotFromAddressTxFilter(private val ignoreAddress: Address) : WalletTransactionFilter {
    override fun matches(tx: Transaction): Boolean {
        val networkParameters = ignoreAddress.parameters

        for (input in tx.inputs) {
            input.outpoint.connectedOutput?.let { connectedOutput ->
                val script = connectedOutput.scriptPubKey

                if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                    script.getToAddress(networkParameters) == ignoreAddress
                ) {
                    return false
                }
            }
        }

        for (output in tx.outputs) {
            val script = output.scriptPubKey

            if ((ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                script.getToAddress(networkParameters) == ignoreAddress
            ) {
                return false
            }
        }

        return true
    }
}
