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

package org.dash.wallet.common.transactions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import org.bitcoinj.core.Address
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.script.ScriptException
import java.util.concurrent.ConcurrentHashMap

object TransactionUtils {
    fun getWalletAddressOfReceived(tx: Transaction, bag: TransactionBag): Address? {
        for (output in tx.outputs) {
            try {
                if (output.isMine(bag)) {
                    return output.scriptPubKey.getToAddress(tx.params, true)
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }
        return null
    }

    fun getFromAddressOfSent(tx: Transaction): List<Address> {
        val result = mutableListOf<Address>()

        for (input in tx.inputs) {
            try {
                val connectedTransaction = input.connectedTransaction
                if (connectedTransaction != null) {
                    val output = connectedTransaction.getOutput(input.outpoint.index)
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    fun getToAddressOfReceived(tx: Transaction, bag: TransactionBag): List<Address> {
        val result = mutableListOf<Address>()

        for (output in tx.outputs) {
            try {
                if (output.isMine(bag)) {
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    fun getToAddressOfSent(tx: Transaction, bag: TransactionBag): List<Address> {
        val result = mutableListOf<Address>()

        for (output in tx.outputs) {
            try {
                if (!output.isMine(bag)) {
                    result.add(output.scriptPubKey.getToAddress(tx.params, true))
                }
            } catch (x: ScriptException) {
                // swallow
            }
        }

        return result
    }

    fun Transaction.isEntirelySelf(bag: TransactionBag): Boolean {
        for (input in inputs) {
            val connectedOutput = input.connectedOutput

            if (connectedOutput == null || !connectedOutput.isMine(bag)) {
                return false
            }
        }

        for (output in outputs) {
            if (!output.isMine(bag)) {
                return false
            }
        }

        return true
    }

    val Transaction.allOutputAddresses: List<Address>
        get() {
            val result = mutableListOf<Address>()

            outputs.forEach {
                try {
                    val script = it.scriptPubKey
                    result.add(script.getToAddress(this.params, true))
                } catch (x: ScriptException) {
                    // swallow
                }
            }
            return result
        }
}

fun Flow<Transaction>.batchAndFilterUpdates(timeInterval: Long = 500): Flow<List<Transaction>> {
    val latestTransactions = ConcurrentHashMap<Sha256Hash, Transaction>()

    return this
        .onEach { transaction ->
            // Update the latest transaction for the hash
            latestTransactions[transaction.txId] = transaction
        }
        .sample(timeInterval) // Emit events every 500ms
        .map {
            // Collect the latest transactions
            latestTransactions.values.toList().also {
                latestTransactions.clear() // Clear after collecting
            }
        }
        .filter { it.isNotEmpty() } // Only emit non-empty lists
}