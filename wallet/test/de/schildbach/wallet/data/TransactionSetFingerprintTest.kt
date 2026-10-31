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

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.bitcoinj.wallet.WalletTransaction
import org.bitcoinj.wallet.WalletTransaction.Pool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransactionSetFingerprintTest {
    private val params = TestNet3Params.get()

    @Before
    fun setUp() {
        // dashj's Context is global; Wallet construction needs one for these params
        Context.propagate(Context(params))
    }

    private fun seed() = DeterministicSeed(ByteArray(16) { it.toByte() }, "", 1_600_000_000L)

    private fun walletFromSeed(): Wallet = Wallet.fromSeed(params, seed(), Script.ScriptType.P2PKH)

    /** A pending transaction paying to [wallet], from an input that isn't the wallet's. */
    private fun receive(wallet: Wallet): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.of("funding-${System.nanoTime()}".toByteArray()), 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.parseCoin("1"), wallet.freshReceiveAddress())
        wallet.receivePending(tx, null)
        return tx
    }

    private val externalAddress: Address = Address.fromKey(params, ECKey.fromPrivate(ByteArray(32) { 1 }))

    /**
     * A pending transaction spending [funding]'s first output (one of [wallet]'s) to an address that
     * isn't the wallet's. Committing it connects its input and marks that output spent by it.
     */
    private fun spend(wallet: Wallet, funding: Transaction): Transaction {
        val tx = Transaction(params)
        tx.addInput(funding.getOutput(0))
        tx.addOutput(Coin.parseCoin("0.5"), externalAddress)
        wallet.receivePending(tx, null)
        return tx
    }

    private fun roundTrip(wallet: Wallet): Wallet {
        val bytes = ByteArrayOutputStream().also { WalletProtobufSerializer().writeWallet(wallet, it) }.toByteArray()
        return WalletProtobufSerializer().readWallet(ByteArrayInputStream(bytes))
    }

    private fun txId(seed: Int): Sha256Hash = Sha256Hash.of(byteArrayOf(seed.toByte(), (seed shr 8).toByte()))

    /** A distinct, unconnected transaction in [pool], identified by [seed]. */
    private fun tx(seed: Int, pool: Pool = Pool.UNSPENT): WalletTransaction {
        val tx = Transaction(params)
        tx.addInput(txId(seed), 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.COIN, externalAddress)
        return WalletTransaction(pool, tx)
    }

    // Stand-ins for a watching key's pubkey + chain code (33 + 32 bytes) and an imported pubkey
    private fun keyId(seed: Int, size: Int = 65): ByteArray = ByteArray(size) { (seed + it).toByte() }

    // Lazy: built after setUp() has propagated the dashj Context
    private val ids by lazy { (1..1000).map { tx(it) } }
    private val walletA = listOf(keyId(1))
    private val walletB = listOf(keyId(2))

    private fun fingerprint(transactions: Collection<WalletTransaction>, keyIds: Collection<ByteArray> = walletA) =
        TransactionSetFingerprint.of(keyIds, transactions)

    @Test
    fun sameSetInDifferentOrder_sameFingerprint() {
        assertEquals(fingerprint(ids), fingerprint(ids.reversed()))
        assertEquals(fingerprint(ids), fingerprint(ids.shuffled()))
    }

    @Test
    fun sameKeysAndSameSet_sameFingerprint_acrossFreshByteArrays() {
        assertEquals(fingerprint(ids, listOf(keyId(1))), fingerprint(ids.shuffled(), listOf(keyId(1))))
    }

    @Test
    fun differentSetWithSameCount_differentFingerprint() {
        val other = ids.dropLast(1) + tx(1001)

        assertEquals(ids.size, other.size)
        assertNotEquals(fingerprint(ids), fingerprint(other))
    }

    @Test
    fun addedTransaction_changesFingerprint() {
        assertNotEquals(fingerprint(ids), fingerprint(ids + tx(1001)))
    }

    @Test
    fun sameSetWithDifferentKeys_differentFingerprint() {
        assertNotEquals(fingerprint(ids, walletA), fingerprint(ids, walletB))
        assertNotEquals(fingerprint(emptyList(), walletA), fingerprint(emptyList(), walletB))
    }

    @Test
    fun addedImportedKey_changesFingerprint() {
        assertNotEquals(fingerprint(ids, walletA), fingerprint(ids, walletA + keyId(3, size = 33)))
    }

    @Test
    fun noKeys_differsFromKeys() {
        assertNotEquals(fingerprint(ids, emptyList()), fingerprint(ids, walletA))
    }

    @Test
    fun keyIdentitiesInDifferentOrder_sameFingerprint() {
        val keys = listOf(keyId(1), keyId(2), keyId(3, size = 33))

        assertEquals(fingerprint(ids, keys), fingerprint(ids, keys.reversed()))
    }

    @Test
    fun sameTransactionInDifferentPool_differentFingerprint() {
        val unspent = tx(1, Pool.UNSPENT)
        val dead = WalletTransaction(Pool.DEAD, unspent.transaction)

        assertNotEquals(fingerprint(listOf(unspent)), fingerprint(listOf(dead)))
    }

    @Test
    fun includesVersionAndCount() {
        assertTrue(fingerprint(ids).startsWith("v4:1000:"))
    }

    @Test
    fun emptySet_isNotTheNoWalletValue() {
        val empty = fingerprint(emptyList())

        assertTrue(empty.startsWith("v4:0:"))
        assertNotEquals(TransactionSetFingerprint.NONE, empty)
    }

    @Test
    fun noWallet_isNone() {
        assertEquals(TransactionSetFingerprint.NONE, TransactionSetFingerprint.of(null as Wallet?))
    }

    @Test
    fun realWallet_matchesItsKeysAndTransactions() {
        val wallet = walletFromSeed()
        receive(wallet)

        val fingerprint = TransactionSetFingerprint.of(wallet)

        assertNotEquals(TransactionSetFingerprint.NONE, fingerprint)
        assertTrue(fingerprint.startsWith("v4:1:"))
        assertEquals(
            TransactionSetFingerprint.of(TransactionSetFingerprint.keyIds(wallet), wallet.walletTransactions),
            fingerprint
        )
    }

    @Test
    fun realWallet_importedKey_changesFingerprint() {
        val wallet = walletFromSeed()
        val before = TransactionSetFingerprint.of(wallet)

        wallet.importKey(ECKey())

        assertNotEquals(before, TransactionSetFingerprint.of(wallet))
    }

    @Test
    fun realWallet_receivedTransaction_changesFingerprint() {
        val wallet = walletFromSeed()
        val before = TransactionSetFingerprint.of(wallet)

        receive(wallet)

        val after = TransactionSetFingerprint.of(wallet)
        assertNotEquals(before, after)
        assertTrue(after.startsWith("v4:1:"))
        // Receiving also marks the key used, which may move the lookahead; the transaction alone,
        // against the wallet's current keys, changes it too
        assertNotEquals(TransactionSetFingerprint.of(TransactionSetFingerprint.keyIds(wallet), emptyList()), after)
    }

    @Test
    fun realWallet_issuedKey_withSameTransactions_changesFingerprint() {
        val wallet = walletFromSeed()
        receive(wallet)
        val txCount = wallet.getTransactionCount(true)
        val before = TransactionSetFingerprint.of(wallet)

        wallet.freshReceiveKey()

        assertEquals(txCount, wallet.getTransactionCount(true))
        assertNotEquals(before, TransactionSetFingerprint.of(wallet))
    }

    @Test
    fun realWallet_lookaheadGrowth_withSameWatchingKeyAndTransactions_changesFingerprint() {
        // The reviewer's case: more keys derived, nothing issued, no transaction added. An output
        // already in the wallet could now be "mine", so a cached negative result must not match.
        val wallet = walletFromSeed()
        receive(wallet)
        val chain = wallet.activeKeyChain
        val issuedExternal = chain.issuedExternalKeys
        val issuedInternal = chain.issuedInternalKeys
        val numKeys = chain.numKeys()
        val before = TransactionSetFingerprint.of(wallet)

        chain.setLookaheadSize(chain.lookaheadSize * 2)
        chain.maybeLookAhead()

        assertTrue("lookahead derived more keys", chain.numKeys() > numKeys)
        assertEquals(issuedExternal, chain.issuedExternalKeys)
        assertEquals(issuedInternal, chain.issuedInternalKeys)
        assertNotEquals(before, TransactionSetFingerprint.of(wallet))
    }

    @Test
    fun realWallets_fromSameSeedWithoutTransactions_sameFingerprint() {
        assertEquals(TransactionSetFingerprint.of(walletFromSeed()), TransactionSetFingerprint.of(walletFromSeed()))
    }

    @Test
    fun realWallets_fromDifferentSeeds_differentFingerprint() {
        val other = Wallet.createDeterministic(params, Script.ScriptType.P2PKH)

        assertNotEquals(TransactionSetFingerprint.of(walletFromSeed()), TransactionSetFingerprint.of(other))
    }

    @Test
    fun realWallet_savedAndReloaded_sameFingerprint() {
        // The cached value has to survive a restart, so the derived-key state must too
        val wallet = walletFromSeed()
        val funding = receive(wallet)
        // A connected input and a spent output, so the graph state has to round-trip as well
        spend(wallet, funding)
        wallet.freshReceiveKey()
        wallet.importKey(ECKey())

        val before = TransactionSetFingerprint.of(wallet)
        assertTrue(before.startsWith("v4:2:"))
        assertEquals(before, TransactionSetFingerprint.of(roundTrip(wallet)))
    }

    @Test
    fun realWallet_inputConnectionChange_withSameKeysAndTransactions_changesFingerprint() {
        // The reviewer's case: the matcher reads an input's connected output (its address,
        // isEntirelySelf, the value sent from the wallet), which the txid set doesn't fix
        val wallet = walletFromSeed()
        val funding = receive(wallet)
        val spending = spend(wallet, funding)
        val input = spending.getInput(0)
        val output = funding.getOutput(0)
        val keyIds = TransactionSetFingerprint.keyIds(wallet).map(ByteArray::toList)
        val txCount = wallet.getTransactionCount(true)
        assertEquals(input, output.spentBy)
        assertNotNull(input.connectedOutput)
        val before = TransactionSetFingerprint.of(wallet)

        // Disconnect the input only: disconnect() also unmarks the output, so mark it spent again
        input.disconnect()
        output.markAsSpent(input)

        assertNull(input.connectedOutput)
        assertEquals(input, output.spentBy)
        assertEquals(txCount, wallet.getTransactionCount(true))
        assertEquals(keyIds, TransactionSetFingerprint.keyIds(wallet).map(ByteArray::toList))
        assertNotEquals(before, TransactionSetFingerprint.of(wallet))

        // Reconnecting restores the state, and with it the fingerprint
        output.markAsUnspent()
        assertEquals(TransactionInput.ConnectionResult.SUCCESS, input.connect(funding, TransactionInput.ConnectMode.ABORT_ON_CONFLICT))

        assertEquals(input, output.spentBy)
        assertNotNull(input.connectedOutput)
        assertEquals(before, TransactionSetFingerprint.of(wallet))
    }

    @Test
    fun realWallet_outputSpentByChange_withSameKeysAndTransactions_changesFingerprint() {
        // The top-up discovery follows an output's spentBy, which the txid set doesn't fix
        val wallet = walletFromSeed()
        val funding = receive(wallet)
        val spending = spend(wallet, funding)
        val input = spending.getInput(0)
        val output = funding.getOutput(0)
        val keyIds = TransactionSetFingerprint.keyIds(wallet).map(ByteArray::toList)
        val txCount = wallet.getTransactionCount(true)
        val before = TransactionSetFingerprint.of(wallet)

        // Unmark the output only: the input stays connected
        output.markAsUnspent()

        assertNull(output.spentBy)
        assertNotNull(input.connectedOutput)
        assertEquals(txCount, wallet.getTransactionCount(true))
        assertEquals(keyIds, TransactionSetFingerprint.keyIds(wallet).map(ByteArray::toList))
        assertNotEquals(before, TransactionSetFingerprint.of(wallet))

        output.markAsSpent(input)

        assertEquals(before, TransactionSetFingerprint.of(wallet))
    }
}
