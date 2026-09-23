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
import org.dash.wallet.common.services.PaymentRecoveryMetadata
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
        /**
         * How long a released payment is still watched before its records are discarded.
         *
         * Releasing does not prove the payment never happened: the payee chooses when to relay
         * the transaction, so one that withholds it until the grace period expires can broadcast
         * afterwards. Keep watching, and keep the order, well beyond that point.
         */
        private const val DEFAULT_RECORD_RETENTION_MS = 24 * 60 * 60_000L
    }

    @VisibleForTesting internal var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    @VisibleForTesting internal var minAgeMs = DEFAULT_MIN_AGE_MS
    @VisibleForTesting internal var syncedGraceMs = DEFAULT_SYNCED_GRACE_MS
    @VisibleForTesting internal var recordRetentionMs = DEFAULT_RECORD_RETENTION_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Sha256Hash, Deferred<Transaction?>>()
    private val jobsMutex = Mutex()

    /**
     * Locks the inputs of [tx], persists it and starts watching the network for it.
     *
     * @return a deferred that completes with the committed transaction once it is seen on the
     *         network, or with null if it never appears and its inputs were released.
     */
    suspend fun quarantine(
        tx: Transaction,
        paymentUrl: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null
    ): Deferred<Transaction?> {
        val wallet = walletData.wallet ?: throw IllegalStateException("wallet is not available")
        val payment = PendingDirectPayment(
            txId = tx.txId,
            txBytes = tx.bitcoinSerialize(),
            paymentUrl = paymentUrl,
            serviceName = serviceName,
            createdAt = System.currentTimeMillis(),
            isGiftCardPurchase = recovery?.isGiftCardPurchase ?: false,
            merchantIconUrl = recovery?.merchantIconUrl
        )
        lockInputs(wallet, tx)
        try {
            config.add(payment)
        } catch (e: Exception) {
            // Keep the locks and keep verifying. By this point the merchant may already hold the
            // signed transaction, so the inputs must not become spendable and the caller must not
            // be told the payment failed: it would offer a retry and could pay twice. Losing the
            // record costs durability across process death, which is the lesser harm, so carry on
            // verifying in memory.
            log.error(
                "could not persist pending direct payment {}, continuing to verify it in memory only",
                tx.txId, e
            )
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
                        // An abandoned payment keeps its watch but not its locks: the inputs were
                        // already released, and re-locking outputs the user may since have spent
                        // would be wrong. The watch continues so a late broadcast is still caught.
                        if (!payment.abandoned && !isTracked(tx.txId)) {
                            lockInputs(wallet, tx)
                        }
                        track(tx, payment)
                    } catch (e: Exception) {
                        // Keep it. Failing to deserialize, lock or track says nothing about
                        // whether the merchant received the payment, and dropping the record
                        // destroys the only information a later attempt could recover it from.
                        log.error(
                            "could not restore pending direct payment {}, keeping it to retry",
                            payment.txId, e
                        )
                    }
                }
            } catch (e: Exception) {
                log.error("failed to resume pending direct payments", e)
            }
        }
    }

    fun isTracked(txId: Sha256Hash): Boolean = jobs[txId]?.isActive == true

    /**
     * Undoes a quarantine once the payment's outcome is known for certain, whether acknowledged
     * or definitively refused. Stops the watch, frees the inputs and forgets the record, leaving
     * nothing behind for a restart to resume.
     *
     * Only for an outcome that is actually certain: an unknown one must stay quarantined.
     */
    suspend fun cancelQuarantine(tx: Transaction) {
        jobs.remove(tx.txId)?.cancel()
        walletData.wallet?.let { unlockInputs(it, tx) }
        try {
            config.remove(tx.txId)
        } catch (e: Exception) {
            log.error("could not remove the pending record of resolved payment {}", tx.txId, e)
        }
        log.info("quarantine cancelled for {}, its outcome is known", tx.txId)
    }

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
        var current = payment
        var syncedSince = 0L

        while (true) {
            try {
                if (isTransactionOnNetwork(tx)) {
                    // Also the late-broadcast case: a released payment is still watched, so a
                    // payee that held the transaction back cannot leave us with a paid order
                    // whose records were thrown away.
                    return commit(tx, current)
                }

                val now = System.currentTimeMillis()
                syncedSince = if (isConnectedAndSynced()) {
                    if (syncedSince == 0L) now else syncedSince
                } else {
                    0L
                }

                if (!current.abandoned) {
                    if (syncedSince != 0L &&
                        now - syncedSince >= syncedGraceMs &&
                        now - current.createdAt >= minAgeMs
                    ) {
                        // Frees the inputs, but the order stays and so does this watch: silence
                        // for the grace period is not proof the payment never reached the payee.
                        current = release(tx, current)
                    }
                } else if (now - current.createdAt >= recordRetentionMs) {
                    discardRecords(current)
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
        // Peers, not just NetworkStatus. The status only leaves CONNECTED by way of
        // DISCONNECTING, so losing P2P while the device keeps internet leaves it reading
        // CONNECTED with no peers, and the cached chain tip stays acceptable for another half
        // hour. Without this the grace period could expire having seen no network at all, and a
        // payment the merchant did broadcast would be declared dead and its order deleted.
        if (blockchainStateProvider.getConnectedPeerCount() <= 0) {
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

        // Restore the metadata the purchase screen would have written had it seen the payment
        // succeed. It could not: marking a gift card transaction inserts metadata that requires
        // the transaction to be in the wallet, and it was not until the commit just above. Only
        // a payment recorded as a gift card purchase gets this; an ordinary BIP70 payment must
        // not be marked as one.
        if (payment.isGiftCardPurchase && payment.serviceName != null) {
            metadataProvider.markGiftCardTransaction(tx.txId, payment.serviceName, payment.merchantIconUrl)
        } else {
            val recordedService = metadataProvider.getTransactionMetadata(tx.txId)?.service
            if (recordedService.isNullOrEmpty()) {
                payment.serviceName?.let { metadataProvider.setTransactionService(tx.txId, it) }
            } else {
                log.info("keeping the service already recorded for {}: {}", tx.txId, recordedService)
            }
        }
        val walletTx = wallet.getTransaction(tx.txId) ?: tx
        // harmless if the merchant already broadcast it; makes sure the network has it otherwise
        walletApplication.broadcastTransaction(walletTx)
        finish(payment)
        return walletTx
    }

    /**
     * Frees the inputs of a payment that has gone unseen for the whole grace period, and records
     * that. The order is deliberately kept and the watch continues: the payee decides when to
     * relay the transaction, so one that withholds it could otherwise have the records deleted
     * and then broadcast.
     *
     * @return the payment as now stored, marked abandoned
     */
    private suspend fun release(tx: Transaction, payment: PendingDirectPayment): PendingDirectPayment {
        log.warn(
            "possibly-sent tx {} has not been seen on the network while connected and synced; " +
                "releasing its inputs but keeping the order and watching for a late broadcast",
            tx.txId
        )
        // Record the release durably before anything else. Until this lands, the payment must
        // stay exactly as it is: inputs locked and the record still active, so verify() retries
        // and resume() picks it up unchanged. Unlocking first and then failing to persist would
        // leave a stored payment claiming to be live while its inputs are already free and may
        // have been respent, and the next resume() would lock them again and re-verify it.
        val abandoned = payment.copy(abandoned = true)
        try {
            config.add(abandoned)
        } catch (e: Exception) {
            log.error("could not record the release of {}, keeping it locked and pending", tx.txId, e)
            throw e
        }

        // Past this point the stored payment says abandoned, so a restart will not lock these
        // inputs again, only carry on watching.
        walletData.wallet?.let { unlockInputs(it, tx) }
        return abandoned
    }

    /**
     * Removes everything saved against a payment that was never sent. The payment is only dropped
     * once that succeeds: discarding it earlier would leave gift cards, metadata or queued
     * platform changes behind with nothing left to retry them.
     */
    private suspend fun discardRecords(payment: PendingDirectPayment) {
        // The transaction can arrive between the last network check and this call. forgetTransaction
        // then reports settled without deleting anything, because the wallet holds it, and finishing
        // here would lose the gift card metadata commit() restores. Look again first.
        walletData.wallet?.getTransaction(payment.txId)?.let { walletTx ->
            log.info("abandoned payment {} turned out to be real after all, committing it", payment.txId)
            commit(walletTx, payment)
            return
        }

        if (payment.isGiftCardPurchase) {
            // Stop watching, but keep the order. A payee chooses when to relay, so it can outlast
            // any horizon we pick and broadcast afterwards; deleting the rows would leave a paid
            // purchase with nothing to redeem against. An order for a payment that truly never
            // happened is merely stale, which is the far cheaper mistake.
            log.warn(
                "giving up watching {} after the retention period, but keeping its gift card order",
                payment.txId
            )
            finish(payment)
            return
        }

        val settled = try {
            metadataProvider.forgetTransaction(payment.txId)
        } catch (e: Exception) {
            log.error("could not discard the records of abandoned payment {}", payment.txId, e)
            false
        }

        if (settled) {
            finish(payment)
        } else {
            log.warn(
                "records of abandoned payment {} are still present, keeping it to retry the cleanup",
                payment.txId
            )
            jobs.remove(payment.txId)
        }
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
