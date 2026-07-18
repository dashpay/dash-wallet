/*
 * Copyright 2026 Dash Core Group.
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

import de.schildbach.wallet.transactions.TransactionUtils.isEntirelySelf
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptException
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.TxInputInfo
import org.dash.wallet.common.transactions.TxOutputInfo

/**
 * Converts dashj [Transaction]s into the neutral [TxInfo] snapshots that cross module
 * boundaries. Field semantics deliberately mirror the previous dashj-typed matcher logic:
 * - `address` fields are only set for standard P2PKH/P2SH scripts (like the old
 *   `ScriptPattern.isP2PKH(s) || isP2SH(s)` guards),
 * - `isLocked` uses the exact `LockedTransaction` condition,
 * - `spentBy` is populated one level deep (enough for the CrowdNode top-up discovery).
 */
object TxInfoConverter {

    fun toTxInfo(tx: Transaction, bag: TransactionBag, params: NetworkParameters, depth: Int = 1): TxInfo {
        val confidence = tx.confidence
        val confidenceType = confidence?.confidenceType
        val isLocked = confidence != null && (
            confidence.isTransactionLocked ||
                confidenceType == TransactionConfidence.ConfidenceType.BUILDING ||
                (
                    confidenceType == TransactionConfidence.ConfidenceType.PENDING &&
                        confidence.numBroadcastPeers() > 1
                    )
            )

        return TxInfo(
            txId = tx.txId.toString(),
            updateTimeMillis = tx.updateTime?.time ?: 0L,
            isLocked = isLocked,
            isPending = confidenceType == TransactionConfidence.ConfidenceType.PENDING,
            netValueDuffs = { tx.getValue(bag).value },
            feeDuffs = { tx.fee?.value },
            isEntirelySelf = { tx.isEntirelySelf(bag) },
            inputs = {
                tx.inputs.map { input ->
                    val connectedOutput = input.outpoint.connectedOutput
                    TxInputInfo(
                        connectedAddress = connectedOutput?.let { standardAddressOf(it.scriptPubKey, params) },
                        connectedIsMine = connectedOutput?.let { isMineSafe(it, bag) }
                    )
                }
            },
            outputs = {
                tx.outputs.mapIndexed { index, output ->
                    val script = try {
                        output.scriptPubKey
                    } catch (x: ScriptException) {
                        null
                    }
                    TxOutputInfo(
                        valueDuffs = output.value.value,
                        address = script?.let { standardAddressOf(it, params) },
                        isOpReturn = script != null && ScriptPattern.isOpReturn(script),
                        isMine = isMineSafe(output, bag),
                        index = index,
                        spentBy = if (depth > 0) {
                            output.spentBy?.parentTransaction?.let { toTxInfo(it, bag, params, depth - 1) }
                        } else {
                            null
                        }
                    )
                }
            },
            rawHex = { tx.toStringHex() },
            raw = tx
        )
    }

    private fun standardAddressOf(script: Script, params: NetworkParameters): String? {
        return try {
            if (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) {
                script.getToAddress(params).toBase58()
            } else {
                null
            }
        } catch (x: ScriptException) {
            null
        }
    }

    private fun isMineSafe(output: org.bitcoinj.core.TransactionOutput, bag: TransactionBag): Boolean {
        return try {
            output.isMine(bag)
        } catch (x: ScriptException) {
            false
        }
    }
}

/** Converts a dashj [Transaction] to a neutral [TxInfo] snapshot. */
fun Transaction.toTxInfo(bag: TransactionBag, params: NetworkParameters): TxInfo =
    TxInfoConverter.toTxInfo(this, bag, params)

/** The underlying dashj transaction of a neutral [TxInfo] created by the wallet module. */
val TxInfo.dashjTx: Transaction
    get() = raw as Transaction

/** The underlying dashj transactions of a neutral wrapper's snapshots. */
val TransactionWrapper.dashjTransactions: List<Transaction>
    get() = transactions.values.map { it.dashjTx }
