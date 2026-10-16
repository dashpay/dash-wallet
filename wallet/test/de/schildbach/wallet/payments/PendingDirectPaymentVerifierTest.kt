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

package de.schildbach.wallet.payments

import de.schildbach.wallet.service.platform.sdk.ShadowSyncPhase
import de.schildbach.wallet.service.platform.sdk.ShadowSyncProgress
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PendingDirectPayment
import de.schildbach.wallet.data.PendingDirectPaymentConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.util.toTxId
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Date
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

class PendingDirectPaymentVerifierTest {
    companion object {
        // static: JUnit builds a new test instance per method, so a per-instance counter would
        // restart and hand every test the same txid
        private val txCounter = AtomicInteger(0)

        /** The engine's height when a test reservation was made; also the default current tip. */
        private const val RESERVED_AT_HEIGHT = 1_000_000L
    }

    private val params = TestNet3Params.get()
    private val paymentUrl = "https://merchant.example/payment"

    private lateinit var wallet: Wallet
    private lateinit var walletData: WalletData
    private lateinit var engine: PendingPaymentEngine
    private lateinit var walletApplication: WalletApplication
    private lateinit var blockchainStateProvider: BlockchainStateProvider
    private lateinit var metadataProvider: TransactionMetadataProvider
    private lateinit var config: PendingDirectPaymentConfig
    private lateinit var verifier: PendingDirectPaymentVerifier

    /** What WalletApplication.finalizeWipe() invokes; captured so tests can run a wipe. */
    private lateinit var onWalletWiped: suspend () -> Unit

    @Before
    fun setUp() {
        Context.propagate(Context(params))
        wallet = Wallet.createBasic(params)

        walletData = mockk(relaxed = true)
        every { walletData.wallet } returns wallet
        walletApplication = mockk(relaxed = true)
        blockchainStateProvider = mockk(relaxed = true)
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.DISCONNECTED
        every { blockchainStateProvider.getConnectedPeerCount() } returns 0
        coEvery { blockchainStateProvider.getState() } returns null
        metadataProvider = mockk(relaxed = true)
        coEvery { metadataProvider.forgetTransaction(any()) } returns true
        config = mockk(relaxed = true)
        coEvery { config.getAllOrThrow() } returns emptyList()

        val wipeListener = slot<suspend () -> Unit>()
        every { walletData.attachOnWalletWipedListener(capture(wipeListener)) } answers { }

        // The dashj regime by default: the SDK neither owns L1 nor has seen anything, and its
        // bridge declines, so commits take the plain maybeCommitTx path.
        engine = mockk(relaxed = true)
        coEvery { engine.sdkOwnsL1() } returns false
        coEvery { engine.observation(any()) } returns EngineObservation.ABSENT
        every { engine.isObservationLive() } returns true
        every { engine.chainHeightUpperBound() } returns RESERVED_AT_HEIGHT
        coEvery { engine.bridge(any(), any(), any()) } returns null

        verifier = PendingDirectPaymentVerifier(
            walletData, walletApplication, blockchainStateProvider, metadataProvider, config, engine
        ).apply {
            pollIntervalMs = 20L
        }
        onWalletWiped = if (wipeListener.isCaptured) wipeListener.captured else ({ })
    }

    /**
     * Stops every watch this test started. The verifier's scope outlives the test, and a watch
     * that never resolves keeps polling mocks that record each call, which grows without bound
     * across the suite and starves the tests that share the fork.
     */
    @After
    fun tearDown() = runBlocking {
        onWalletWiped()
    }

    /**
     * A structurally valid tx spending one (foreign) outpoint; signatures aren't checked on commit.
     * Each call spends a different outpoint, so every test gets its own txid. dashj keeps
     * TransactionConfidence in a table on the global Context, so tests that reused a txid would
     * inherit each other's broadcast state.
     */
    private fun createTransaction(): Transaction {
        val tx = Transaction(params)
        val unique = Sha256Hash.of("pending-payment-${txCounter.incrementAndGet()}".toByteArray())
        tx.addInput(unique, 0, ScriptBuilder.createEmpty())
        val address = Address.fromString(params, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL")
        tx.addOutput(Coin.parseCoin("0.01"), address)
        return tx
    }

    private fun goOnlineAndSynced() {
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.CONNECTED
        every { blockchainStateProvider.getConnectedPeerCount() } returns 4
        coEvery { blockchainStateProvider.getState() } returns BlockchainState(
            Date(), 100_000, false, EnumSet.noneOf(BlockchainState.Impediment::class.java), 0, 0, 100
        )
    }

    @Test
    fun `quarantine locks inputs, persists the payment and stays pending without network evidence`() = runBlocking {
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(100)

        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
        coVerify { config.add(match { it.txId == tx.txId && it.paymentUrl == paymentUrl && it.serviceName == "CTXSpend" }, any()) }
        assertTrue(verifier.isTracked(tx.txId))
        assertFalse("must not resolve while disconnected", result.isCompleted)
        assertNull(wallet.getTransaction(tx.txId))
    }

    @Test
    fun `commits, unlocks and broadcasts once a peer announces the tx`() = runBlocking {
        val tx = createTransaction()
        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")

        // a peer relays the tx: the merchant did broadcast it
        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))

        val committed = withTimeout(5_000) { result.await() }

        assertNotNull(committed)
        assertEquals(tx.txId, committed!!.txId)
        assertNotNull("tx should be committed to the wallet", wallet.getTransaction(tx.txId))
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify { metadataProvider.setTransactionService(tx.txId.toTxId(), "CTXSpend") }
        verify { walletApplication.broadcastTransaction(match { it.txId == tx.txId }) }
        coVerify { config.remove(tx.txId) }
        assertFalse(verifier.isTracked(tx.txId))
    }

    @Test
    fun `releases inputs when connected and synced for the grace period without seeing the tx`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, null)
        val committed = withTimeout(5_000) { result.await() }

        assertNull("tx never seen: must not be committed", committed)
        assertNull(wallet.getTransaction(tx.txId))
        tx.inputs.forEach { assertFalse("inputs must be spendable again", wallet.isLockedOutput(it.outpoint)) }
        coVerify { config.remove(tx.txId) }
        verify(exactly = 0) { walletApplication.broadcastTransaction(any()) }
    }

    @Test
    fun `removes optimistically saved gift cards when the payment is abandoned`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) { result.await() }

        // the order was never placed, so everything recorded for it must go: cards, metadata,
        // and anything queued for Dash Platform
        coVerify { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
    }

    @Test
    fun `keeps gift cards when the payment is confirmed on the network`() = runBlocking {
        val tx = createTransaction()
        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")

        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))
        withTimeout(5_000) { result.await() }

        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
    }

    @Test
    fun `keeps the payment when its records could not be discarded`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        // cleanup deferred, e.g. no wallet available to confirm the transaction is absent
        coEvery { metadataProvider.forgetTransaction(any()) } returns false
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) { result.await() }

        // inputs are freed, but the record survives so the cleanup can be retried
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
        coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
    }

    @Test
    fun `aborts and frees the inputs when the quarantine cannot be persisted`() = runBlocking {
        // Callers quarantine before submitting, so nothing has been sent and aborting is free.
        // Proceeding without a record would leave memory-only locks that no restart could
        // restore, and the inputs of an uncertain payment free to fund a retry.
        coEvery { config.add(any(), any()) } throws RuntimeException("datastore is gone")
        val tx = createTransaction()

        val thrown = try {
            verifier.quarantine(tx, paymentUrl, "CTXSpend")
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("the caller must not be allowed to submit", thrown)
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        assertFalse(verifier.isTracked(tx.txId))
    }

    @Test
    fun `keeps inputs locked and the payment active when the release cannot be persisted`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        // the quarantine write succeeds; only the abandoned transition fails
        coEvery { config.add(match { it.abandoned }, any()) } throws RuntimeException("datastore is gone")
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        // nothing may move until the release is durable: a stored payment still marked active
        // with freed inputs would be re-locked and re-verified by the next resume()
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(any()) }
        assertFalse("the payment must stay pending and keep retrying", result.isCompleted)
    }

    @Test
    fun `keeps the payment when discarding its records throws`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        coEvery { metadataProvider.forgetTransaction(any()) } throws RuntimeException("database is gone")
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) { result.await() }

        coVerify(exactly = 0) { config.remove(tx.txId) }
    }

    @Test
    fun `resume keeps watching an abandoned payment without locking its inputs`() = runBlocking {
        verifier.recordRetentionMs = 0L
        // the retention cleanup acts only while connected and synced, like the release
        goOnlineAndSynced()
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend",
                System.currentTimeMillis(), abandoned = true
            )
        )

        // awaitRestored, not resume: it returns only once the scan has finished, so the job is
        // registered before anything is asserted. resume() returns immediately and isTracked
        // reads false for a job that has not been created yet, so polling straight after it can
        // pass without the scan having run at all.
        withTimeout(5_000) { verifier.awaitRestored() }
        // Then wait for cleanup to finish, not merely for forgetTransaction to be called: the
        // verifier still has to return from it and run finish, which clears the tracking job.
        withTimeout(5_000) {
            while (verifier.isTracked(tx.txId)) {
                delay(10)
            }
        }

        // an abandoned payment is never re-locked or re-verified, only cleaned up
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        coVerify { config.remove(tx.txId) }
    }

    @Test
    fun `does not release while no peers are connected, however synced the status looks`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        // NetworkStatus only leaves CONNECTED by way of DISCONNECTING, so it can read CONNECTED
        // with no peers at all, and the cached chain tip stays recent for another half hour.
        // Without peers there is no evidence either way, and the tx may already be on the network.
        every { blockchainStateProvider.getConnectedPeerCount() } returns 0
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        assertFalse("must not declare a payment dead without ever seeing the network", result.isCompleted)
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
    }

    @Test
    fun `keeps the order and keeps watching after releasing the inputs`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        // The payee decides when to relay the transaction, so silence is not proof it never
        // arrived. Free the inputs, but keep the order and the watch.
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        assertFalse("the watch must continue past release", result.isCompleted)
    }

    @Test
    fun `commits a payment broadcast after its inputs were released`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(
            tx,
            paymentUrl,
            "PiggyCards",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = "https://logo.example/x.png")
        )
        delay(400)
        // a payee that withheld the transaction until after release finally relays it
        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))

        val committed = withTimeout(5_000) { result.await() }

        assertNotNull("a late broadcast must still be committed", committed)
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify { metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), "PiggyCards", "https://logo.example/x.png") }
    }

    @Test
    fun `keeps a gift card order when the retention period runs out`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(
            tx,
            paymentUrl,
            "PiggyCards",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = null)
        )
        withTimeout(5_000) { result.await() }

        // The watch stops, but nothing is deleted: the record holds the only durable copy of the
        // selected provider, and without it a late arrival cannot be attributed or retrieved.
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
        coVerify { config.add(match { it.txId == tx.txId && it.watchExpired }, any()) }
    }

    @Test
    fun `attributes a gift card payment that arrives after its watch expired`() = runBlocking {
        val tx = createTransaction()
        // the wallet found it through ordinary syncing, long after polling stopped
        wallet.maybeCommitTx(tx)
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                System.currentTimeMillis(), isGiftCardPurchase = true,
                merchantIconUrl = "https://logo.example/x.png",
                abandoned = true, watchExpired = true
            )
        )

        verifier.resume()
        withTimeout(5_000) {
            coVerify(timeout = 5_000) {
                metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), "PiggyCards", "https://logo.example/x.png")
            }
        }
        // the record goes after the attribution, on the watch's own coroutine: wait for it
        coVerify(timeout = 5_000) { config.remove(tx.txId) }
    }

    @Test
    fun `leaves an expired watch alone while its transaction is still absent`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                System.currentTimeMillis(), isGiftCardPurchase = true,
                abandoned = true, watchExpired = true
            )
        )

        verifier.resume()
        delay(300)

        // no polling, no locks, and the record survives for the next start to look again
        assertFalse(verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
    }

    @Test
    fun `discards an ordinary payment when the retention period runs out`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "SomeService")
        withTimeout(5_000) { result.await() }

        coVerify { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
    }

    @Test
    fun `cancelling a quarantine frees the inputs and forgets the record`() = runBlocking {
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend")

        verifier.cancelQuarantine(tx)

        // used when the outcome becomes certain, so nothing may be left for a restart to resume
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify { config.remove(tx.txId) }
        assertFalse(verifier.isTracked(tx.txId))
    }

    @Test
    fun `awaitRestored blocks until persisted quarantines have their locks back`() = runBlocking {
        val tx = createTransaction()
        val gate = CompletableDeferred<Unit>()
        coEvery { config.getAllOrThrow() } coAnswers {
            // stand in for a slow preferences read after a restart
            gate.await()
            listOf(
                PendingDirectPayment(
                    tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend", System.currentTimeMillis()
                )
            )
        }

        val restored = async { verifier.awaitRestored() }
        delay(200)

        // a payment flow reaching this point must not be allowed past while the inputs of an
        // uncertain payment are still unprotected
        assertFalse("must not proceed before restoration", restored.isCompleted)
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }

        gate.complete(Unit)
        withTimeout(5_000) { restored.await() }

        tx.inputs.forEach { assertTrue("locks must be back before spending", wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `a later scan attributes an expired payment whose transaction has since arrived`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                System.currentTimeMillis(), isGiftCardPurchase = true,
                merchantIconUrl = "https://logo.example/x.png",
                abandoned = true, watchExpired = true
            )
        )

        // first scan: the transaction is not in the wallet yet, so the record is left alone
        verifier.awaitRestored()
        coVerify(exactly = 0) { metadataProvider.markGiftCardTransaction(any(), any(), any()) }

        // ordinary syncing finds it afterwards, and a later service start scans again
        wallet.maybeCommitTx(tx)
        verifier.resume()

        // scans must stay repeatable: an expired record has no watch and no arrival observer,
        // so only a later scan can attribute it
        withTimeout(5_000) {
            coVerify(timeout = 5_000) {
                metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), "PiggyCards", "https://logo.example/x.png")
            }
        }
    }

    @Test
    fun `awaitRestored refuses to proceed when the stored payments cannot be read`() = runBlocking {
        verifier.restoreTimeoutMs = 2_000L
        coEvery { config.getAllOrThrow() } throws RuntimeException("preferences unreadable")

        val thrown = try {
            verifier.awaitRestored()
            null
        } catch (e: Exception) {
            e
        }

        // an unreadable store is not an empty one: treating it as such would report every
        // outpoint as free to spend
        assertNotNull("a failed read must not count as readiness", thrown)
    }

    @Test
    fun `awaitRestored retries after a failed scan`() = runBlocking {
        verifier.restoreTimeoutMs = 2_000L
        var attempt = 0
        coEvery { config.getAllOrThrow() } coAnswers {
            attempt++
            if (attempt == 1) throw RuntimeException("transient read failure") else emptyList()
        }

        try {
            verifier.awaitRestored()
        } catch (expected: Exception) {
            // first attempt fails
        }
        // a failure must not lock payments out for good
        verifier.awaitRestored()
        assertEquals(2, attempt)
    }

    @Test
    fun `awaitRestored refuses to proceed when restoration cannot finish`() = runBlocking {
        verifier.restoreTimeoutMs = 150L
        coEvery { config.getAllOrThrow() } coAnswers {
            delay(10_000)
            emptyList()
        }

        val thrown = try {
            verifier.awaitRestored()
            null
        } catch (e: Exception) {
            e
        }

        // refusing a new payment beats risking the inputs of one already out there
        assertNotNull("must fail closed", thrown)
    }

    @Test
    fun `does not release before the minimum age even when synced`() = runBlocking {
        verifier.minAgeMs = 60_000L
        verifier.syncedGraceMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, null)
        delay(200)

        assertFalse(result.isCompleted)
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `resume re-locks inputs of persisted payments and tracks them`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend", System.currentTimeMillis())
        )

        verifier.resume()
        withTimeout(5_000) {
            while (!verifier.isTracked(tx.txId)) delay(10)
        }

        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
        assertNull(wallet.getTransaction(tx.txId))
    }

    @Test
    fun `resume keeps a payment it cannot restore`() = runBlocking {
        val txId = Sha256Hash.of(byteArrayOf(9))
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(txId, byteArrayOf(0, 1), paymentUrl, null, System.currentTimeMillis())
        )

        verifier.resume()
        delay(300)

        // failing to deserialize says nothing about whether the merchant got the payment, so the
        // record must survive for another attempt rather than being destroyed
        coVerify(exactly = 0) { config.remove(txId) }
        assertFalse(verifier.isTracked(txId))
    }

    @Test
    fun `restores gift card metadata when a recovered purchase is committed`() = runBlocking {
        val tx = createTransaction()
        val result = verifier.quarantine(
            tx,
            paymentUrl,
            "PiggyCards",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = "https://logo.example/x.png")
        )

        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))
        withTimeout(5_000) { result.await() }

        // the purchase screen could not record this: marking a gift card transaction needs the
        // transaction to be in the wallet, which it only is once committed here
        coVerify { metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), "PiggyCards", "https://logo.example/x.png") }
    }

    @Test
    fun `does not mark an ordinary payment as a gift card purchase`() = runBlocking {
        val tx = createTransaction()
        val result = verifier.quarantine(tx, paymentUrl, "SomeService")

        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))
        withTimeout(5_000) { result.await() }

        coVerify(exactly = 0) { metadataProvider.markGiftCardTransaction(any(), any(), any()) }
        coVerify { metadataProvider.setTransactionService(tx.txId.toTxId(), "SomeService") }
    }

    // --- a wiped wallet takes its pending payments with it ------------------------------------

    @Test
    fun `a wipe stops a watch before it can commit onto the replacement wallet`() = runBlocking {
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(100)
        assertTrue(verifier.isTracked(tx.txId))

        // finalizeWipe() notifies its listeners and only then swaps the wallet out; the process
        // keeps running, so this singleton and its jobs survive into the next wallet's life
        onWalletWiped()
        val replacement = Wallet.createBasic(params)
        every { walletData.wallet } returns replacement

        // evidence arrives for a transaction that belonged to the wallet that is now gone
        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))
        delay(200)

        assertFalse("the watch must not outlive its wallet", verifier.isTracked(tx.txId))
        assertNull("must not commit an old payment onto a new wallet", replacement.getTransaction(tx.txId))
        verify(exactly = 0) { walletApplication.broadcastTransaction(any()) }
        coVerify(exactly = 0) { metadataProvider.setTransactionService(tx.txId.toTxId(), any()) }
    }

    @Test
    fun `a watch that outlives the wipe still refuses the replacement wallet`() = runBlocking {
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(100)

        // The wipe swaps the wallet out while jobs are still being asked to stop; cancellation is
        // a request, not an event, so one already past its own check can arrive here. This is that
        // straggler: the listener has not reached it, and the wallet underneath has changed.
        val replacement = Wallet.createBasic(params)
        every { walletData.wallet } returns replacement
        tx.confidence.markBroadcastBy(PeerAddress(params, InetAddress.getLoopbackAddress(), 9999))
        delay(200)

        assertNull("must not commit onto a wallet that did not make this payment", replacement.getTransaction(tx.txId))
        verify(exactly = 0) { walletApplication.broadcastTransaction(any()) }
        coVerify(exactly = 0) { metadataProvider.setTransactionService(tx.txId.toTxId(), any()) }
    }

    @Test
    fun `a wipe clears readiness so the next wallet has to restore for itself`() = runBlocking {
        verifier.awaitRestored()

        onWalletWiped()

        // the replacement wallet's store cannot be read, so readiness must fail rather than be
        // inherited from the scan the wiped wallet passed
        coEvery { config.getAllOrThrow() } throws IllegalStateException("unreadable")
        try {
            verifier.awaitRestored()
            fail("readiness survived the wipe")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `a wipe drops the stored payments itself, whichever listener runs first`() = runBlocking {
        // BaseConfig registers its own wipe listener to clear this store, but listeners run in
        // registration order and this one cannot claim to be first; clearing after the drain
        // leaves the same end state either way round
        onWalletWiped()

        coVerify { config.clearAll() }
    }

    // --- one invoice, one submission -----------------------------------------------------------

    private fun storedPayment(tx: Transaction, requestId: String?, abandoned: Boolean = false) =
        PendingDirectPayment(
            tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend", System.currentTimeMillis(),
            abandoned = abandoned, paymentRequestId = requestId
        )

    @Test
    fun `an invoice with an unresolved submission names the transaction holding it`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))

        assertEquals(tx.txId, verifier.unresolvedSubmissionFor("abc123"))
    }

    @Test
    fun `a different invoice is not blocked by this one`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))

        // merchants routinely serve every invoice from one payment endpoint, so identity has to
        // come from the request itself; matching more loosely would block unrelated purchases
        assertNull(verifier.unresolvedSubmissionFor("def456"))
    }

    @Test
    fun `a released submission stops blocking its invoice`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123", abandoned = true))

        // its inputs were freed because the wallet judged it never sent; refusing to let the user
        // pay after that would strand them on an invoice they never actually paid
        assertNull(verifier.unresolvedSubmissionFor("abc123"))
    }

    // --- the submission claim (review 5e6eb1215f01) ------------------------------------------

    @Test
    fun `a claimed invoice refuses a second claim until the first is released`() = runBlocking {
        val first = createTransaction()
        val second = createTransaction()

        assertNull(verifier.claimSubmission(first.txId, "abc123", isGiftCardPurchase = false))
        // nothing saved yet: only the in-memory claim stands between the two
        assertEquals(first.txId, verifier.claimSubmission(second.txId, "abc123", isGiftCardPurchase = false))
        // a different invoice is not held up by this one
        assertNull(verifier.claimSubmission(second.txId, "def456", isGiftCardPurchase = false))

        verifier.releaseSubmissionClaim(first.txId)
        verifier.releaseSubmissionClaim(second.txId)
        assertNull(verifier.claimSubmission(second.txId, "abc123", isGiftCardPurchase = false))
    }

    @Test
    fun `a saved unresolved record refuses a claim for its invoice`() = runBlocking {
        val saved = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(saved, "abc123"))

        assertEquals(saved.txId, verifier.claimSubmission(createTransaction().txId, "abc123", false))
        // released (abandoned) records do not block, as for the lookup
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(saved, "abc123", abandoned = true))
        assertNull(verifier.claimSubmission(createTransaction().txId, "abc123", false))
    }

    @Test
    fun `gift card purchases are claimed one at a time`() = runBlocking {
        val first = createTransaction()
        assertNull(verifier.claimSubmission(first.txId, null, isGiftCardPurchase = true))
        assertEquals(first.txId, verifier.claimSubmission(createTransaction().txId, null, isGiftCardPurchase = true))
        // an ordinary payment is not a gift card purchase
        assertNull(verifier.claimSubmission(createTransaction().txId, null, isGiftCardPurchase = false))
    }

    @Test
    fun `an unreadable store refuses the claim`() = runBlocking {
        coEvery { config.getAllOrThrow() } throws IllegalStateException("unreadable")
        try {
            verifier.claimSubmission(createTransaction().txId, "abc123", false)
            fail("an unread store was treated as having nothing outstanding")
        } catch (e: IllegalStateException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `a wipe drops claims held for the old wallet`() = runBlocking {
        val old = createTransaction()
        assertNull(verifier.claimSubmission(old.txId, "abc123", isGiftCardPurchase = true))

        onWalletWiped()

        assertNull(verifier.claimSubmission(createTransaction().txId, "abc123", isGiftCardPurchase = true))
    }

    @Test
    fun `a payment request with no identity blocks nothing`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, null))

        // a record written before identities were stored must not match every later invoice
        assertNull(verifier.unresolvedSubmissionFor(null))
        assertNull(verifier.unresolvedSubmissionFor("abc123"))
    }

    // --- the Kotlin SDK engine (phase-1 port) ------------------------------------------------

    private fun reservationFor(tx: Transaction, reservedAtHeight: Long? = RESERVED_AT_HEIGHT) =
        de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment(
            txidHex = tx.txId.toString(),
            rawTxBytes = tx.unsafeBitcoinSerialize(),
            feeDuffs = 1_000L,
            native = null,
            reservedAtHeight = reservedAtHeight
        )

    @Test
    fun `commits once the SDK engine has observed the tx and consumes the held reservation`() = runBlocking {
        val tx = createTransaction()
        val reservation = reservationFor(tx)
        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservation)

        // post-cutover the dashj wallet sees nothing; the engine recording the tx is the evidence
        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED

        val committed = withTimeout(5_000) { result.await() }

        assertNotNull(committed)
        assertEquals(tx.txId, committed!!.txId)
        coVerify(exactly = 1) { engine.broadcast(reservation) }
        coVerify(exactly = 0) { engine.release(any()) }
        coVerify { config.remove(tx.txId) }
    }

    @Test
    fun `a commit through the SDK bridge is not announced through dashj a second time`() = runBlocking {
        val tx = createTransaction()
        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        coEvery { engine.bridge(tx.txId.toString(), any(), any()) } returns tx
        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED

        withTimeout(5_000) { result.await() }

        coVerify(exactly = 1) { engine.bridge(tx.txId.toString(), any(), any()) }
        // the bridge runs the dashj announce itself, as it does after every SDK send
        verify(exactly = 0) { walletApplication.broadcastTransaction(any()) }
    }

    @Test
    fun `releasing a payment gives its SDK reservation back`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        val tx = createTransaction()
        val reservation = reservationFor(tx)

        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservation)
        delay(500)

        coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        coVerify(exactly = 1) { engine.release(reservation) }
        coVerify(exactly = 0) { engine.broadcast(any()) }
    }

    @Test
    fun `once the SDK owns L1 the held dashj peer group does not block the release`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        // the dashj peer group is held after the cutover, so it never connects and never has
        // peers; the engine's stalled-sync signal is folded into isSynced() instead
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.UNKNOWN
        every { blockchainStateProvider.getConnectedPeerCount() } returns 0
        coEvery { engine.sdkOwnsL1() } returns true
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `cancelling a quarantine leaves the SDK reservation to the caller`() = runBlocking {
        val tx = createTransaction()
        val reservation = reservationFor(tx)
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservation)

        verifier.cancelQuarantine(tx)

        // the caller knows whether the outcome consumed it or frees it
        coVerify(exactly = 0) { engine.release(any()) }
        coVerify(exactly = 0) { engine.broadcast(any()) }
        assertNull(verifier.detachReservation(tx.txId))
    }

    @Test
    fun `an abandoned payment the engine has since observed is committed, not forgotten`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        verifier.recordRetentionMs = 0L
        goOnlineAndSynced()
        val tx = createTransaction()
        // the first network check misses it, the one taken right before discarding sees it
        var asked = 0
        coEvery { engine.observation(tx.txId.toString()) } answers {
            if (++asked > 3) EngineObservation.OBSERVED else EngineObservation.ABSENT
        }

        val result = verifier.quarantine(tx, paymentUrl, "SomeService")
        val committed = withTimeout(5_000) { result.await() }

        assertNotNull("a payment the engine has seen must not have its records discarded", committed)
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
    }

    @Test
    fun `once the SDK owns L1 a stalled sync still blocks the release`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        coEvery { engine.sdkOwnsL1() } returns true
        // the SDK derivation raises NETWORK when the engine's scan stalls
        coEvery { blockchainStateProvider.getState() } returns BlockchainState(
            Date(), 100_000, false, EnumSet.of(BlockchainState.Impediment.NETWORK), 0, 0, 100
        )
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        assertFalse("must not declare a payment dead without live network evidence", result.isCompleted)
        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
    }

    // --- evidence the SDK engine cannot give (review 4245734669 / 4245734673) --------------------

    @Test
    fun `an unavailable observation never lets the release grace run out`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        // the store cannot be read: nothing says the payee did not broadcast it
        coEvery { engine.observation(any()) } returns EngineObservation.UNAVAILABLE
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        assertFalse(result.isCompleted)
        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `a synced row without a live SDK engine does not advance the release grace`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        coEvery { engine.sdkOwnsL1() } returns true
        // the service tore the engine down; the persisted row still reads synced and recent
        every { engine.isObservationLive() } returns false
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        tx.inputs.forEach { assertTrue(wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `the release grace restarts whenever the engine becomes unavailable`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 300L
        goOnlineAndSynced()
        coEvery { engine.sdkOwnsL1() } returns true
        // live for ~200 ms at a time, never the full 300 ms grace in one stretch
        val start = System.currentTimeMillis()
        every { engine.isObservationLive() } answers { (System.currentTimeMillis() - start) % 250 < 200 }
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(1_200)

        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
    }

    @Test
    fun `an unavailable observation keeps an abandoned payment's records`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        // long enough that the release happens first and the retention cleanup comes due only
        // once the store has become unreadable
        verifier.recordRetentionMs = 300L
        goOnlineAndSynced()
        val tx = createTransaction()
        // live and absent long enough to release, then the store becomes unreadable before the
        // retention cleanup: it must not delete the records of what may be a real payment
        var asked = 0
        coEvery { engine.observation(tx.txId.toString()) } answers {
            if (++asked <= 6) EngineObservation.ABSENT else EngineObservation.UNAVAILABLE
        }

        verifier.quarantine(tx, paymentUrl, "SomeService")
        delay(800)

        coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
    }

    @Test
    fun `engine observation classification`() {
        assertEquals(EngineObservation.OBSERVED, classifyEngineObservation(recordFound = true, live = false))
        assertEquals(EngineObservation.UNAVAILABLE, classifyEngineObservation(recordFound = null, live = true))
        assertEquals(EngineObservation.UNAVAILABLE, classifyEngineObservation(recordFound = false, live = false))
        assertEquals(EngineObservation.ABSENT, classifyEngineObservation(recordFound = false, live = true))
    }

    // --- absence needs processing to have caught up (review 3cb2e95bbc6b) --------------------

    private fun progress(
        filterHeight: Long = 1_000L,
        walletSyncedHeight: Long,
        phase: ShadowSyncPhase = ShadowSyncPhase.FILTERS
    ) = ShadowSyncProgress(
        phase = phase,
        overallPercent = 99.9,
        headerHeight = 1_000L,
        headerTarget = 1_000L,
        filterHeight = filterHeight,
        filterTarget = 1_000L,
        walletSyncedHeight = walletSyncedHeight
    )

    @Test
    fun `filters at the tip with the committed wallet cursor far behind is not a live observation`() {
        // The display predicate reads caught up here, but blocks up to the tip have not been
        // processed yet: a mined payment in them has no row, and its absence proves nothing.
        val lagging = progress(walletSyncedHeight = 500L)
        assertTrue("precondition: the display predicate says caught up", lagging.scanCaughtUpToTip)
        assertTrue("precondition: the block pipeline is lagging", lagging.blockPipelineLagging)

        assertFalse(isEngineObservationLive(engineRunning = true, progress = lagging))
    }

    @Test
    fun `an unknown committed wallet cursor is not a live observation`() {
        // 0 means no evidence; blockPipelineLagging reads false for it, which is not proof
        assertFalse(isEngineObservationLive(engineRunning = true, progress = progress(walletSyncedHeight = 0L)))
    }

    @Test
    fun `a committed wallet cursor at the tip is a live observation`() {
        assertTrue(isEngineObservationLive(engineRunning = true, progress = progress(walletSyncedHeight = 1_000L)))
    }

    @Test
    fun `a committed wallet cursor one or two blocks short of the tip is not a live observation`() {
        // Review 5543338689: a payment mined in block 1000 may have no row while the wallet has
        // committed only through 999. No tolerance: processing must have reached the tip.
        assertFalse(isEngineObservationLive(engineRunning = true, progress = progress(walletSyncedHeight = 999L)))
        assertFalse(isEngineObservationLive(engineRunning = true, progress = progress(walletSyncedHeight = 998L)))
    }

    @Test
    fun `a header target behind the known header height is not a known tip`() {
        // headers have moved past the target the cursor is compared against: the target is stale
        val stale = progress(walletSyncedHeight = 1_000L).copy(headerHeight = 1_001L)
        assertFalse(isEngineObservationLive(engineRunning = true, progress = stale))
        // and no tip at all is no evidence either
        val unknown = progress(walletSyncedHeight = 1_000L).copy(headerHeight = 0L, headerTarget = 0L)
        assertFalse(isEngineObservationLive(engineRunning = true, progress = unknown))
    }

    // The release and the retention cleanup driven through the real engine predicate, at a tip
    // the committed cursor has not reached.

    private fun liveEngineVerifier(walletSyncedHeight: Long): Pair<PendingDirectPaymentVerifier, suspend () -> Unit> {
        val shadow = mockk<de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService>(relaxed = true)
        every { shadow.isEngineRunning() } returns true
        every { shadow.progress } returns kotlinx.coroutines.flow.MutableStateFlow(progress(walletSyncedHeight = walletSyncedHeight))
        val sdkL1 = mockk<de.schildbach.wallet.service.platform.sdk.SdkL1SendService>(relaxed = true)
        coEvery { sdkL1.cutoverCommitted() } returns true
        val db = mockk<org.dashfoundation.dashsdk.persistence.DashDatabase>(relaxed = true)
        // no row: the payment is not in the store yet
        every { db.transactionDao() } returns mockk(relaxed = true) { coEvery { getByTxid(any()) } returns null }
        val sdkService = mockk<de.schildbach.wallet.service.platform.sdk.DashSdkService>(relaxed = true)
        every { sdkService.databaseOrNull() } returns db
        val realEngine = SdkPendingPaymentEngine(sdkL1, sdkService, shadow, mockk(relaxed = true))

        val wipe = slot<suspend () -> Unit>()
        val ownWalletData = mockk<WalletData>(relaxed = true)
        every { ownWalletData.wallet } returns wallet
        every { ownWalletData.attachOnWalletWipedListener(capture(wipe)) } answers { }
        val v = PendingDirectPaymentVerifier(
            ownWalletData, walletApplication, blockchainStateProvider, metadataProvider, config, realEngine
        ).apply { pollIntervalMs = 20L }
        return v to (if (wipe.isCaptured) wipe.captured else ({ }))
    }

    private fun releaseIsWithheldAt(walletSyncedHeight: Long) = runBlocking {
        val (v, stop) = liveEngineVerifier(walletSyncedHeight)
        try {
            v.minAgeMs = 0L
            v.syncedGraceMs = 100L
            goOnlineAndSynced()
            val tx = createTransaction()

            v.quarantine(tx, paymentUrl, "SomeService")
            delay(700)

            coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
            tx.inputs.forEach { assertTrue("inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
        } finally {
            stop()
        }
    }

    private fun retentionCleanupIsWithheldAt(walletSyncedHeight: Long, giftCard: Boolean) = runBlocking {
        val (v, stop) = liveEngineVerifier(walletSyncedHeight)
        try {
            v.recordRetentionMs = 0L
            goOnlineAndSynced()
            val tx = createTransaction()
            coEvery { config.getAllOrThrow() } returns listOf(
                PendingDirectPayment(
                    tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                    System.currentTimeMillis() - 1_000L, isGiftCardPurchase = giftCard, abandoned = true
                )
            )

            withTimeout(5_000) { v.awaitRestored() }
            delay(700)

            coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
            coVerify(exactly = 0) { config.remove(tx.txId) }
            coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.watchExpired }, any()) }
        } finally {
            stop()
        }
    }

    @Test
    fun `no release while the committed cursor is one block short of the tip`() = releaseIsWithheldAt(999L)

    @Test
    fun `no release while the committed cursor is two blocks short of the tip`() = releaseIsWithheldAt(998L)

    @Test
    fun `no retention cleanup while the committed cursor is one block short of the tip`() =
        retentionCleanupIsWithheldAt(999L, giftCard = false)

    @Test
    fun `no retention cleanup while the committed cursor is two blocks short of the tip`() =
        retentionCleanupIsWithheldAt(998L, giftCard = false)

    @Test
    fun `no gift card watch expiry while the committed cursor is one block short of the tip`() =
        retentionCleanupIsWithheldAt(999L, giftCard = true)

    @Test
    fun `no gift card watch expiry while the committed cursor is two blocks short of the tip`() =
        retentionCleanupIsWithheldAt(998L, giftCard = true)

    @Test
    fun `the retention cleanup does run once the committed cursor reaches the tip`() = runBlocking {
        // the control for the tests above: same setup, cursor at the tip
        val (v, stop) = liveEngineVerifier(1_000L)
        try {
            v.recordRetentionMs = 0L
            goOnlineAndSynced()
            val tx = createTransaction()
            coEvery { config.getAllOrThrow() } returns listOf(
                PendingDirectPayment(
                    tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                    System.currentTimeMillis() - 1_000L, abandoned = true
                )
            )
            withTimeout(5_000) { v.awaitRestored() }
            coVerify(timeout = 5_000) { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        } finally {
            stop()
        }
    }

    @Test
    fun `the retention cleanup needs the same connected and synced evidence as the release`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        verifier.recordRetentionMs = 400L
        goOnlineAndSynced()
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "SomeService")
        // released under the full release guard...
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }
        // ...then the wallet loses the network before the retention period ends. The engine row
        // still reads ABSENT, but nothing is watching: the records of what may be a real payment
        // must stay.
        coEvery { blockchainStateProvider.getState() } returns null
        delay(800)

        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
    }

    // --- funding gate for restored payments (review 4245734665) -------------------------------

    @Test
    fun `a payment restored after a restart leaves SDK funding unprotected until it resolves`() = runBlocking {
        val tx = createTransaction()
        // what resume() finds on disk: an active payment, and no SDK reservation in this process
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))

        assertTrue(verifier.hasUnprotectedPendingPayment())

        // resolved (committed or definitively released): the store no longer holds it
        coEvery { config.getAllOrThrow() } returns emptyList()
        assertFalse(verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `a payment quarantined in this process with its reservation held is protected`() = runBlocking {
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))

        assertFalse(verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `a released payment does not block SDK funding`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123", abandoned = true))

        assertFalse(verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `an unreadable store blocks SDK funding`() = runBlocking {
        coEvery { config.getAllOrThrow() } throws IllegalStateException("store unreadable")

        assertTrue(verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `a reservation built long before its quarantine is not trusted (review 4250735551)`() = runBlocking {
        // The preview built and reserved this payment 45 minutes ago and sat open; the quarantine
        // happens only now, on Confirm. The engine's lifetime runs from the reservation, so this
        // one may already be gone, and its inputs exposed, however fresh the quarantine is.
        val tx = createTransaction()
        val oldReservation = de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment(
            txidHex = tx.txId.toString(),
            rawTxBytes = tx.unsafeBitcoinSerialize(),
            feeDuffs = 1_000L,
            native = null,
            reservedAtMs = System.currentTimeMillis() - 45 * 60_000L
        )
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = oldReservation)
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))

        assertTrue(verifier.hasUnprotectedPendingPayment())
    }

    // --- block-based reservation lifetime (review 2a8b1c7c057e) -----------------------------

    private suspend fun protectedWith(reservedAtHeight: Long?, tip: Long?): Boolean {
        val tx = createTransaction()
        every { engine.chainHeightUpperBound() } returns tip
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx, reservedAtHeight))
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))
        return !verifier.hasUnprotectedPendingPayment()
    }

    @Test
    fun `a reservation is trusted only a safe margin short of the engine's block limit`() = runBlocking {
        assertTrue("a few blocks old: still reserved", protectedWith(RESERVED_AT_HEIGHT, RESERVED_AT_HEIGHT + 5))
    }

    @Test
    fun `a reservation that many blocks have passed is not trusted however recent it is`() = runBlocking {
        // 20 blocks can arrive well inside the wall-clock window; the engine refuses or sweeps it
        assertFalse(protectedWith(RESERVED_AT_HEIGHT, RESERVED_AT_HEIGHT + 20))
        assertFalse(protectedWith(RESERVED_AT_HEIGHT, RESERVED_AT_HEIGHT + 16))
    }

    @Test
    fun `an unknown build height or tip fails closed`() = runBlocking {
        assertFalse("no build height", protectedWith(null, RESERVED_AT_HEIGHT))
        assertFalse("no current tip", protectedWith(RESERVED_AT_HEIGHT, null))
    }
}
