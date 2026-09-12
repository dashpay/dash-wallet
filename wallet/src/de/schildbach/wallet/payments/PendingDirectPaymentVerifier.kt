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

import androidx.annotation.VisibleForTesting
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PendingDirectPayment
import de.schildbach.wallet.data.PendingDirectPaymentConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks BIP70 payments whose HTTP submission failed after the request may already have reached
 * the merchant (connection dropped mid-response, read timeout, ...). The merchant may have
 * broadcast the transaction, so it can be neither treated as sent nor as failed.
 *
 * While a payment is pending its inputs are locked in the wallet so a retry cannot build a
 * conflicting transaction. The verifier then watches the network, independently of the UI that
 * started the payment:
 *  - as soon as the transaction is seen (relayed by peers, InstantSend-locked, or mined) it is
 *    committed to the wallet, its inputs are unlocked and it is (re)broadcast;
 *  - if the wallet has been connected and fully synced for a while and the transaction still
 *    has not appeared, the merchant never received it: the inputs are released.
 *
 * Pending payments are persisted so the locks and the verification survive process death;
 * [resume] must be called once the wallet is available (the blockchain service does this).
 */
@Singleton
class PendingDirectPaymentVerifier @Inject constructor(
    private val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val metadataProvider: TransactionMetadataProvider,
    private val config: PendingDirectPaymentConfig
) {
    companion object {
        private val log = LoggerFactory.getLogger(PendingDirectPaymentVerifier::class.java)
        private const val DEFAULT_POLL_INTERVAL_MS = 5_000L
        /** Never declare a payment lost sooner than this after it was submitted. */
        private const val DEFAULT_MIN_AGE_MS = 10 * 60_000L
        /** How long the wallet must be continuously connected and synced without seeing the tx. */
        private const val DEFAULT_SYNCED_GRACE_MS = 5 * 60_000L
        /** A chain tip older than this means we are not really caught up, whatever the sync state says. */
        private const val RECENT_CHAIN_TIP_MS = 30 * 60_000L
    }

    @VisibleForTesting internal var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    @VisibleForTesting internal var minAgeMs = DEFAULT_MIN_AGE_MS
    @VisibleForTesting internal var syncedGraceMs = DEFAULT_SYNCED_GRACE_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Sha256Hash, Deferred<Transaction?>>()
    private val jobsMutex = Mutex()

    /**
     * Locks the inputs of [tx], persists it and starts watching the network for it.
     *
     * @return a deferred that completes with the committed transaction once it is seen on the
     *         network, or with null if it never appears and its inputs were released.
     */
    suspend fun quarantine(tx: Transaction, paymentUrl: String, serviceName: String?): Deferred<Transaction?> {
        val wallet = walletData.wallet ?: throw IllegalStateException("wallet is not available")
        val payment = PendingDirectPayment(
            txId = tx.txId,
            txBytes = tx.bitcoinSerialize(),
            paymentUrl = paymentUrl,
            serviceName = serviceName,
            createdAt = System.currentTimeMillis()
        )
        lockInputs(wallet, tx)
        try {
            config.add(payment)
        } catch (e: Exception) {
            // The locks live in memory only, and without a persisted record resume() could never
            // find them again: they would strand these outputs until the process restarts.
            log.error("could not persist pending direct payment {}, releasing its inputs", tx.txId, e)
            unlockInputs(wallet, tx)
            throw e
        }
        log.info("quarantined possibly-sent tx {} ({} inputs locked)", tx.txId, tx.inputs.size)
        return track(tx, payment)
    }

    /**
     * Re-locks the inputs of persisted pending payments and restarts their verification.
     * Safe to call repeatedly; payments already being tracked are skipped.
     */
    fun resume() {
        scope.launch {
            try {
                val wallet = walletData.wallet ?: walletData.observeWallet().filterNotNull().first()
                val pending = config.getAll()
                if (pending.isEmpty()) {
                    return@launch
                }
                log.info("resuming verification of {} pending direct payment(s)", pending.size)
                for (payment in pending) {
                    try {
                        val tx = Transaction(wallet.params, payment.txBytes)
                        if (!isTracked(tx.txId)) {
                            lockInputs(wallet, tx)
                        }
                        track(tx, payment)
                    } catch (e: Exception) {
                        log.error("could not restore pending direct payment {}, dropping it", payment.txId, e)
                        config.remove(payment.txId)
                    }
                }
            } catch (e: Exception) {
                log.error("failed to resume pending direct payments", e)
            }
        }
    }

    fun isTracked(txId: Sha256Hash): Boolean = jobs[txId]?.isActive == true

    /**
     * True if there is any evidence that the network knows about [tx]: it is in the wallet with a
     * network source, has an InstantSend or ChainLock, or was announced by at least one peer.
     * Requires connected peers to ever become true.
     */
    fun isTransactionOnNetwork(tx: Transaction): Boolean {
        return try {
            val wallet = walletData.wallet ?: return false
            val inWalletTx = wallet.getTransaction(tx.txId)
            val confidence = (inWalletTx ?: tx).confidence ?: return false

            (inWalletTx != null && confidence.source == TransactionConfidence.Source.NETWORK) ||
                confidence.isChainLocked ||
                confidence.isTransactionLocked ||
                confidence.numBroadcastPeers() > 0
        } catch (e: Exception) {
            log.debug("Error checking transaction network status: {}", e.message)
            false
        }
    }

    private suspend fun track(tx: Transaction, payment: PendingDirectPayment): Deferred<Transaction?> =
        jobsMutex.withLock {
            jobs[tx.txId]?.takeIf { it.isActive }
                ?: scope.async { verify(tx, payment) }.also { jobs[tx.txId] = it }
        }

    private suspend fun verify(tx: Transaction, payment: PendingDirectPayment): Transaction? {
        log.info("watching the network for possibly-sent tx {} (submitted to {})", tx.txId, payment.paymentUrl)
        var syncedSince = 0L

        while (true) {
            try {
                if (isTransactionOnNetwork(tx)) {
                    return commit(tx, payment)
                }

                val now = System.currentTimeMillis()
                syncedSince = if (isConnectedAndSynced()) {
                    if (syncedSince == 0L) now else syncedSince
                } else {
                    0L
                }

                if (syncedSince != 0L &&
                    now - syncedSince >= syncedGraceMs &&
                    now - payment.createdAt >= minAgeMs
                ) {
                    release(tx, payment)
                    return null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("error while verifying possibly-sent tx {}", tx.txId, e)
            }
            delay(pollIntervalMs)
        }
    }

    private suspend fun isConnectedAndSynced(): Boolean {
        if (blockchainStateProvider.getNetworkStatus() != NetworkStatus.CONNECTED) {
            return false
        }
        val state = blockchainStateProvider.getState() ?: return false
        val bestChainDate = state.bestChainDate ?: return false
        return state.isSynced() && System.currentTimeMillis() - bestChainDate.time < RECENT_CHAIN_TIP_MS
    }

    private suspend fun commit(tx: Transaction, payment: PendingDirectPayment): Transaction {
        val wallet = walletData.wallet ?: throw IllegalStateException("wallet is not available")
        Context.propagate(wallet.context)

        val existing = wallet.getTransaction(tx.txId)
        if (existing == null) {
            wallet.maybeCommitTx(tx)
            log.info("possibly-sent tx {} was seen on the network, committed to wallet", tx.txId)
        } else {
            log.info("possibly-sent tx {} was seen on the network and is already in the wallet", tx.txId)
        }
        unlockInputs(wallet, tx)

        payment.serviceName?.let { metadataProvider.setTransactionService(tx.txId, it) }
        val walletTx = wallet.getTransaction(tx.txId) ?: tx
        // harmless if the merchant already broadcast it; makes sure the network has it otherwise
        walletApplication.broadcastTransaction(walletTx)
        finish(payment)
        return walletTx
    }

    private suspend fun release(tx: Transaction, payment: PendingDirectPayment) {
        log.warn(
            "possibly-sent tx {} was never seen on the network while connected and synced; " +
                "the merchant did not receive the payment, releasing its inputs",
            tx.txId
        )
        walletData.wallet?.let { unlockInputs(it, tx) }
        // Anything saved optimistically against this tx describes an order that was never placed,
        // and its metadata must not reach Dash Platform either.
        try {
            metadataProvider.forgetTransaction(tx.txId)
        } catch (e: Exception) {
            log.error("could not discard the records of abandoned payment {}", tx.txId, e)
        }
        finish(payment)
    }

    private suspend fun finish(payment: PendingDirectPayment) {
        config.remove(payment.txId)
        jobs.remove(payment.txId)
    }

    private fun lockInputs(wallet: Wallet, tx: Transaction) {
        tx.inputs.forEach { wallet.lockOutput(it.outpoint) }
    }

    private fun unlockInputs(wallet: Wallet, tx: Transaction) {
        tx.inputs.forEach { wallet.unlockOutput(it.outpoint) }
    }
}
