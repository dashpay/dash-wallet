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

package org.dash.wallet.common.transactions

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.transactions.TransactionUtils.isEntirelySelf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsEntirelySelfTest {
    private val networkParams = TestNet3Params.get()
    private val myAddress = Address.fromBase58(networkParams, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy")
    private val notMyAddress = Address.fromBase58(networkParams, "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9")

    /** Minimal bag: P2PKH outputs paying [myHashes] are ours; nothing else is. */
    private fun bagOf(vararg myHashes: ByteArray) = object : TransactionBag {
        override fun isPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) =
            myHashes.any { it.contentEquals(pubKeyHash) }
        override fun isWatchedScript(script: Script) = false
        override fun isPubKeyMine(pubKey: ByteArray) = false
        override fun isPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun isCoinJoinPubKeyHashMine(pubKeyHash: ByteArray, scriptType: Script.ScriptType?) = false
        override fun isCoinJoinPubKeyMine(pubKey: ByteArray) = false
        override fun isCoinJoinPayToScriptHashMine(payToScriptHash: ByteArray) = false
        override fun getTransactionPool(pool: WalletTransaction.Pool): Map<Sha256Hash, Transaction> = mapOf()
        override fun isFullyMixed(output: TransactionOutput) = false
        override fun isLockedOutput(outPoint: TransactionOutPoint) = false
        override fun lockOutput(outPoint: TransactionOutPoint) = false
    }

    @Test
    fun inputlessPayoutToOurAddress_isNotEntirelySelf() {
        // A Platform credit-withdrawal (asset-unlock) payout: zero inputs,
        // all outputs ours. Must NOT classify as a self-transfer. (On-chain it
        // is a version-3 special tx with a quorum-signed payload; the payload
        // needs the native BLS library, and the classifier ignores it anyway,
        // so the operative zero-input shape is modeled directly.)
        val tx = Transaction(networkParams)
        tx.addOutput(Coin.parseCoin("0.5"), myAddress)

        assertFalse(tx.isEntirelySelf(bagOf(myAddress.hash)))
    }

    @Test
    fun spendOfOwnCoinToOwnAddress_isEntirelySelf() {
        val parent = Transaction(networkParams)
        parent.addOutput(Coin.COIN, myAddress)

        val tx = Transaction(networkParams)
        tx.addInput(parent.outputs[0])
        tx.addOutput(Coin.COIN.subtract(Coin.valueOf(1000)), myAddress)

        assertTrue(tx.isEntirelySelf(bagOf(myAddress.hash)))
    }

    @Test
    fun spendOfForeignCoinToOurAddress_isNotEntirelySelf() {
        val parent = Transaction(networkParams)
        parent.addOutput(Coin.COIN, notMyAddress)

        val tx = Transaction(networkParams)
        tx.addInput(parent.outputs[0])
        tx.addOutput(Coin.parseCoin("0.25"), myAddress)

        assertFalse(tx.isEntirelySelf(bagOf(myAddress.hash)))
    }
}
