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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        // Behaves like the store by default: what a quarantine or a release writes, later reads
        // see, until it is removed. A release only writes a record that is still stored. Tests
        // that need a particular store stub these again.
        val stored = java.util.concurrent.CopyOnWriteArrayList<PendingDirectPayment>()
        coEvery { config.add(any(), any()) } answers {
            val payment = firstArg<PendingDirectPayment>()
            stored.removeIf { it.txId == payment.txId }
            stored.add(payment)
            Unit
        }
        coEvery { config.remove(any()) } answers {
            val txId = firstArg<Sha256Hash>()
            stored.removeIf { it.txId == txId }
            mockk(relaxed = true)
        }
        coEvery { config.getAllOrThrow() } answers { stored.toList() }

        val wipeListener = slot<suspend () -> Unit>()
        every { walletData.attachOnWalletWipedListener(capture(wipeListener)) } answers { }

        // The dashj regime by default: the SDK neither owns L1 nor has seen anything, and its
        // bridge declines, so commits take the plain maybeCommitTx path.
        engine = mockk(relaxed = true)
        coEvery { engine.sdkOwnsL1() } returns false
        // A readable state: the strict read agrees with whatever a test stubs for sdkOwnsL1.
        coEvery { engine.sdkOwnsL1OrUnknown() } coAnswers { engine.sdkOwnsL1() }
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
        goOnlineAndSynced()
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, null)
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }

        assertNull("tx never seen: must not be committed", wallet.getTransaction(tx.txId))
        tx.inputs.forEach { assertFalse("inputs must be spendable again", wallet.isLockedOutput(it.outpoint)) }
        // released, not resolved: the record stays and so does the watch (review 9fa90c30ccf1)
        coVerify(exactly = 0) { config.remove(tx.txId) }
        assertTrue(verifier.isTracked(tx.txId))
        verify(exactly = 0) { walletApplication.broadcastTransaction(any()) }
    }

    @Test
    fun `removes optimistically saved gift cards once the abandoned payment is invalidated`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }
        // a confirmed transaction now spends one of its inputs: it can never confirm
        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        assertNull(withTimeout(5_000) { result.await() })

        // the order was never placed, so everything recorded for it must go: cards, metadata,
        // and anything queued for Dash Platform
        coVerify { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        coVerify { config.remove(tx.txId) }
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
        goOnlineAndSynced()
        // cleanup deferred, e.g. no wallet available to confirm the transaction is absent
        coEvery { metadataProvider.forgetTransaction(any()) } returns false
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }
        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        delay(300)

        // inputs are freed, but the record survives so the cleanup can be retried
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
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
        goOnlineAndSynced()
        coEvery { metadataProvider.forgetTransaction(any()) } throws RuntimeException("database is gone")
        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend")
        delay(500)

        coVerify(exactly = 0) { config.remove(tx.txId) }
        assertTrue("the cleanup is retried", verifier.isTracked(tx.txId))
    }

    @Test
    fun `resume keeps watching an abandoned payment without locking its inputs`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend",
                System.currentTimeMillis(), abandoned = true
            )
        )

        // awaitRestored, not resume: it returns only once the scan has finished, so the job is
        // registered before anything is asserted
        withTimeout(5_000) { verifier.awaitRestored() }

        // an abandoned payment is never re-locked, only watched until it is seen or invalidated
        assertTrue(verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }

        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        withTimeout(5_000) { while (verifier.isTracked(tx.txId)) delay(10) }
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
    fun `keeps watching an abandoned gift card order until it is seen or invalidated`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        goOnlineAndSynced()
        val tx = createTransaction()

        verifier.quarantine(
            tx,
            paymentUrl,
            "PiggyCards",
            PaymentRecoveryMetadata(isGiftCardPurchase = true, merchantIconUrl = null)
        )
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }
        delay(300)

        // nothing is deleted and the watch goes on: the payee may still broadcast it
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.watchExpired }, any()) }
        assertTrue(verifier.isTracked(tx.txId))
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
    fun `watches an expired-watch record again while its transaction is still absent`() = runBlocking {
        // a record left by the old retention rule: still a valid signed payment, so it is watched
        // like any released one until it is seen or invalidated
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                System.currentTimeMillis(), isGiftCardPurchase = true,
                abandoned = true, watchExpired = true
            )
        )

        withTimeout(5_000) { verifier.awaitRestored() }

        assertTrue(verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
    }

    @Test
    fun `discards an abandoned payment once a confirmed conflicting spend invalidates it`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "SomeService", paymentRequestId = "abc123")
        withTimeout(5_000) {
            while (runCatching {
                    coVerify { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
                }.isFailure
            ) delay(10)
        }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }

        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        assertNull(withTimeout(5_000) { result.await() })

        coVerify { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        coVerify { config.remove(tx.txId) }
    }

    @Test
    fun `cancelling a quarantine frees the inputs and forgets the record`() = runBlocking {
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend")

        verifier.cancelQuarantine(tx, wallet)

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
    fun `a released submission still blocks its invoice`() = runBlocking {
        // Review 9fa90c30ccf1: releasing T1's inputs does not revoke its signatures. A payee that
        // kept T1 could broadcast it alongside any T2 built from other coins and collect twice,
        // so the invoice stays blocked until T1 is seen or a confirmed conflicting spend kills it.
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123", abandoned = true))

        assertEquals(tx.txId, verifier.unresolvedSubmissionFor("abc123"))
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
        // released (abandoned) records still block a new transaction, as for the lookup
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(saved, "abc123", abandoned = true))
        assertEquals(saved.txId, verifier.claimSubmission(createTransaction().txId, "abc123", false))
        // ...but not a resubmission of T1 itself
        assertNull(verifier.claimSubmission(saved.txId, "abc123", false))
    }

    @Test
    fun `an abandoned gift card purchase no longer blocks a different gift card purchase`() = runBlocking {
        // A new purchase is a separate order with its own invoice, not a duplicate payment of the
        // released one (whose late arrival is still attributed to its own order)
        val saved = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            storedPayment(saved, null, abandoned = true).copy(isGiftCardPurchase = true)
        )
        assertNull(verifier.claimSubmission(createTransaction().txId, null, isGiftCardPurchase = true))
    }

    @Test
    fun `an unreleased gift card purchase still blocks another gift card purchase`() = runBlocking {
        val saved = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            storedPayment(saved, null).copy(isGiftCardPurchase = true)
        )
        assertEquals(saved.txId, verifier.claimSubmission(createTransaction().txId, null, isGiftCardPurchase = true))
    }

    @Test
    fun `an abandoned gift card purchase still blocks its own invoice`() = runBlocking {
        // review 9fa90c30ccf1: the same request is never paid with a second transaction
        val saved = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(
            storedPayment(saved, "abc123", abandoned = true).copy(isGiftCardPurchase = true)
        )
        assertEquals(saved.txId, verifier.claimSubmission(createTransaction().txId, "abc123", isGiftCardPurchase = true))
        assertEquals(saved.txId, verifier.unresolvedSubmissionFor("abc123"))
        // a different order (different request) goes through
        assertNull(verifier.claimSubmission(createTransaction().txId, "def456", isGiftCardPurchase = true))
    }

    @Test
    fun `a late broadcast of an abandoned gift card purchase is still attributed to its order`() = runBlocking {
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(
            storedPayment(tx, "abc123", abandoned = true).copy(
                isGiftCardPurchase = true,
                serviceName = "CTXSpend",
                merchantIconUrl = "https://logo.example/x.png"
            )
        )
        withTimeout(5_000) { verifier.awaitRestored() }

        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(10) }

        coVerify { metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), "CTXSpend", "https://logo.example/x.png") }
        assertNotNull(wallet.getTransaction(tx.txId))
    }

    @Test
    fun `retention cleanup keeps an abandoned record that still blocks its invoice`() = runBlocking {
        // Nothing has observed T1 and nothing has spent its inputs elsewhere: it is still a valid
        // signed payment, so its record (the invoice block) outlives the retention period.
        goOnlineAndSynced()
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123", abandoned = true))

        withTimeout(5_000) { verifier.awaitRestored() }
        delay(500)

        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        coVerify(exactly = 0) { config.remove(tx.txId) }
        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.watchExpired }, any()) }
        assertEquals(tx.txId, verifier.unresolvedSubmissionFor("abc123"))
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
    fun `the record keeps the fee the SDK built the payment with, through its release`() = runBlocking {
        // review 4303472428: a resubmission shows this fee; the wallet may not be able to rebuild it
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        assertEquals(1_000L, stored.single().feeDuffs)

        goOnlineAndSynced()
        withTimeout(5_000) { while (!stored.single().abandoned) delay(10) }
        assertEquals("released, the fee stays with the record", 1_000L, stored.single().feeDuffs)
    }

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

        verifier.cancelQuarantine(tx, wallet)

        // the caller knows whether the outcome consumed it or frees it
        coVerify(exactly = 0) { engine.release(any()) }
        coVerify(exactly = 0) { engine.broadcast(any()) }
        assertNull(verifier.detachReservation(tx.txId))
    }

    @Test
    fun `an abandoned payment the engine has since observed is committed, not forgotten`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
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
        val realEngine = SdkPendingPaymentEngine(sdkL1, sdkService, shadow, mockk(relaxed = true), mockk(relaxed = true))

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
    fun `an abandoned record outlives retention even with the committed cursor at the tip`() = runBlocking {
        // Review 9fa90c30ccf1: silence never discards a released payment, however well observed;
        // only its being seen or invalidated does.
        val (v, stop) = liveEngineVerifier(1_000L)
        try {
            goOnlineAndSynced()
            val tx = createTransaction()
            coEvery { config.getAllOrThrow() } returns listOf(
                PendingDirectPayment(
                    tx.txId, tx.bitcoinSerialize(), paymentUrl, "PiggyCards",
                    System.currentTimeMillis() - 1_000L, abandoned = true
                )
            )
            withTimeout(5_000) { v.awaitRestored() }
            delay(500)
            coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
            coVerify(exactly = 0) { config.remove(tx.txId) }
        } finally {
            stop()
        }
    }

    @Test
    fun `the retention cleanup needs the same connected and synced evidence as the release`() = runBlocking {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 50L
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

    // --- a record whose removal fails (review e73b1d7e4d5f) ----------------------------------

    /**
     * The config mock behaving like the store, with removals that fail while [failRemovals] is
     * set: what a quarantine writes, later reads see, until a removal actually lands.
     */
    private fun storeWithFlakyRemovals(): Pair<MutableList<PendingDirectPayment>, java.util.concurrent.atomic.AtomicBoolean> {
        val stored = java.util.concurrent.CopyOnWriteArrayList<PendingDirectPayment>()
        val failRemovals = java.util.concurrent.atomic.AtomicBoolean(false)
        coEvery { config.add(any(), any()) } answers {
            val payment = firstArg<PendingDirectPayment>()
            stored.removeIf { it.txId == payment.txId }
            stored.add(payment)
            Unit
        }
        coEvery { config.remove(any()) } answers {
            if (failRemovals.get()) throw java.io.IOException("storage temporarily unavailable")
            val txId = firstArg<Sha256Hash>()
            stored.removeIf { it.txId == txId }
            mockk(relaxed = true)
        }
        coEvery { config.getAllOrThrow() } answers { stored.toList() }
        return stored to failRemovals
    }

    @Test
    fun `a resolved payment whose record removal fails once is cleaned up without a restart`() = runBlocking {
        verifier.removalRetryInitialMs = 50L
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        assertFalse("held reservation: protected", verifier.hasUnprotectedPendingPayment())

        // acknowledged (or definitively never sent): the outcome is known, but the removal fails
        failRemovals.set(true)
        verifier.cancelQuarantine(tx, wallet)
        assertEquals("the record is still on disk", 1, stored.size)

        // resolved, so it must not block SDK funding while its removal is pending
        assertFalse(verifier.hasUnprotectedPendingPayment())
        assertNull(verifier.unresolvedSubmissionFor("abc123"))

        // storage recovers: the record goes on its own, with no new service or process
        failRemovals.set(false)
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(20) }
        assertFalse(verifier.hasUnprotectedPendingPayment())
        assertFalse("no watch was resurrected", verifier.isTracked(tx.txId))
    }

    @Test
    fun `a scan never resurrects the watch of a resolved payment whose removal is pending`() = runBlocking {
        verifier.removalRetryInitialMs = 60_000L // the retry stays out of the way
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        failRemovals.set(true)
        verifier.cancelQuarantine(tx, wallet)
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }

        verifier.resume()
        delay(300)

        assertEquals(1, stored.size)
        assertFalse("no watch for a resolved payment", verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse("its inputs are not locked again", wallet.isLockedOutput(it.outpoint)) }
        assertFalse(verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `a scan holding a stale snapshot does not watch or lock a payment cancelled since`() = runBlocking {
        // review 4302333212: the scan read T1, then a POST that definitely never left cancelled
        // T1's quarantine, and the removal landed; the scan then went on to lock T1's inputs and
        // start a new watch, whose release would later write the record back
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))

        val snapshotRead = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        verifier.afterRestoreSnapshotRead = {
            snapshotRead.complete(Unit)
            proceed.await()
        }
        verifier.resume()
        withTimeout(5_000) { snapshotRead.await() }

        verifier.cancelQuarantine(tx, wallet)
        assertTrue("the record was removed", stored.isEmpty())

        proceed.complete(Unit)
        withTimeout(5_000) { verifier.awaitRestored() }

        assertFalse("no watch for a cancelled payment", verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse("its inputs are not locked again", wallet.isLockedOutput(it.outpoint)) }
        assertTrue("nothing recreated the record", stored.isEmpty())
    }

    @Test
    fun `a watch releasing a payment cancelled under it does not write the record back`() = runBlocking {
        // the sibling of review 4302333212: the watch was already inside release(), past its last
        // cancellation check, when a POST that definitely never left cancelled the quarantine;
        // its write of the abandoned record then recreated what the cancellation had removed
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        goOnlineAndSynced()
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()

        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        verifier.beforeReleaseWrite = {
            reached.complete(Unit)
            // a write already under way: cancelling the watch does not stop it at this point
            withContext(NonCancellable) { proceed.await() }
            resumed.complete(Unit)
        }
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        withTimeout(5_000) { reached.await() }

        verifier.cancelQuarantine(tx, wallet)
        assertTrue("the record was removed", stored.isEmpty())

        proceed.complete(Unit)
        withTimeout(5_000) { resumed.await() }
        delay(300)

        assertTrue("the release must not write the record back", stored.isEmpty())
        assertFalse(verifier.isTracked(tx.txId))
        coVerify(exactly = 0) { config.add(match { it.txId == tx.txId && it.abandoned }, any()) }
    }

    @Test
    fun `a committed payment whose record removal fails once is cleaned up without a restart`() = runBlocking {
        verifier.removalRetryInitialMs = 50L
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val tx = createTransaction()
        failRemovals.set(true)
        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        val committed = withTimeout(5_000) { result.await() }
        assertNotNull("committed despite the failed removal", committed)
        assertFalse(verifier.hasUnprotectedPendingPayment())

        failRemovals.set(false)
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(20) }
        assertFalse(verifier.isTracked(tx.txId))
    }

    @Test
    fun `a wipe drops pending removals and stops their retry`() = runBlocking {
        verifier.removalRetryInitialMs = 50L
        val (_, failRemovals) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        failRemovals.set(true)
        verifier.cancelQuarantine(tx, wallet)

        onWalletWiped()
        // the wipe cleared the store itself; nothing of the old wallet may be retried after it
        io.mockk.clearMocks(config, answers = false, recordedCalls = true, childMocks = false)
        delay(400)

        coVerify(exactly = 0) { config.remove(any()) }
    }

    // --- the engine's tip bound (review 28775c57d545) ----------------------------------------

    private fun engineWith(progress: ShadowSyncProgress): SdkPendingPaymentEngine {
        val shadow = mockk<de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService>(relaxed = true)
        every { shadow.progress } returns kotlinx.coroutines.flow.MutableStateFlow(progress)
        return SdkPendingPaymentEngine(mockk(relaxed = true), mockk(relaxed = true), shadow, mockk(relaxed = true), mockk(relaxed = true))
    }

    // --- review 4309551133: a stored row is not delivery ------------------------------------

    private fun engineOverRow(
        row: org.dashfoundation.dashsdk.persistence.entities.TransactionEntity?,
        locks: de.schildbach.wallet.database.dao.InstantSendLockDao = mockk(relaxed = true)
    ): SdkPendingPaymentEngine {
        val shadow = mockk<de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService>(relaxed = true)
        every { shadow.isEngineRunning() } returns true
        every { shadow.progress } returns kotlinx.coroutines.flow.MutableStateFlow(progress(walletSyncedHeight = 1_000L))
        val db = mockk<org.dashfoundation.dashsdk.persistence.DashDatabase>(relaxed = true)
        every { db.transactionDao() } returns mockk(relaxed = true) { coEvery { getByTxid(any()) } returns row }
        val sdkService = mockk<de.schildbach.wallet.service.platform.sdk.DashSdkService>(relaxed = true)
        every { sdkService.databaseOrNull() } returns db
        return SdkPendingPaymentEngine(mockk(relaxed = true), sdkService, shadow, mockk(relaxed = true), locks)
    }

    private fun row(context: Int, blockHeight: Int) =
        org.dashfoundation.dashsdk.persistence.entities.TransactionEntity(
            txid = ByteArray(32) { 1 },
            transactionData = ByteArray(0),
            context = context,
            blockHeight = blockHeight
        )

    @Test
    fun `a stored row with no InstantSend lock and no block is not delivery`() = runBlocking {
        // the engine records what it broadcast itself; that row proves nothing reached the network
        assertEquals(EngineObservation.ABSENT, engineOverRow(row(context = 0, blockHeight = 0)).observation("01".repeat(32)))
    }

    @Test
    fun `an InstantSend-locked, chain-locked or mined row is delivery`() = runBlocking {
        val txid = "01".repeat(32)
        assertEquals(EngineObservation.OBSERVED, engineOverRow(row(context = 1, blockHeight = 0)).observation(txid))
        assertEquals(EngineObservation.OBSERVED, engineOverRow(row(context = 3, blockHeight = 1_000)).observation(txid))
        assertEquals(EngineObservation.OBSERVED, engineOverRow(row(context = 2, blockHeight = 999)).observation(txid))
        assertEquals(EngineObservation.ABSENT, engineOverRow(null).observation(txid))
    }

    // --- review 4309551218: fresh SDK funding and a reactivation never overlap ----------------

    @Test
    fun `a reactivation during an in-flight funding is refused at once, the payment untouched`() = runBlocking {
        // the funding (a registration) may run for minutes: the reactivation must not wait for it
        // while the resubmission holds its claim; it is refused, and the user retries later
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(PendingDirectPayment(tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend", 0L, abandoned = true))
        val lock = FundingSelectionLock()
        val wipe = slot<suspend () -> Unit>()
        val ownWalletData = mockk<WalletData>(relaxed = true)
        every { ownWalletData.wallet } returns wallet
        every { ownWalletData.attachOnWalletWipedListener(capture(wipe)) } answers { }
        val shared = PendingDirectPaymentVerifier(
            ownWalletData, walletApplication, blockchainStateProvider, metadataProvider, config, engine, lock
        ).apply { pollIntervalMs = 20L }

        val funding = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val registration = async(Dispatchers.Default) {
            lock.withShared {
                funding.complete(Unit)
                done.await() // a long-running funding, past its gate check
            }
        }
        funding.await()

        val outcome = withTimeout(2_000) { shared.reactivateForResubmission(tx, wallet) }

        assertEquals(PendingDirectPaymentVerifier.Reactivation.FUNDING_IN_PROGRESS, outcome)
        assertTrue("untouched", stored.single().abandoned)
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }

        done.complete(Unit)
        registration.await()
        assertEquals(PendingDirectPaymentVerifier.Reactivation.REACTIVATED, shared.reactivateForResubmission(tx, wallet))
        assertFalse(stored.single().abandoned)
        if (wipe.isCaptured) wipe.captured()
    }

    @Test
    fun `an InstantSend lock in the app's table is delivery even without a block`() = runBlocking {
        // the SDK never writes context = 1 on Android; the app records received IS locks itself
        val txid = "01".repeat(32)
        val locks = mockk<de.schildbach.wallet.database.dao.InstantSendLockDao> {
            coEvery { getLockedTxIds(listOf(txid)) } returns listOf(txid)
        }
        assertEquals(
            EngineObservation.OBSERVED,
            engineOverRow(row(context = 0, blockHeight = 0), locks).observation(txid)
        )
    }

    @Test
    fun `the chain height upper bound is the best known tip, header target included`() {
        // headers still catching up to a target the network already reached
        val catchingUp = progress(walletSyncedHeight = 990L).copy(headerHeight = 995L, filterHeight = 990L)
        assertEquals(1_000L, engineWith(catchingUp).chainHeightUpperBound())
    }

    @Test
    fun `an unknown tip gives no upper bound, whatever the committed cursor says`() {
        // the committed cursor is a lower bound on processing, never a bound on the tip
        val noTip = progress(walletSyncedHeight = 990L).copy(headerHeight = 0L, headerTarget = 0L, filterHeight = 0L)
        assertNull(engineWith(noTip).chainHeightUpperBound())
    }

    // --- an abandoned invoice stays blocked until T1 is seen or invalidated (9fa90c30ccf1) ---

    @Test
    fun `an abandoned payment that is then observed is resolved as paid and unblocks its invoice`() = runBlocking {
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(storedPayment(tx, "abc123", abandoned = true))
        assertEquals(tx.txId, verifier.unresolvedSubmissionFor("abc123"))

        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED
        withTimeout(5_000) { verifier.awaitRestored() }
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(10) }

        assertNotNull("committed as paid", wallet.getTransaction(tx.txId))
        assertNull(verifier.unresolvedSubmissionFor("abc123"))
    }

    @Test
    fun `a confirmed conflicting spend of an input unblocks the invoice`() = runBlocking {
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(storedPayment(tx, "abc123", abandoned = true))
        withTimeout(5_000) { verifier.awaitRestored() }
        delay(200)
        assertEquals("still blocked while T1 is valid", tx.txId, verifier.unresolvedSubmissionFor("abc123"))

        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(10) }

        assertNull(verifier.unresolvedSubmissionFor("abc123"))
        assertNull(verifier.claimSubmission(createTransaction().txId, "abc123", false))
        assertNull("never committed", wallet.getTransaction(tx.txId))
    }

    @Test
    fun `the abandoned submission of an invoice is offered for resubmission, an active one is not`() = runBlocking {
        val tx = createTransaction()
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123", abandoned = true))
        assertEquals(tx.txId, verifier.abandonedSubmissionFor("abc123", wallet)?.txId)

        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(tx, "abc123"))
        assertNull(verifier.abandonedSubmissionFor("abc123", wallet))
    }

    // the SDK engine's view of a conflicting spend

    private fun engineWithTxo(
        txo: org.dashfoundation.dashsdk.persistence.entities.TxoEntity?,
        spenderHeight: Int?,
        spenderContext: Int = 2, // inBlock
        chainLockHeight: Int = 0,
        headerHeight: Long = 0L
    ): SdkPendingPaymentEngine {
        val db = mockk<org.dashfoundation.dashsdk.persistence.DashDatabase>(relaxed = true)
        every { db.txoDao() } returns mockk(relaxed = true) { coEvery { getByOutpoint(any()) } returns txo }
        every { db.transactionDao() } returns mockk(relaxed = true) {
            coEvery { getByTxid(any()) } returns spenderHeight?.let { h ->
                mockk<org.dashfoundation.dashsdk.persistence.entities.TransactionEntity> {
                    every { blockHeight } returns h
                    every { context } returns spenderContext
                }
            }
        }
        val sdkService = mockk<de.schildbach.wallet.service.platform.sdk.DashSdkService>(relaxed = true)
        every { sdkService.databaseOrNull() } returns db
        val shadow = mockk<de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService>(relaxed = true)
        every { shadow.chainLockHeight } returns kotlinx.coroutines.flow.MutableStateFlow(chainLockHeight)
        every { shadow.progress } returns kotlinx.coroutines.flow.MutableStateFlow(
            progress(walletSyncedHeight = headerHeight).copy(headerHeight = headerHeight, headerTarget = headerHeight)
        )
        return SdkPendingPaymentEngine(mockk(relaxed = true), sdkService, shadow, mockk(relaxed = true), mockk(relaxed = true))
    }

    private fun spentTxo(spendingTxid: ByteArray) =
        mockk<org.dashfoundation.dashsdk.persistence.entities.TxoEntity> {
            every { isSpent } returns true
            every { this@mockk.spendingTxid } returns spendingTxid
        }

    @Test
    fun `the SDK engine counts a conflicting spend only once it is final`() = runBlocking {
        // 4297952515: inclusion in a block is not finality; a shallow reorg can take the spend out
        // and make T1 valid again after its records are gone. Same rule as ChainLockedCoinSelector.
        val tx = createTransaction()
        val other = ByteArray(32) { 9 }

        assertEquals(
            "in a chainlocked block", true,
            engineWithTxo(spentTxo(other), 1_000, spenderContext = 3).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "at or below the chainlock height", true,
            engineWithTxo(spentTxo(other), 1_000, chainLockHeight = 1_000, headerHeight = 1_001).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "above the chainlock height: unknown, not final", false,
            engineWithTxo(spentTxo(other), 1_000, chainLockHeight = 999, headerHeight = 1_100).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "no chainlock known, six deep", true,
            engineWithTxo(spentTxo(other), 1_000, headerHeight = 1_005).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "no chainlock known, only five deep", false,
            engineWithTxo(spentTxo(other), 1_000, headerHeight = 1_004).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "no chainlock and no known tip", false,
            engineWithTxo(spentTxo(other), 1_000).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "unconfirmed conflict", false,
            engineWithTxo(spentTxo(other), 0, headerHeight = 1_100).inputSpentByFinalConflict(tx)
        )
        assertEquals(
            "spent by T1 itself", false,
            engineWithTxo(spentTxo(tx.txId.reversedBytes), 1_000, spenderContext = 3).inputSpentByFinalConflict(tx)
        )
        assertEquals("unknown output", false, engineWithTxo(null, null).inputSpentByFinalConflict(tx))
    }

    /**
     * A wallet holding [parent], whose output 0 is spent by [spender]; T1 spends the same output.
     */
    private fun walletWithConflict(): Triple<Transaction, Transaction, Transaction> {
        val key = org.bitcoinj.core.ECKey()
        wallet.importKey(key)
        val parent = Transaction(params).apply {
            addInput(Sha256Hash.of("funding-${txCounter.incrementAndGet()}".toByteArray()), 0, ScriptBuilder.createEmpty())
            addOutput(Coin.parseCoin("1.0"), Address.fromKey(params, key))
        }
        val spender = Transaction(params).apply {
            addInput(parent.getOutput(0))
            addOutput(Coin.parseCoin("0.9"), Address.fromString(params, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"))
        }
        val t1 = Transaction(params).apply {
            addInput(parent.getOutput(0))
            addOutput(Coin.parseCoin("0.8"), Address.fromString(params, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"))
        }
        parent.getOutput(0).markAsSpent(spender.getInput(0))
        wallet.addWalletTransaction(
            org.bitcoinj.wallet.WalletTransaction(org.bitcoinj.wallet.WalletTransaction.Pool.SPENT, parent)
        )
        return Triple(parent, spender, t1)
    }

    private fun abandonedRecordOf(t1: Transaction) {
        coEvery { config.getAllOrThrow() } returns listOf(storedPayment(t1, "abc123", abandoned = true))
    }

    @Test
    fun `a shallow confirmed conflicting spend in the dashj wallet does not discard T1`() = runBlocking {
        val (_, spender, t1) = walletWithConflict()
        spender.confidence.confidenceType = org.bitcoinj.core.TransactionConfidence.ConfidenceType.BUILDING
        spender.confidence.appearedAtChainHeight = 99_998
        // tip 100_000: three deep, no chainlock known
        coEvery { blockchainStateProvider.getState() } returns BlockchainState(
            Date(), 100_000, false, EnumSet.noneOf(BlockchainState.Impediment::class.java), 0, 0, 100
        )
        abandonedRecordOf(t1)

        withTimeout(5_000) { verifier.awaitRestored() }
        delay(400)

        coVerify(exactly = 0) { metadataProvider.forgetTransaction(any()) }
        assertTrue("watched until the spend is final", verifier.isTracked(t1.txId))
    }

    @Test
    fun `a chainlocked conflicting spend in the dashj wallet discards T1`() = runBlocking {
        val (_, spender, t1) = walletWithConflict()
        spender.confidence.confidenceType = org.bitcoinj.core.TransactionConfidence.ConfidenceType.BUILDING
        spender.confidence.appearedAtChainHeight = 99_998
        // a chainlock at the tip locks everything below it
        coEvery { blockchainStateProvider.getState() } returns BlockchainState(
            Date(), 100_000, false, EnumSet.noneOf(BlockchainState.Impediment::class.java), 100_000, 0, 100
        )
        abandonedRecordOf(t1)

        withTimeout(5_000) { verifier.awaitRestored() }
        withTimeout(5_000) { while (verifier.isTracked(t1.txId)) delay(10) }

        coVerify { metadataProvider.forgetTransaction(t1.txId.toTxId()) }
        coVerify { config.remove(t1.txId) }
    }

    // --- completing an acknowledged resubmission (round 10) -----------------------------------

    @Test
    fun `an acknowledgement that arrives after the watch settled the payment returns it as paid`() = runBlocking {
        // 2f5ba48cac7c: the payee broadcast T1 and the watch committed it and removed its record
        // while the resubmission's POST was still waiting for the ACK
        val tx = createTransaction()
        wallet.maybeCommitTx(tx)
        coEvery { config.getAllOrThrow() } returns emptyList()

        val settled = verifier.commitAcknowledged(tx, wallet)

        assertEquals(tx.txId, settled.txId)
    }

    @Test
    fun `a failed finalisation of an acknowledged resubmission keeps the watch, which finishes it later`() = runBlocking {
        // 85487eabbf1b: a transient metadata failure must not drop the only background retry
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(
            storedPayment(tx, "abc123", abandoned = true).copy(isGiftCardPurchase = true, serviceName = "CTXSpend")
        )
        withTimeout(5_000) { verifier.awaitRestored() }
        var attempts = 0
        coEvery { metadataProvider.markGiftCardTransaction(any(), any(), any()) } answers {
            if (++attempts == 1) throw java.io.IOException("metadata store unavailable")
        }

        try {
            verifier.commitAcknowledged(tx, wallet)
            fail("a failed finalisation was reported as done")
        } catch (e: org.dash.wallet.common.services.PaymentSubmissionPendingException) {
            assertEquals(tx.txId.toString(), e.txId)
        }
        assertEquals("the record stays", 1, stored.size)
        assertTrue("the watch stays", verifier.isTracked(tx.txId))

        // storage recovers and the network shows T1: the watch finishes the job
        coEvery { engine.observation(tx.txId.toString()) } returns EngineObservation.OBSERVED
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(10) }
        assertTrue(attempts >= 2)
    }

    @Test
    fun `an acknowledged resubmission is not completed while the SDK owns L1 and nothing relays it`() = runBlocking {
        // 3fc3cd86956b: the released reservation is gone and the dashj peers are held, so nothing
        // here can put T1 on the network; it stays pending and watched until the network shows it
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        stored.add(storedPayment(tx, "abc123", abandoned = true))
        coEvery { engine.sdkOwnsL1() } returns true

        try {
            verifier.commitAcknowledged(tx, wallet)
            fail("an unrelayed payment was reported as paid")
        } catch (e: org.dash.wallet.common.services.PaymentSubmissionPendingException) {
            assertEquals(tx.txId.toString(), e.txId)
        }
        assertEquals(1, stored.size)
        assertNull(wallet.getTransaction(tx.txId))
        assertTrue(verifier.isTracked(tx.txId))
    }

    @Test
    fun `a queued removal never deletes the record of a payment quarantined again since`() = runBlocking {
        // review 4302741459: the retry worker took its list of settled ids, then the same
        // transaction was quarantined again (a definitively unsent payment rebuilt with the same
        // txid); the worker went on to delete the new, active record
        verifier.removalRetryInitialMs = 20L
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))

        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        verifier.afterRemovalSnapshot = { txId ->
            if (txId == tx.txId) {
                reached.complete(Unit)
                proceed.await()
            }
        }
        // settled, but its removal fails: queued for the worker
        failRemovals.set(true)
        verifier.cancelQuarantine(tx, wallet)
        failRemovals.set(false)
        withTimeout(5_000) { reached.await() }

        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        proceed.complete(Unit)
        delay(300)

        assertEquals("the new quarantine's record survives", listOf(tx.txId), stored.map { it.txId })
        assertFalse(stored.single().abandoned)
        assertTrue(verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertTrue("its inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `a failed re-quarantine keeps the queued removal of the earlier record`() = runBlocking {
        // review 4303092964: the re-quarantine took the id out of the removal queue before its own
        // write; that write failed, so the earlier settled record stayed on disk with nothing left
        // to remove it, blocking SDK funding and the invoice after storage recovered
        verifier.removalRetryInitialMs = 50L
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val failAdds = java.util.concurrent.atomic.AtomicBoolean(false)
        coEvery { config.add(any(), any()) } answers {
            if (failAdds.get()) throw java.io.IOException("storage temporarily unavailable")
            val payment = firstArg<PendingDirectPayment>()
            stored.removeIf { it.txId == payment.txId }
            stored.add(payment)
            Unit
        }
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx), paymentRequestId = "abc123")

        // definitively unsent, but the removal fails: the record stays, queued for removal
        failRemovals.set(true)
        verifier.cancelQuarantine(tx, wallet)
        assertEquals(1, stored.size)

        // the same transaction, rebuilt, cannot be recorded
        failAdds.set(true)
        try {
            verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx), paymentRequestId = "abc123")
            fail("a quarantine that could not be recorded must not go ahead")
        } catch (e: java.io.IOException) {
            // expected
        }

        // storage recovers: the earlier record is still cleaned up, with no new scan
        failAdds.set(false)
        failRemovals.set(false)
        withTimeout(5_000) { while (stored.isNotEmpty()) delay(20) }
        assertFalse(verifier.hasUnprotectedPendingPayment())
        assertNull(verifier.unresolvedSubmissionFor("abc123"))
        assertFalse(verifier.isTracked(tx.txId))
        tx.inputs.forEach { assertFalse(wallet.isLockedOutput(it.outpoint)) }
    }

    @Test
    fun `a release never undoes the protection of a reactivation that overtook it`() = runBlocking {
        // review 4308598488: release() wrote abandoned under the record lock but unlocked the
        // inputs and dropped the reservation after giving it back; a resubmission's reactivation
        // in between relocked T1, and the release's tail then unlocked it again
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))

        val reached = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        verifier.afterReleaseLocked = {
            if (!reached.isCompleted) {
                reached.complete(Unit)
                // the release is past its lock and must not be stopped by the reactivation's cancel
                withContext(NonCancellable) { proceed.await() }
            }
        }
        goOnlineAndSynced()
        withTimeout(5_000) { reached.await() }
        assertTrue("released", stored.single().abandoned)

        // a resubmission of T1 overtakes the release; its new watch gets a real grace period
        verifier.syncedGraceMs = 60_000L
        assertEquals(
            PendingDirectPaymentVerifier.Reactivation.REACTIVATED,
            verifier.reactivateForResubmission(tx, wallet)
        )
        proceed.complete(Unit)
        delay(300)

        assertFalse("active", stored.single().abandoned)
        tx.inputs.forEach { assertTrue("its inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
        assertTrue("fresh SDK funding is still blocked", verifier.hasUnprotectedPendingPayment())
    }

    @Test
    fun `a release decided on an older record never overwrites or unlocks the record written since`() = runBlocking {
        // the release decided on the record it was watching, then took the record lock and wrote
        // that snapshot as abandoned and unlocked, checking only that a record still existed. A
        // step that rewrote the record active in between (here a quarantine of the same
        // transaction, which keeps the running watch rather than cancelling it) was undone.
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        val decidedOn = stored.single()

        val superseded = CompletableDeferred<PendingDirectPayment>()
        verifier.beforeReleaseWrite = {
            if (!superseded.isCompleted) {
                // the watch has decided to release; the record is rewritten active before its write
                verifier.syncedGraceMs = 60_000L
                verifier.quarantine(tx, paymentUrl, "CTXSpend")
                superseded.complete(stored.single())
            }
        }
        goOnlineAndSynced()
        val current = withTimeout(5_000) { superseded.await() }
        assertTrue("a new write of the record", current.revision != decidedOn.revision)
        delay(300)

        assertEquals("the release left the newer record as it is", current, stored.single())
        assertFalse(stored.single().abandoned)
        tx.inputs.forEach { assertTrue("its inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
        assertTrue("still watched", verifier.isTracked(tx.txId))
        coVerify(exactly = 0) { engine.release(any()) }
    }

    @Test
    fun `a resubmission reactivates a released payment durably, and survives a restart`() = runBlocking {
        // review 4307041830: posted again, T1 is protected exactly like a fresh quarantine
        val (stored, _) = storeWithFlakyRemovals()
        val tx = createTransaction()
        val releasedAt = System.currentTimeMillis() - 60 * 60_000L
        stored.add(
            PendingDirectPayment(
                tx.txId, tx.bitcoinSerialize(), paymentUrl, "CTXSpend", releasedAt,
                abandoned = true
            )
        )
        assertFalse("released: not counted by the funding gate", verifier.hasUnprotectedPendingPayment())

        assertEquals(
            PendingDirectPaymentVerifier.Reactivation.REACTIVATED,
            verifier.reactivateForResubmission(tx, wallet)
        )

        val active = stored.single()
        // a stale second attempt finds it active and leaves it exactly as it is (review 4307815170)
        assertEquals(
            PendingDirectPaymentVerifier.Reactivation.ALREADY_ACTIVE,
            verifier.reactivateForResubmission(tx, wallet)
        )
        assertEquals(active, stored.single())
        assertFalse(active.abandoned)
        assertFalse(active.watchExpired)
        assertTrue("its release grace starts over", active.createdAt > releasedAt)
        tx.inputs.forEach { assertTrue("its inputs are locked again", wallet.isLockedOutput(it.outpoint)) }
        assertTrue("no SDK reservation can be made again: fresh SDK funding is blocked", verifier.hasUnprotectedPendingPayment())
        assertTrue(verifier.isTracked(tx.txId))

        // a process death: the locks lived in memory, the record on disk
        tx.inputs.forEach { wallet.unlockOutput(it.outpoint) }
        val restarted = PendingDirectPaymentVerifier(
            walletData, walletApplication, blockchainStateProvider, metadataProvider, config, engine
        ).apply { pollIntervalMs = 20L }
        withTimeout(5_000) { restarted.awaitRestored() }
        tx.inputs.forEach { assertTrue("restored as an active payment", wallet.isLockedOutput(it.outpoint)) }
        assertTrue(restarted.isTracked(tx.txId))

        // and had the request never left, the payment goes back to released
        verifier.releaseUnsentResubmission(tx, wallet)
        assertTrue(stored.single().abandoned)
    }

    @Test
    fun `a removal that fails while the retry worker is retiring is still retried`() = runBlocking {
        // r4296870284: the worker found the queue empty and was about to exit, still active, when
        // another payment's removal failed; scheduling saw the active worker and started none, so
        // the new id was never retried
        verifier.removalRetryInitialMs = 20L
        val (stored, failRemovals) = storeWithFlakyRemovals()
        val first = createTransaction()
        val second = createTransaction()
        verifier.quarantine(first, paymentUrl, "CTXSpend", reservation = reservationFor(first))
        verifier.quarantine(second, paymentUrl, "CTXSpend", reservation = reservationFor(second))

        var interleaved = false
        verifier.afterRemovalQueueDrained = {
            if (!interleaved) {
                interleaved = true
                // in the retiring worker's window: the second payment settles and its removal fails
                failRemovals.set(true)
                verifier.cancelQuarantine(second, wallet)
                failRemovals.set(false)
            }
        }

        failRemovals.set(true)
        verifier.cancelQuarantine(first, wallet)
        failRemovals.set(false)

        withTimeout(5_000) { while (stored.isNotEmpty()) delay(10) }
        assertTrue(interleaved)
    }

    // --- invalidation settles under the reactivation boundary, only for the record it judged ---

    /** Released and watched, with [tx]'s first invalidation check answering yes, later ones no. */
    private suspend fun releasedAndThenInvalidatedOnce(tx: Transaction): MutableList<PendingDirectPayment> {
        verifier.minAgeMs = 0L
        verifier.syncedGraceMs = 100L
        val (stored, _) = storeWithFlakyRemovals()
        verifier.quarantine(tx, paymentUrl, "CTXSpend", reservation = reservationFor(tx))
        goOnlineAndSynced()
        withTimeout(5_000) { while (!stored.single().abandoned) delay(10) }
        // no further releases while the test looks
        verifier.syncedGraceMs = 60_000L
        val asked = AtomicInteger(0)
        coEvery { engine.inputSpentByFinalConflict(any()) } answers { asked.getAndIncrement() == 0 }
        return stored
    }

    @Test
    fun `an invalidation judged before a reactivation never undoes the reactivation`() = runBlocking {
        val tx = createTransaction()
        val reactivated = CompletableDeferred<PendingDirectPaymentVerifier.Reactivation>()
        verifier.beforeInvalidationSettled = {
            if (!reactivated.isCompleted) {
                // a resubmission reactivates T1 after the watch judged it invalidated
                val outcome = async(Dispatchers.Default) { verifier.reactivateForResubmission(tx, wallet) }
                withContext(NonCancellable) { reactivated.complete(outcome.await()) }
            }
        }
        val stored = releasedAndThenInvalidatedOnce(tx)

        assertEquals(PendingDirectPaymentVerifier.Reactivation.REACTIVATED, withTimeout(5_000) { reactivated.await() })
        delay(300)

        assertFalse("still active", stored.single().abandoned)
        tx.inputs.forEach { assertTrue("its inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        assertTrue("watched", verifier.isTracked(tx.txId))
    }

    @Test
    fun `an invalidation decided on an older record never discards the record written since`() = runBlocking {
        val tx = createTransaction()
        val rewritten = CompletableDeferred<PendingDirectPayment>()
        lateinit var stored: MutableList<PendingDirectPayment>
        verifier.beforeInvalidationSettled = {
            if (!rewritten.isCompleted) {
                // a quarantine of the same transaction rewrites it active and keeps this watch
                verifier.quarantine(tx, paymentUrl, "CTXSpend")
                rewritten.complete(stored.single())
            }
        }
        stored = releasedAndThenInvalidatedOnce(tx)

        val current = withTimeout(5_000) { rewritten.await() }
        delay(300)

        assertEquals("left exactly as written", current, stored.single())
        tx.inputs.forEach { assertTrue("its inputs stay locked", wallet.isLockedOutput(it.outpoint)) }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        assertTrue("still watched", verifier.isTracked(tx.txId))
    }

    // --- the wipe barrier: a wipe waits for funding under way and refuses funding that waited ---

    @Test
    fun `a wipe waits for an in-flight funding, and refuses one that waited through it`() = runBlocking {
        val lock = FundingSelectionLock()
        val wipe = slot<suspend () -> Unit>()
        val ownWalletData = mockk<WalletData>(relaxed = true)
        every { ownWalletData.wallet } returns wallet
        every { ownWalletData.attachOnWalletWipedListener(capture(wipe)) } answers { }
        PendingDirectPaymentVerifier(
            ownWalletData, walletApplication, blockchainStateProvider, metadataProvider, config, engine, lock
        ).apply { pollIntervalMs = 20L }

        // a funding of the old wallet is selecting its inputs
        val selecting = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val inFlight = async(Dispatchers.Default) {
            lock.withShared {
                selecting.complete(Unit)
                finish.await()
                "selected"
            }
        }
        withTimeout(2_000) { selecting.await() }

        val wiping = async(Dispatchers.Default) { wipe.captured() }
        delay(200)
        assertFalse("the wipe cleared the payment state under a funding still selecting", wiping.isCompleted)

        // a funding asked for while the wipe runs waits, and must not go on afterwards
        val late = async(Dispatchers.Default) { runCatching { lock.withShared { "selected" } } }
        delay(100)
        assertFalse(late.isCompleted)

        finish.complete(Unit)
        assertEquals("selected", withTimeout(2_000) { inFlight.await() })
        withTimeout(5_000) { wiping.await() }
        val refused = withTimeout(2_000) { late.await() }
        assertTrue("a funding that waited through the wipe went on: $refused", refused.exceptionOrNull() is WalletWipedException)

        // the next wallet's funding is not held up
        assertEquals("selected", withTimeout(2_000) { lock.withShared { "selected" } })
    }

    @Test
    fun `a wipe never deadlocks on a watch waiting to settle an invalidation`() = runBlocking {
        // the wipe holds the lock exclusively and joins the watches: one waiting for a shared hold
        // must give up when cancelled rather than keep the wipe waiting for ever
        val lock = FundingSelectionLock()
        val wipe = slot<suspend () -> Unit>()
        val ownWalletData = mockk<WalletData>(relaxed = true)
        every { ownWalletData.wallet } returns wallet
        every { ownWalletData.attachOnWalletWipedListener(capture(wipe)) } answers { }
        val own = PendingDirectPaymentVerifier(
            ownWalletData, walletApplication, blockchainStateProvider, metadataProvider, config, engine, lock
        ).apply { pollIntervalMs = 20L }
        val tx = createTransaction()
        own.quarantine(tx, paymentUrl, "CTXSpend")
        // a funding holds the lock, so the watch's settlement has to wait for it
        val finish = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val funding = async(Dispatchers.Default) { lock.withShared { holding.complete(Unit); finish.await() } }
        withTimeout(2_000) { holding.await() }
        // the wipe takes the lock and waits for the funding ...
        val wiping = async(Dispatchers.Default) { wipe.captured() }
        delay(100)
        // ... while the watch judges the payment invalidated and waits for a shared hold
        coEvery { engine.inputSpentByFinalConflict(any()) } returns true
        delay(200)
        finish.complete(Unit)
        funding.await()
        // well inside the wipe's own drain timeout: the watch gave up when cancelled
        withTimeout(2_000) { wiping.await() }
        coVerify(exactly = 0) { metadataProvider.forgetTransaction(tx.txId.toTxId()) }
        assertFalse(own.isTracked(tx.txId))
    }
}
