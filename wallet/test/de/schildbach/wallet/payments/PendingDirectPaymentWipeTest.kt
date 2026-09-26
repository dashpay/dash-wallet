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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PendingDirectPayment
import de.schildbach.wallet.data.PendingDirectPaymentConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A wipe against the real pending-payment store, with both real wipe listeners.
 *
 * The verifier's own tests mock the config, so they cannot see the one thing that matters here:
 * whether a write the wipe raced against is still on disk afterwards, and what the next recovery
 * scan does with it. A record that survives a wipe is picked up by that scan and, once the
 * transaction shows up on the network, committed to and rebroadcast from the replacement wallet,
 * which never made it.
 */
@RunWith(RobolectricTestRunner::class)
// a plain Application: this needs only a Context for DataStore, and booting the real
// WalletApplication would drag in Hilt and Firebase
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class PendingDirectPaymentWipeTest {
    private val params = TestNet3Params.get()
    private val paymentUrl = "https://merchant.example/payment"

    // deterministic, so each has a watching key and the two have different ones
    private lateinit var oldWallet: Wallet
    private lateinit var replacement: Wallet
    @Volatile private var installed: Wallet? = null

    private lateinit var walletData: WalletDataProvider
    private lateinit var blockchainStateProvider: BlockchainStateProvider
    private lateinit var config: HeldWriteConfig
    private lateinit var verifier: PendingDirectPaymentVerifier

    /** In registration order, as WalletApplication keeps them. */
    private val wipeListeners = mutableListOf<suspend () -> Unit>()

    @Before
    fun setUp() {
        Context.propagate(Context(params))
        oldWallet = Wallet.createDeterministic(params, Script.ScriptType.P2PKH)
        replacement = Wallet.createDeterministic(params, Script.ScriptType.P2PKH)
        installed = oldWallet

        walletData = mockk(relaxed = true)
        every { walletData.wallet } answers { installed }
        every { walletData.attachOnWalletWipedListener(any()) } answers { wipeListeners.add(firstArg()) }

        blockchainStateProvider = mockk(relaxed = true)
        every { blockchainStateProvider.getNetworkStatus() } returns NetworkStatus.DISCONNECTED
        every { blockchainStateProvider.getConnectedPeerCount() } returns 0
        coEvery { blockchainStateProvider.getState() } returns null

        // the real store, which registers BaseConfig's own clearAll() as the first wipe listener
        config = HeldWriteConfig(ApplicationProvider.getApplicationContext<Application>(), walletData)
        runBlocking { config.clearAll() }

        verifier = PendingDirectPaymentVerifier(
            walletData,
            mockk<WalletApplication>(relaxed = true),
            blockchainStateProvider,
            mockk<TransactionMetadataProvider>(relaxed = true),
            config
        ).apply {
            // the watches must not act during a test; nothing here is ever on the network
            pollIntervalMs = 60_000L
        }
        assertEquals("both the store and the verifier listen for wipes", 2, wipeListeners.size)
    }

    /**
     * The real store, able to hold one write back until something else has run.
     *
     * A subclass rather than a MockK spy: callOriginal() from coAnswers does not resume properly
     * once the original really suspends, which DataStore's edit does, so a spy reports the write
     * as done without it ever having run, and the test passes or fails for the wrong reason.
     */
    class HeldWriteConfig(context: android.content.Context, walletData: WalletDataProvider) :
        PendingDirectPaymentConfig(context, walletData) {
        var beforeNextWrite: (suspend () -> Unit)? = null
        var heldWrites = 0

        override suspend fun add(payment: PendingDirectPayment, stillValid: () -> Boolean) {
            beforeNextWrite?.let {
                beforeNextWrite = null
                heldWrites++
                it()
            }
            super.add(payment, stillValid)
        }
    }

    /**
     * What WalletApplication.finalizeWipe() does: every listener in turn, each to completion,
     * while the old wallet is still installed, and only then the wallet goes.
     */
    private suspend fun wipe(listenersReversed: Boolean, installAfterwards: Wallet? = replacement) {
        val order = if (listenersReversed) wipeListeners.reversed() else wipeListeners.toList()
        order.forEach { it() }
        installed = installAfterwards
    }

    private fun createTransaction(): Transaction {
        val tx = Transaction(params)
        tx.addInput(Sha256Hash.of("wipe-${System.nanoTime()}".toByteArray()), 0, ScriptBuilder.createEmpty())
        tx.addOutput(Coin.parseCoin("0.01"), Address.fromString(params, "yWdXnYxGbouNoo8yMvcbZmZ3Gdp6BpySxL"))
        return tx
    }

    private fun identityOf(wallet: Wallet) = Sha256Hash.of(wallet.watchingKey.pubKey).toString()

    /** The scan a restart, or the next payment, runs; must find nothing and adopt nothing. */
    private suspend fun assertReplacementAdoptsNothing(tx: Transaction) {
        withTimeout(5_000) { verifier.awaitRestored() }
        assertEquals("the erased payment is back on disk", emptyList<PendingDirectPayment>(), config.getAllOrThrow())
        assertFalse("the replacement wallet is watching a payment it never made", verifier.isTracked(tx.txId))
        tx.inputs.forEach {
            assertFalse("the replacement wallet locked an outpoint it does not own", replacement.isLockedOutput(it.outpoint))
        }
    }

    // --- a quarantine write held back until the wipe has cleared the store ------------------------

    private fun aWriteHeldAcrossAWipeIsNotRecreated(listenersReversed: Boolean) = runBlocking {
        val tx = createTransaction()
        // Given: the write has passed every check made before it and is about to land when the
        // wipe runs to completion and a replacement wallet is installed
        config.beforeNextWrite = { wipe(listenersReversed) }

        // When
        try {
            verifier.quarantine(tx, paymentUrl, "CTXSpend", origin = oldWallet)
            fail("a payment was quarantined for a wallet that was wiped while its record was being written")
        } catch (e: IllegalStateException) {
            // Expected: refused where it would have landed, so nothing is submitted for it either
        }

        // Then: without this the test could pass by refusing before the write was ever reached
        assertEquals("the write was never held across the wipe", 1, config.heldWrites)
        // nothing is left for the next scan, and the scan adopts nothing
        assertReplacementAdoptsNothing(tx)
    }

    @Test
    fun `a quarantine write held across a wipe is not recreated, store listener first`() =
        aWriteHeldAcrossAWipeIsNotRecreated(listenersReversed = false)

    @Test
    fun `a quarantine write held across a wipe is not recreated, verifier listener first`() =
        aWriteHeldAcrossAWipeIsNotRecreated(listenersReversed = true)

    // --- a quarantine that starts after the listeners ran, while the old wallet is still installed

    @Test
    fun `a quarantine begun during the wipe is refused even though the old wallet is still installed`() = runBlocking {
        val tx = createTransaction()
        // Given: the listeners have run but finalizeWipe() has not yet taken the wallet away, so
        // "is it the installed wallet" still says yes
        wipe(listenersReversed = false, installAfterwards = oldWallet)

        // When
        try {
            verifier.quarantine(tx, paymentUrl, "CTXSpend", origin = oldWallet)
            fail("a payment was quarantined for a wallet a wipe had already started on")
        } catch (e: IllegalStateException) {
            // Expected
        }

        // Then: the wipe has already cleared the store, so a record written now would outlive it
        assertEquals(emptyList<PendingDirectPayment>(), config.getAllOrThrow())
        installed = replacement
        assertReplacementAdoptsNothing(tx)
    }

    // --- a record another wallet left behind, however it got there ---------------------------------

    @Test
    fun `a recovery scan does not adopt a payment another wallet left behind`() = runBlocking {
        // Given: a record stamped by the wiped wallet is on disk - a clear that failed, say - and
        // the replacement is the installed wallet
        val tx = createTransaction()
        installed = replacement
        config.add(
            PendingDirectPayment(
                txId = tx.txId,
                txBytes = tx.bitcoinSerialize(),
                paymentUrl = paymentUrl,
                serviceName = "CTXSpend",
                createdAt = System.currentTimeMillis(),
                walletId = identityOf(oldWallet)
            )
        )

        // Then: the scan neither locks nor watches it, and drops it, since no wallet that could
        // use it is left
        assertReplacementAdoptsNothing(tx)
    }

    // --- the durable half only works if the owner is actually written down -----------------------

    @Test
    fun `a quarantined payment records which wallet signed it`() = runBlocking {
        val tx = createTransaction()

        verifier.quarantine(tx, paymentUrl, "CTXSpend", origin = oldWallet)

        // Without this the scan's refusal above has nothing to go on for any real record: it
        // would be tested against a record the test wrote, and never see one production wrote.
        assertEquals(identityOf(oldWallet), config.getAllOrThrow().single { it.txId == tx.txId }.walletId)
    }

}
