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

package de.schildbach.wallet.ui.transactions

import de.schildbach.wallet_test.R
import io.mockk.every
import io.mockk.mockk
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionBag
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Icon classification for home-screen transaction rows.
 *
 * Regression coverage for the shielded-balance withdrawal misclassification:
 * a Platform credit-withdrawal (asset-unlock) payout has NO inputs — the coins
 * come from the credit pool and the authorization lives in the quorum-signed
 * payload. Before the fix, `isEntirelySelf` was vacuously true for such a
 * transaction (its input loop never ran; every output is ours), so the row
 * carried the internal-transfer icon even though its text said "Received".
 */
class TransactionRowViewIconTest {
    private val networkParams = TestNet3Params.get()
    private val bitcoinjContext = Context(networkParams)

    // Testnet addresses reused from other host tests in this repo.
    private val myAddress = Address.fromBase58(networkParams, "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy")
    private val myOtherAddress = Address.fromBase58(networkParams, "yLW8Vfeb6sJfB3deb4KGsa5vY9g5pAqWQi")

    private fun bagMock(
        myHashes: List<ByteArray>,
        unspentPool: Map<org.bitcoinj.core.Sha256Hash, Transaction> = mapOf()
    ): TransactionBag {
        val bag = mockk<TransactionBag>()
        every { bag.isPubKeyHashMine(any(), any()) } answers {
            val hash = firstArg<ByteArray>()
            myHashes.any { it.contentEquals(hash) }
        }
        every { bag.isPubKeyMine(any()) } returns false
        every { bag.isPayToScriptHashMine(any()) } returns false
        every { bag.isCoinJoinPubKeyHashMine(any(), any()) } returns false
        every { bag.isCoinJoinPubKeyMine(any()) } returns false
        every { bag.isCoinJoinPayToScriptHashMine(any()) } returns false
        every { bag.isWatchedScript(any()) } returns false
        every { bag.isFullyMixed(any()) } returns false
        every { bag.isLockedOutput(any()) } returns false
        every { bag.getTransactionPool(WalletTransaction.Pool.UNSPENT) } returns unspentPool
        every { bag.getTransactionPool(WalletTransaction.Pool.SPENT) } returns mapOf()
        every { bag.getTransactionPool(WalletTransaction.Pool.PENDING) } returns mapOf()
        return bag
    }

    @Test
    fun creditWithdrawalPayout_noInputs_getsReceiveIconAndReceivedTitle() {
        // Shielded-balance withdrawal as dashj sees it: an input-less
        // asset-unlock transaction paying a fresh receive address of ours.
        // On-chain this is a version-3 TRANSACTION_ASSET_UNLOCK special tx whose
        // authorization lives in a quorum-signed extra payload (which needs the
        // native BLS library to construct, unavailable on the host JVM). The row
        // classifier never looks at the payload or the special type — the operative
        // shape is "no inputs, all outputs ours", modeled directly here.
        val tx = Transaction(networkParams)
        tx.addOutput(Coin.parseCoin("0.5"), myAddress)

        val bag = bagMock(listOf(myAddress.hash))
        val row = TransactionRowView.fromTransaction(tx, bag, bitcoinjContext, chainLockBlockHeight = 0)

        assertEquals(
            "an incoming cross-chain withdrawal must carry the receive icon",
            R.drawable.ic_transaction_received,
            row.icon
        )
        assertEquals(R.style.TxReceivedBackground, row.iconBackground)
        assertEquals(
            "row text and icon must agree: this is a receive",
            R.string.transaction_row_status_received,
            row.title?.resourceId
        )
    }

    @Test
    fun trueSelfSend_allInputsAndOutputsOurs_keepsInternalIcon() {
        // A real L1 self-send: spends our own coin, pays our own address.
        val parent = Transaction(networkParams)
        parent.addOutput(Coin.COIN, myAddress)

        val tx = Transaction(networkParams)
        tx.addInput(parent.outputs[0]) // connects the outpoint to the parent output
        tx.addOutput(Coin.COIN.subtract(Coin.valueOf(1000)), myOtherAddress)

        val bag = bagMock(
            myHashes = listOf(myAddress.hash, myOtherAddress.hash),
            unspentPool = mapOf(parent.txId to parent)
        )
        val row = TransactionRowView.fromTransaction(tx, bag, bitcoinjContext, chainLockBlockHeight = 0)

        assertEquals(
            "a send-to-own-address must keep the internal-transfer icon",
            R.drawable.ic_internal,
            row.icon
        )
        assertEquals(R.style.TxSentBackground, row.iconBackground)
        assertEquals(R.string.transaction_row_status_sent_internally, row.title?.resourceId)
    }

    @Test
    fun regularIncomingPayment_inputsNotOurs_getsReceiveIcon() {
        // Ordinary receive: the input spends a coin that is not ours.
        val strangerParent = Transaction(networkParams)
        strangerParent.addOutput(Coin.COIN, Address.fromBase58(networkParams, "yd9CUc7wvATUS3GfdmcAhRZhG7719jhNf9"))

        val tx = Transaction(networkParams)
        tx.addInput(strangerParent.outputs[0])
        tx.addOutput(Coin.parseCoin("0.25"), myAddress)

        // The parent is NOT in any wallet pool and its output is not ours.
        val bag = bagMock(listOf(myAddress.hash))
        val row = TransactionRowView.fromTransaction(tx, bag, bitcoinjContext, chainLockBlockHeight = 0)

        assertEquals(R.drawable.ic_transaction_received, row.icon)
        assertEquals(R.style.TxReceivedBackground, row.iconBackground)
        assertEquals(R.string.transaction_row_status_received, row.title?.resourceId)
    }
}
