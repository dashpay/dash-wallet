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

import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PendingDirectPayment
import de.schildbach.wallet.data.PendingDirectPaymentConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    }

    private val params = TestNet3Params.get()
    private val paymentUrl = "https://merchant.example/payment"

    private lateinit var wallet: Wallet
    private lateinit var walletData: WalletDataProvider
    private lateinit var walletApplication: WalletApplication
    private lateinit var blockchainStateProvider: BlockchainStateProvider
    private lateinit var metadataProvider: TransactionMetadataProvider
    private lateinit var config: PendingDirectPaymentConfig
    private lateinit var verifier: PendingDirectPaymentVerifier

    @Before
    fun setUp() {
        Context.propagate(Context(params))
        wallet = Wallet.createBasic(params)

        walletData = mockk(relaxed = true)
        every { walletData.wallet } returns wallet
        walletApplication = mockk(relaxed = true)
        blockchainStateProvider = mockk(relaxed = true)
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.DISCONNECTED
        coEvery { blockchainStateProvider.getState() } returns null
        metadataProvider = mockk(relaxed = true)
        config = mockk(relaxed = true)
        coEvery { config.getAll() } returns emptyList()

        verifier = PendingDirectPaymentVerifier(
            walletData, walletApplication, blockchainStateProvider, metadataProvider, config
        ).apply {
            pollIntervalMs = 20L
        }
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
        coVerify { config.add(match { it.txId == tx.txId && it.paymentUrl == paymentUrl && it.serviceName == "CTXSpend" }) }
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
        coVerify { metadataProvider.setTransactionService(tx.txId, "CTXSpend") }
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
        goOnlineAndSynced()
        val tx = createTransaction()

        val result = verifier.quarantine(tx, paymentUrl, "CTXSpend")
        withTimeout(5_000) { result.await() }

        // the order was never placed, so everything recorded for it must go: cards, metadata,
        // and anything queued for Dash Platform
        coVerify { metadataProvider.forgetTransaction(tx.txId) }
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
        coEvery { config.getAll() } returns listOf(
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
    fun `resume drops payments that cannot be parsed`() = runBlocking {
        val txId = Sha256Hash.of(byteArrayOf(9))
        coEvery { config.getAll() } returns listOf(
            PendingDirectPayment(txId, byteArrayOf(0, 1), paymentUrl, null, System.currentTimeMillis())
        )

        verifier.resume()

        withTimeout(5_000) {
            coVerify(timeout = 5_000) { config.remove(txId) }
        }
        assertFalse(verifier.isTracked(txId))
    }
}
