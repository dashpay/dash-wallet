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

package de.schildbach.wallet.service.platform.sdk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.VerificationException
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the Phase 5c.2 bridge factory: reconstruct+verify
 * from fixture bytes, the bloom-race adoption of the wallet's instance,
 * memo/exchange-rate persistence on the committed instance, the bounded
 * bytes poll, the dashj-tail hooks — and the load-bearing invariant that
 * NO failure path throws into a send path. No native calls; the SDK Room
 * read is faked via [SdkTxRowSource] and the dashj wallet via mockk.
 */
class SdkBridgedTransactionFactoryTest {

    private val params: NetworkParameters = TestNet3Params.get()
    private val recipientAddress: String by lazy { Address.fromKey(params, ECKey()).toBase58() }
    private val changeAddress: String by lazy { Address.fromKey(params, ECKey()).toBase58() }
    private val exchangeRate = ExchangeRate(Coin.COIN, Fiat.parseFiat("USD", "30"))

    private lateinit var bitcoinjContext: Context

    @Before
    fun setUp() {
        bitcoinjContext = Context.getOrCreate(params)
        Context.propagate(bitcoinjContext)
    }

    /** Same fixture shape as the 5c.0/5c.1 probe tests: one input, recipient + change. */
    private fun buildSendTxBytes(): ByteArray {
        val tx = Transaction(params)
        tx.addInput(
            TransactionInput(
                params, tx, ByteArray(0),
                TransactionOutPoint(params, 0, Sha256Hash.wrap("11".repeat(32)))
            )
        )
        tx.addOutput(Coin.valueOf(1_000_000L), Address.fromString(params, recipientAddress))
        tx.addOutput(Coin.valueOf(500_000L), Address.fromString(params, changeAddress))
        return tx.bitcoinSerialize()
    }

    private fun txidOf(bytes: ByteArray): String = Transaction(params, bytes).txId.toString()

    /** A wallet that accepts the commit and holds nothing beforehand. */
    private fun emptyWallet(): Wallet = mockk<Wallet>(relaxed = true) {
        every { context } returns bitcoinjContext
        every { maybeCommitTx(any()) } returns true
        every { getTransaction(any()) } returns null
    }

    private class FakeRowSource(
        var row: (ByteArray) -> SdkTxRow? = { null }
    ) : SdkTxRowSource {
        var reads = 0
        var lastWireTxid: ByteArray? = null

        override suspend fun sdkTxRow(txidWireBytes: ByteArray): SdkTxRow? {
            reads++
            lastWireTxid = txidWireBytes
            return row(txidWireBytes)
        }
    }

    private class Hooks {
        val committed = mutableListOf<Transaction>()
        var selfSpendMarks = 0
        val analytics = mutableListOf<Pair<Transaction, Wallet>>()
    }

    private fun factory(
        wallet: Wallet?,
        source: SdkTxRowSource = FakeRowSource(),
        hooks: Hooks = Hooks(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob())
    ) = SdkBridgedTransactionFactory(
        source = source,
        scope = scope,
        wallet = { wallet },
        networkParameters = { params },
        onCommitted = { hooks.committed += it },
        onSelfSpendBroadcast = { hooks.selfSpendMarks++ },
        logSendTx = { tx, w -> hooks.analytics += tx to w },
        pollIntervalMs = 1,
        bytesTimeoutMs = 25
    )

    // ── Reconstruct + verify + commit (bytes provided directly) ────────

    @Test
    fun bridge_directBytes_reconstructsVerifiesAndCommits() = runBlocking {
        val bytes = buildSendTxBytes()
        val txid = txidOf(bytes)
        val wallet = emptyWallet()
        val hooks = Hooks()

        val result = factory(wallet, hooks = hooks)
            .bridge(txid, rawTxBytes = bytes, memo = "coffee", exchangeRate = exchangeRate)

        val bridged = result as BridgedTxResult.Bridged
        assertFalse(bridged.adoptedWalletInstance)
        val live = bridged.transaction
        assertEquals(txid, live.txId.toString())
        // The dashj-tail stamps, all set BEFORE the commit persisted them.
        assertEquals("coffee", live.memo)
        assertEquals(exchangeRate, live.exchangeRate)
        assertEquals(Transaction.Purpose.USER_PAYMENT, live.purpose)
        assertEquals(TransactionConfidence.Source.SELF, live.getConfidence(bitcoinjContext).source)
        verify(exactly = 1) { wallet.maybeCommitTx(live) }
        // The tail ran on the live instance.
        assertEquals(listOf(live), hooks.committed)
        assertEquals(1, hooks.selfSpendMarks)
        assertEquals(listOf(live to wallet), hooks.analytics)
    }

    @Test
    fun bridge_nullMemoAndRate_leavesThemUnset() = runBlocking {
        val bytes = buildSendTxBytes()
        val result = factory(emptyWallet()).bridge(txidOf(bytes), rawTxBytes = bytes)

        val live = (result as BridgedTxResult.Bridged).transaction
        assertNull(live.memo)
        assertNull(live.exchangeRate)
    }

    @Test
    fun bridge_txidMismatch_isNotBridged_andNothingCommitted() = runBlocking {
        val bytes = buildSendTxBytes()
        val wrongTxid = "ab".repeat(32)
        val wallet = emptyWallet()
        val hooks = Hooks()

        val result = factory(wallet, hooks = hooks).bridge(wrongTxid, rawTxBytes = bytes)

        assertTrue(result is BridgedTxResult.NotBridged)
        verify(exactly = 0) { wallet.maybeCommitTx(any()) }
        assertEquals(0, hooks.committed.size + hooks.selfSpendMarks + hooks.analytics.size)
    }

    // ── The bloom race: adopt the WALLET's instance ─────────────────────

    @Test
    fun bridge_walletAlreadyHasTx_adoptsTheWalletInstance() = runBlocking {
        val bytes = buildSendTxBytes()
        val txid = txidOf(bytes)
        // The instance dashj's bloom filters delivered — a DIFFERENT object
        // than the one the factory reconstructs.
        val networkInstance = Transaction(params, bytes)
        val wallet = mockk<Wallet>(relaxed = true) {
            every { context } returns bitcoinjContext
            every { maybeCommitTx(any()) } returns false
            every { getTransaction(Sha256Hash.wrap(txid)) } returns networkInstance
        }
        val hooks = Hooks()

        val result = factory(wallet, hooks = hooks)
            .bridge(txid, rawTxBytes = bytes, memo = "race", exchangeRate = exchangeRate)

        val bridged = result as BridgedTxResult.Bridged
        assertTrue(bridged.adoptedWalletInstance)
        assertSame("must adopt the wallet's live object", networkInstance, bridged.transaction)
        // The app-side stamps were carried onto the ADOPTED instance.
        assertEquals("race", networkInstance.memo)
        assertEquals(exchangeRate, networkInstance.exchangeRate)
        assertEquals(Transaction.Purpose.USER_PAYMENT, networkInstance.purpose)
        // The tail still runs — same as the dashj path's maybeCommitTx==false branch.
        assertEquals(listOf(networkInstance), hooks.committed)
        assertEquals(1, hooks.selfSpendMarks)
    }

    // ── Bytes resolution via the SDK Room seam (bounded poll) ──────────

    @Test
    fun bridge_noBytesGiven_pollsTheSdkRow_withWireOrderTxid() = runBlocking {
        val bytes = buildSendTxBytes()
        val txid = txidOf(bytes)
        // The row lands on the third poll — inside the bound.
        val source = FakeRowSource()
        source.row = { if (source.reads >= 3) SdkTxRow(feeDuffs = 225, rawTxBytes = bytes) else null }

        val result = factory(emptyWallet(), source = source).bridge(txid)

        assertTrue(result is BridgedTxResult.Bridged)
        assertTrue(source.reads >= 3)
        assertTrue(wireTxidBytesOrNull(txid)!!.contentEquals(source.lastWireTxid!!))
    }

    @Test
    fun bridge_bytesNeverAppear_timesOut_toNotBridged() = runBlocking {
        val wallet = emptyWallet()
        val source = FakeRowSource(row = { null })

        val result = factory(wallet, source = source).bridge("cd".repeat(32))

        val notBridged = result as BridgedTxResult.NotBridged
        assertTrue(notBridged.reason.contains("bytes unavailable"))
        assertTrue("the poll must actually have retried", source.reads > 1)
        verify(exactly = 0) { wallet.maybeCommitTx(any()) }
    }

    @Test
    fun bridge_malformedTxid_isNotBridged() = runBlocking {
        val result = factory(emptyWallet()).bridge("not-a-txid")
        assertTrue(result is BridgedTxResult.NotBridged)
    }

    // ── Containment: every failure path returns, never throws ──────────

    @Test
    fun bridge_undecodableBytes_isNotBridged() = runBlocking {
        val wallet = emptyWallet()
        val result = factory(wallet).bridge("ab".repeat(32), rawTxBytes = byteArrayOf(1, 2, 3))

        assertTrue(result is BridgedTxResult.NotBridged)
        verify(exactly = 0) { wallet.maybeCommitTx(any()) }
    }

    @Test
    fun bridge_walletUnavailable_isNotBridged() = runBlocking {
        val bytes = buildSendTxBytes()
        val result = factory(wallet = null).bridge(txidOf(bytes), rawTxBytes = bytes)
        assertTrue(result is BridgedTxResult.NotBridged)
    }

    @Test
    fun bridge_walletLookupThrow_isContained() = runBlocking {
        val bytes = buildSendTxBytes()
        val factory = SdkBridgedTransactionFactory(
            source = FakeRowSource(),
            scope = CoroutineScope(SupervisorJob()),
            wallet = { throw IllegalStateException("wallet loading") },
            networkParameters = { params },
            pollIntervalMs = 1,
            bytesTimeoutMs = 25
        )
        val result = factory.bridge(txidOf(bytes), rawTxBytes = bytes)
        assertTrue(result is BridgedTxResult.NotBridged)
    }

    @Test
    fun bridge_commitThrow_isContained_toNotBridged() = runBlocking {
        val bytes = buildSendTxBytes()
        val wallet = mockk<Wallet>(relaxed = true) {
            every { context } returns bitcoinjContext
            every { maybeCommitTx(any()) } throws VerificationException("double spend")
        }
        val hooks = Hooks()

        val result = factory(wallet, hooks = hooks).bridge(txidOf(bytes), rawTxBytes = bytes)

        val notBridged = result as BridgedTxResult.NotBridged
        assertTrue(notBridged.cause is VerificationException)
        // No tail on a failed bridge.
        assertEquals(0, hooks.committed.size + hooks.selfSpendMarks + hooks.analytics.size)
    }

    @Test
    fun bridge_sdkRowReadThrows_countsAsAbsent_notAsFailure() = runBlocking {
        val source = FakeRowSource(row = { throw IllegalStateException("room down") })
        val result = factory(emptyWallet(), source = source).bridge("cd".repeat(32))
        assertTrue(result is BridgedTxResult.NotBridged)
    }

    @Test
    fun bridge_tailHookFailures_doNotUndoTheBridge() = runBlocking {
        val bytes = buildSendTxBytes()
        val factory = SdkBridgedTransactionFactory(
            source = FakeRowSource(),
            scope = CoroutineScope(SupervisorJob()),
            wallet = { emptyWallet() },
            networkParameters = { params },
            onCommitted = { throw IllegalStateException("service not started") },
            onSelfSpendBroadcast = { throw IllegalStateException("shadow not running") },
            logSendTx = { _, _ -> throw IllegalStateException("analytics down") },
            pollIntervalMs = 1,
            bytesTimeoutMs = 25
        )

        val result = factory.bridge(txidOf(bytes), rawTxBytes = bytes)

        assertTrue("tail failures must not demote a committed bridge", result is BridgedTxResult.Bridged)
    }

    @Test
    fun bridgeInBackground_containsEveryFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val source = FakeRowSource(row = { throw IllegalStateException("room down") })
        val job = factory(wallet = null, source = source, scope = scope)
            .bridgeInBackground("malformed-txid")

        job.join()
        assertTrue(job.isCompleted)
        assertFalse("a bridge failure escaped into the launched job", job.isCancelled)
    }
}
