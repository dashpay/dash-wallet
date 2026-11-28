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

package de.schildbach.wallet.data

import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/** All assertions are made right after the mutation call: the counter must not need any waiting. */
class WalletChangeCounterTest {
    private val params = TestNet3Params.get()

    @Before
    fun setUp() {
        // dashj's Context is global; Wallet construction needs one for these params
        Context.propagate(Context(params))
    }

    private fun wallet(): Wallet = Wallet.fromSeed(
        params,
        DeterministicSeed(ByteArray(16) { it.toByte() }, "", 1_600_000_000L),
        Script.ScriptType.P2PKH
    )

    /** A transaction paying to [wallet], from an input that isn't the wallet's. */
    private fun payment(wallet: Wallet): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.of("funding-${System.nanoTime()}".toByteArray()), 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.parseCoin("1"), wallet.freshReceiveAddress())
        return tx
    }

    private val genesis by lazy { StoredBlock(params.genesisBlock, BigInteger.ONE, 0) }

    private fun nextBlock(previous: StoredBlock): StoredBlock =
        previous.build(previous.header.createNextBlock(null))

    @Test
    fun countIsStableWithoutChanges() {
        val counter = WalletChangeCounter()
        val wallet = wallet()
        val count = counter.count(wallet)

        assertEquals(count, counter.count(wallet))
    }

    @Test
    fun receivePending_advancesCount() {
        val counter = WalletChangeCounter()
        val wallet = wallet()
        val tx = payment(wallet)
        val before = counter.count(wallet)

        wallet.receivePending(tx, null)

        assertTrue(counter.count(wallet) > before)
    }

    @Test
    fun bestChainReceive_advancesCount_beforeTheNextBestBlock() {
        // dashj defers onWalletChanged for a best-chain receive to notifyNewBestBlock, which runs in
        // a separate lock acquisition; the confidence listener covers the gap in between
        val counter = WalletChangeCounter()
        val wallet = wallet()
        val tx = payment(wallet)
        val before = counter.count(wallet)

        wallet.receiveFromBlock(tx, nextBlock(genesis), AbstractBlockChain.NewBlockType.BEST_CHAIN, 0)

        assertTrue(counter.count(wallet) > before)
    }

    @Test
    fun reorganize_advancesCount() {
        val counter = WalletChangeCounter()
        val wallet = wallet()
        val oldBlock = nextBlock(genesis)
        val newBlock = nextBlock(genesis)
        wallet.receiveFromBlock(payment(wallet), oldBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0)
        wallet.notifyNewBestBlock(oldBlock)
        val before = counter.count(wallet)

        wallet.reorganize(genesis, listOf(oldBlock), listOf(newBlock))

        assertTrue(counter.count(wallet) > before)
    }

    @Test
    fun differentWallet_advancesCount() {
        val counter = WalletChangeCounter()
        val before = counter.count(wallet())

        assertTrue(counter.count(wallet()) > before)
    }

    @Test
    fun noWallet_advancesCount() {
        val counter = WalletChangeCounter()
        val before = counter.count(wallet())

        assertTrue(counter.count(null) > before)
    }

    @Test
    fun replacedWallet_isNoLongerCounted() {
        val counter = WalletChangeCounter()
        val old = wallet()
        counter.count(old)
        val current = wallet()
        val count = counter.count(current)

        old.receivePending(payment(old), null)

        assertEquals(count, counter.count(current))
    }
}
