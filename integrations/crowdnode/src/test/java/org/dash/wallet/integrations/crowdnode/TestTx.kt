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

package org.dash.wallet.integrations.crowdnode

import org.dash.wallet.common.payments.parsers.AddressUtils
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.TxInputInfo
import org.dash.wallet.common.transactions.TxOutputInfo
import java.security.MessageDigest

/**
 * Minimal raw-transaction fixture replacing the dashj `Transaction` objects these tests used to
 * build from hex. It parses the very same serialized transactions and exposes them as neutral
 * [TxInfo] snapshots, mirroring the dashj semantics the tests relied on:
 *
 * - [connect] mirrors `TransactionInput.connect(TransactionOutput)`: the connected output is
 *   resolved through the input's outpoint index within the connected transaction (exactly like
 *   `TransactionOutPoint.getConnectedOutput()`).
 * - [markAsSpent] mirrors `TransactionOutput.markAsSpent(spendingTx.inputs[i])`.
 * - [toTxInfo] mirrors the `TransactionBag` mock the tests used (`isPubKeyHashMine` returning
 *   [toTxInfo]'s `mine` argument for every script):
 *   - `output.isMine(bag)` == `mine`
 *   - `isEntirelySelf(bag)` == `mine` && has inputs && every input connected
 *   - `getValue(bag)` == sum of outputs minus sum of connected inputs when `mine`, else 0
 *   - `getFee()` == input sum - output sum when every input is connected, else null
 */
class TestTx(hex: String) {
    class TestOutput(val valueDuffs: Long, val address: String?, val index: Int) {
        var spentBy: TestTx? = null
    }

    class TestInput(val outpointIndex: Int) {
        var connectedTx: TestTx? = null
        val connectedOutput: TestOutput?
            get() = connectedTx?.outputs?.get(outpointIndex)
    }

    val txId: String
    val inputs = mutableListOf<TestInput>()
    val outputs = mutableListOf<TestOutput>()

    init {
        val bytes = hexToBytes(hex)
        txId = doubleSha256ReversedHex(bytes)

        var pos = 4 // version

        val inputCount = readVarInt(bytes, pos).also { pos = it.second }.first
        repeat(inputCount.toInt()) {
            pos += 32 // outpoint tx hash
            val outpointIndex = readUint32(bytes, pos)
            pos += 4
            val scriptLen = readVarInt(bytes, pos).also { pos = it.second }.first
            pos += scriptLen.toInt()
            pos += 4 // sequence
            inputs.add(TestInput(outpointIndex.toInt()))
        }

        val outputCount = readVarInt(bytes, pos).also { pos = it.second }.first
        repeat(outputCount.toInt()) { index ->
            val value = readInt64(bytes, pos)
            pos += 8
            val scriptLen = readVarInt(bytes, pos).also { pos = it.second }.first.toInt()
            val script = bytes.copyOfRange(pos, pos + scriptLen)
            pos += scriptLen
            outputs.add(TestOutput(value, scriptToTestnetAddress(script), index))
        }
    }

    /** Mirrors `tx.inputs[inputIndex].connect(other.outputs[n])` — the outpoint index selects the output. */
    fun connect(inputIndex: Int, tx: TestTx) {
        inputs[inputIndex].connectedTx = tx
    }

    /** Mirrors `tx.outputs[outputIndex].markAsSpent(spendingTx.inputs[i])`. */
    fun markAsSpent(outputIndex: Int, spendingTx: TestTx) {
        outputs[outputIndex].spentBy = spendingTx
    }

    fun toTxInfo(mine: Boolean = true): TxInfo {
        val allInputsConnected = inputs.isNotEmpty() && inputs.all { it.connectedOutput != null }
        val connectedInputSum = inputs.sumOf { it.connectedOutput?.valueDuffs ?: 0L }
        val outputSum = outputs.sumOf { it.valueDuffs }

        return TxInfo(
            txId = txId,
            updateTimeMillis = System.currentTimeMillis(),
            netValueDuffs = { if (mine) outputSum - connectedInputSum else 0L },
            feeDuffs = { if (allInputsConnected) connectedInputSum - outputSum else null },
            isEntirelySelf = { mine && allInputsConnected },
            inputs = {
                inputs.map {
                    val connected = it.connectedOutput
                    TxInputInfo(
                        connectedAddress = connected?.address,
                        connectedIsMine = if (connected != null) mine else null
                    )
                }
            },
            outputs = {
                outputs.map {
                    TxOutputInfo(
                        valueDuffs = it.valueDuffs,
                        address = it.address,
                        isOpReturn = false,
                        isMine = mine,
                        index = it.index,
                        spentBy = it.spentBy?.toTxInfo(mine)
                    )
                }
            }
        )
    }

    companion object {
        private const val TESTNET_P2PKH_HEADER = 140
        private const val TESTNET_P2SH_HEADER = 19

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        private fun doubleSha256ReversedHex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(digest.digest(bytes))
            hash.reverse()
            return hash.joinToString("") { "%02x".format(it) }
        }

        private fun readUint32(bytes: ByteArray, pos: Int): Long {
            return (bytes[pos].toLong() and 0xff) or
                ((bytes[pos + 1].toLong() and 0xff) shl 8) or
                ((bytes[pos + 2].toLong() and 0xff) shl 16) or
                ((bytes[pos + 3].toLong() and 0xff) shl 24)
        }

        private fun readInt64(bytes: ByteArray, pos: Int): Long {
            var result = 0L
            for (i in 0..7) {
                result = result or ((bytes[pos + i].toLong() and 0xff) shl (8 * i))
            }
            return result
        }

        /** Returns Pair(value, newPos). */
        private fun readVarInt(bytes: ByteArray, pos: Int): Pair<Long, Int> {
            val first = bytes[pos].toInt() and 0xff
            return when {
                first < 0xfd -> Pair(first.toLong(), pos + 1)
                first == 0xfd -> Pair(
                    (bytes[pos + 1].toLong() and 0xff) or ((bytes[pos + 2].toLong() and 0xff) shl 8),
                    pos + 3
                )
                first == 0xfe -> Pair(readUint32(bytes, pos + 1), pos + 5)
                else -> throw IllegalArgumentException("8-byte varints not supported in test fixtures")
            }
        }

        /** Base58 destination of a standard P2PKH/P2SH script on testnet, null for non-standard scripts. */
        private fun scriptToTestnetAddress(script: ByteArray): String? {
            // P2PKH: OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
            if (script.size == 25 &&
                (script[0].toInt() and 0xff) == 0x76 &&
                (script[1].toInt() and 0xff) == 0xa9 &&
                (script[2].toInt() and 0xff) == 0x14 &&
                (script[23].toInt() and 0xff) == 0x88 &&
                (script[24].toInt() and 0xff) == 0xac
            ) {
                return AddressUtils.encode(TESTNET_P2PKH_HEADER, script.copyOfRange(3, 23))
            }

            // P2SH: OP_HASH160 <20> OP_EQUAL
            if (script.size == 23 &&
                (script[0].toInt() and 0xff) == 0xa9 &&
                (script[1].toInt() and 0xff) == 0x14 &&
                (script[22].toInt() and 0xff) == 0x87
            ) {
                return AddressUtils.encode(TESTNET_P2SH_HEADER, script.copyOfRange(2, 22))
            }

            return null
        }
    }
}
