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
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.service.platform.sdk.SdkDeferredPayment
import de.schildbach.wallet.util.toTxId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * On the Kotlin SDK branch BIP70 payments are built by the SDK with their inputs reserved in the
 * engine ([SdkDeferredPayment]), and once the cutover commits the dashj peer group is held. The
 * dashj wallet object stays the wallet of record, so the locks below are the same app-side mirror
 * of the engine reservation that SendCoinsTaskRunner already keeps, and a commit still lands in
 * it. What dashj can no longer see is supplied by [engine]: whether the SDK engine has observed
 * the transaction, and whether the dashj peer count means anything. While a payment is watched in
 * this process its engine reservation is held here as well, so it is consumed by the commit or
 * given back by the release. The engine has no API to reserve an outpoint again, so after process
 * death only the dashj-side locks are restored: those protect dashj spenders, not SDK sends.
 */
@Singleton
class PendingDirectPaymentVerifier @Inject constructor(
    private val walletData: WalletData,
    private val walletApplication: WalletApplication,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val metadataProvider: TransactionMetadataProvider,
    private val config: PendingDirectPaymentConfig,
    private val engine: PendingPaymentEngine
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
        /** How long a payment flow waits for restoration before refusing to proceed. */
        private const val DEFAULT_RESTORE_TIMEOUT_MS = 30_000L
        /**
         * How long a reservation held in this process is trusted to keep its inputs out of SDK coin
         * selection. The engine sweeps reservations after 24 blocks and refuses to broadcast one
         * older than 20 (about 50 minutes); this stays under both.
         */
        private const val RESERVATION_TRUST_MS = 40 * 60_000L

        /**
         * How many blocks a reservation is trusted for: a margin of 4 under the engine's 20-block
         * broadcast limit (and its 24-block sweep).
         */
        private const val RESERVATION_TRUST_BLOCKS = 16L
        /** How long a wipe waits for running verifications to stop before going ahead anyway. */
        private const val DRAIN_TIMEOUT_MS = 5_000L

        private const val REQUEST_CLAIM_PREFIX = "request:"
        private const val GIFT_CARD_CLAIM = "gift-card"
    }

    @VisibleForTesting internal var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    @VisibleForTesting internal var minAgeMs = DEFAULT_MIN_AGE_MS
    @VisibleForTesting internal var syncedGraceMs = DEFAULT_SYNCED_GRACE_MS
    @VisibleForTesting internal var recordRetentionMs = DEFAULT_RECORD_RETENTION_MS
    @VisibleForTesting internal var restoreTimeoutMs = DEFAULT_RESTORE_TIMEOUT_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()

    /** Set once a scan has protected every persisted payment; cleared until then. */
    @Volatile private var locksRestored = false
    private val jobs = ConcurrentHashMap<Sha256Hash, Deferred<Transaction?>>()

    /**
     * SDK engine reservations of payments watched in this process. Holding the object keeps the
     * engine from reclaiming the inputs through its GC backstop while the outcome is unknown; the
     * commit consumes it and the release gives it back.
     */
    private val reservations = ConcurrentHashMap<Sha256Hash, SdkDeferredPayment>()

    /**
     * Wallets a wipe has started on. Listeners run while the wiped wallet is still installed, so
     * "is it still the installed wallet" keeps answering yes for the rest of the wipe; anything
     * that checked only that could still act for it after its records were cleared. Weak, so a
     * retired wallet is not kept alive by being remembered here.
     */
    private val retiredWallets: MutableSet<Wallet> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val jobsMutex = Mutex()

    /**
     * Submissions between their claim and their quarantine, keyed by what they must not be
     * duplicated by: `request:<payment request hash>` for an invoice, [GIFT_CARD_CLAIM] for a gift
     * card purchase. The saved record answers the same question once it exists; this answers it
     * in the window before, which is a suspension long (the order is recorded, a refund address
     * fetched) and which separate screens - each with its own view model - can all be inside at
     * once. Checked and written under [claimMutex] only. In memory and per submission: released
     * when the submission reaches its quarantine or fails before it, and dropped by a wipe.
     */
    private val submissionClaims = ConcurrentHashMap<String, Sha256Hash>()
    private val claimMutex = Mutex()

    init {
        // A pending payment belongs to the wallet that made it, but this is a singleton: its
        // scope, its jobs and its restored flag all outlive a wipe, which replaces the wallet
        // without ending the process. A job still watching for the old transaction would find a
        // brand new wallet under it and commit, attribute and rebroadcast a payment that has
        // nothing to do with it.
        walletData.attachOnWalletWipedListener { forgetWalletState() }
    }

    /**
     * Drops everything held on behalf of the wallet being wiped.
     *
     * Jobs are drained, not merely cancelled, because cancellation is a request: one already
     * inside commit() would otherwise carry on writing records for a wallet that no longer
     * exists. The store is cleared here as well, even though [PendingDirectPaymentConfig] wipes
     * itself through [org.dash.wallet.common.data.BaseConfig], because wipe listeners run in
     * registration order and this one has no claim to being first. Doing it after the drain
     * leaves the same end state whichever way round they run.
     */
    private suspend fun forgetWalletState() {
        // First, before anything can yield: from here on every write made for this wallet is
        // refused where it would land, so the clear below cannot be undone by one already under
        // way. See PendingDirectPaymentConfig.add.
        walletData.wallet?.let { retiredWallets.add(it) }
        val running = jobs.values.toList()
        jobs.clear()
        // The wiped wallet's engine reservations go with it; nothing will consume or release them.
        reservations.clear()
        // Its in-flight submissions are refused by their own ownership checks; their claims must
        // not hold up the replacement wallet's payments in the meantime.
        submissionClaims.clear()
        running.forEach { it.cancel() }
        // Bounded: a job wedged in a call that does not answer cancellation must not hold up the
        // wipe, and the captured-wallet checks below mean a straggler still cannot touch the
        // replacement wallet.
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) { running.forEach { runCatching { it.join() } } }
        locksRestored = false
        try {
            config.clearAll()
        } catch (e: Exception) {
            log.error("could not clear pending payments during wipe", e)
        }
        log.info("wallet wiped; dropped {} pending payment watch(es) and reset readiness", running.size)
    }

    /**
     * Locks the inputs of [tx], persists it and starts watching the network for it.
     *
     * @param reservation the SDK engine reservation [tx] was built under, when there is one. Held
     *   from here on: the commit consumes it, the release gives it back. Not taken over when the
     *   quarantine fails, so the caller still owns it then.
     * @return a deferred that completes with the committed transaction once it is seen on the
     *         network, or with null if it never appears and its inputs were released.
     */
    suspend fun quarantine(
        tx: Transaction,
        paymentUrl: String,
        serviceName: String?,
        recovery: PaymentRecoveryMetadata? = null,
        paymentRequestId: String? = null,
        origin: Wallet? = null,
        reservation: SdkDeferredPayment? = null
    ): Deferred<Transaction?> {
        // The caller passes the wallet it actually signed against. Reading the installed one here
        // would be a second, later read: a wipe between signing and this call would bind the
        // recovery record, its locks and its watch to the replacement instead of to the wallet
        // whose outpoints they describe.
        val wallet = origin ?: walletData.wallet ?: throw IllegalStateException("wallet is not available")
        if (!stillOwnedBy(wallet)) {
            throw IllegalStateException("the wallet that signed ${tx.txId} has been wiped")
        }
        val payment = PendingDirectPayment(
            txId = tx.txId,
            txBytes = tx.bitcoinSerialize(),
            paymentUrl = paymentUrl,
            paymentRequestId = paymentRequestId,
            serviceName = serviceName,
            createdAt = System.currentTimeMillis(),
            isGiftCardPurchase = recovery?.isGiftCardPurchase ?: false,
            merchantIconUrl = recovery?.merchantIconUrl,
            walletId = walletIdentity(wallet)
        )
        lockInputs(wallet, tx)
        try {
            config.add(payment) { stillOwnedBy(wallet) }
        } catch (e: Exception) {
            // Callers quarantine before submitting, so nothing has been sent yet and aborting
            // costs nothing. Carrying on without a record would be the expensive choice: the
            // locks live only in memory, so a process death during submission or the wait would
            // leave resume() with nothing to restore and the inputs of an uncertain payment free
            // to fund a retry.
            log.error("could not persist pending direct payment {}, releasing its inputs", tx.txId, e)
            unlockInputs(wallet, tx)
            throw e
        }
        reservation?.let { reservations[tx.txId] = it }
        log.info("quarantined possibly-sent tx {} ({} inputs locked)", tx.txId, tx.inputs.size)
        return track(tx, payment, wallet)
    }

    /**
     * Re-locks the inputs of persisted pending payments and restarts their verification.
     * Safe to call repeatedly; payments already being tracked are skipped.
     */
    fun resume() {
        scope.launch { scan() }
    }

    /**
     * Suspends until persisted quarantines have been restored and their inputs locked again.
     *
     * Payment flows must pass through this before selecting coins. After process death the locks
     * are gone, since they live only in memory, while the wallet is available to the UI
     * immediately and restoration runs off a service start that may lag behind it. Without the
     * gate a new payment could spend an outpoint reserved by an uncertain one whose signed
     * transaction the payee still holds.
     *
     * Starts restoration itself if nothing has yet, so it does not depend on the service.
     *
     * @throws IllegalStateException if restoration does not finish in time, or if the wallet that
     *   was installed when the caller arrived was wiped while it waited. Refusing to build a
     *   payment we cannot protect is the safer failure.
     */
    suspend fun awaitRestored() {
        if (locksRestored) {
            return
        }
        // The wallet the caller is about to pay from. Callers read the installed wallet again as
        // soon as this returns, and this can wait for a long time, so a wipe that lands meanwhile
        // would have them select coins from and sign with the replacement, for a payment the
        // user confirmed on the wallet that was wiped. Null means nothing is installed yet, which
        // is app start: that caller is waiting for the wallet, not holding one.
        val waitingFor = walletData.wallet
        // Readiness means a scan that actually protected everything, not merely one that ran.
        // Swallowing a failed read here and carrying on would leave an uncertain payment's
        // outpoints spendable, which is what the barrier exists to prevent. A failure leaves the
        // flag clear, so the next payment retries rather than being locked out for good.
        val protectedNow = withTimeoutOrNull(restoreTimeoutMs) { scan() }
        if (protectedNow != true) {
            throw IllegalStateException(
                "pending payments could not be restored; refusing to risk spending their inputs"
            )
        }
        if (waitingFor != null && !stillOwnedBy(waitingFor)) {
            throw IllegalStateException("the wallet was wiped while this payment waited for restoration")
        }
    }

    /**
     * Restores every persisted quarantine and applies anything owed to records whose watch has
     * expired. Repeatable on purpose: an expired record whose transaction had not yet arrived is
     * left in place, and only a later scan can attribute it once ordinary syncing finds it.
     *
     * @return true when every unresolved payment is accounted for
     */
    private suspend fun scan(): Boolean = scanMutex.withLock {
        try {
            restorePersisted()
            locksRestored = true
            true
        } catch (e: Exception) {
            log.error("could not restore pending direct payments", e)
            false
        }
    }

    private suspend fun restorePersisted() {
        var failed = false
        run restore@{
            run {
                val wallet = walletData.wallet ?: walletData.observeWallet().filterNotNull().first()
                // Strict read: the ordinary one turns an unreadable store into empty preferences
                // and silently drops entries it cannot decode, either of which would report every
                // outpoint as free to spend.
                val pending = config.getAllOrThrow()
                if (pending.isEmpty()) {
                    return@restore
                }
                log.info("resuming verification of {} pending direct payment(s)", pending.size)
                val walletId = walletIdentity(wallet)
                for (payment in pending) {
                    if (payment.walletId != null && payment.walletId != walletId) {
                        // Left by a wallet that has since been wiped: a write that was already on
                        // its way when the wipe cleared the store, or a clear that failed. Its
                        // outpoints are not this wallet's, so there is nothing here to protect,
                        // and binding it here is the harm - a watch on this wallet would commit and
                        // rebroadcast a transaction this wallet never made. Dropped, not kept:
                        // no wallet that could use it is left.
                        log.warn("discarding pending payment {}, it belongs to a wallet that was wiped", payment.txId)
                        try {
                            config.remove(payment.txId)
                        } catch (e: Exception) {
                            log.error("could not discard {}; it will be skipped again next time", payment.txId, e)
                        }
                        continue
                    }
                    try {
                        val tx = Transaction(wallet.params, payment.txBytes)

                        if (payment.watchExpired) {
                            // Polling stopped long ago. If ordinary syncing has since found the
                            // transaction, attribute it and let the record go; otherwise leave it
                            // untouched for the next start to look again.
                            val arrived = wallet.getTransaction(tx.txId)
                                ?: tx.takeIf { engineObservation(tx.txId) == EngineObservation.OBSERVED }
                            arrived?.let {
                                log.info("transaction {} arrived after its watch expired", tx.txId)
                                commit(it, payment, wallet)
                            }
                            continue
                        }

                        // An abandoned payment keeps its watch but not its locks: the inputs were
                        // already released, and re-locking outputs the user may since have spent
                        // would be wrong. The watch continues so a late broadcast is still caught.
                        if (!payment.abandoned && !isTracked(tx.txId)) {
                            lockInputs(wallet, tx)
                        }
                        track(tx, payment, wallet)
                    } catch (e: Exception) {
                        // Keep the record. Failing to deserialize, lock or track says nothing
                        // about whether the merchant received the payment, and dropping it
                        // destroys the only information a later attempt could recover it from.
                        log.error(
                            "could not restore pending direct payment {}, keeping it to retry",
                            payment.txId, e
                        )
                        // ... but do not call this scan a success: an unrestored payment means
                        // unprotected outpoints, and spending must stay blocked until a later
                        // scan manages it.
                        failed = true
                    }
                }
            }
        }

        if (failed) {
            throw IllegalStateException("some pending direct payments could not be restored")
        }
    }

    fun isTracked(txId: Sha256Hash): Boolean = jobs[txId]?.isActive == true

    /**
     * Whether [held] can still be trusted to keep its inputs out of the engine's coin selection.
     * The engine ages a reservation in blocks from the height it was made at (broadcast refused
     * after 20, swept after 24), and blocks can arrive far faster than any wall-clock window, so
     * the block test decides; the wall-clock limit only caps it. Both heights are bounds that
     * over-estimate the age, and an unknown one fails closed. The SDK offers no way to ask
     * whether a reservation is still live.
     */
    private fun isReservationStillHeld(held: SdkDeferredPayment?, now: Long, tip: Long?): Boolean {
        held ?: return false
        if (now - held.reservedAtMs >= RESERVATION_TRUST_MS) return false
        val reservedAt = held.reservedAtHeight ?: return false
        tip ?: return false
        return tip - reservedAt < RESERVATION_TRUST_BLOCKS
    }

    /**
     * True while a payment whose outcome is unknown has inputs the SDK engine is not keeping out of
     * coin selection, so that an SDK-funded build could spend them and conflict with the signed
     * transaction the payee may still broadcast.
     *
     * That is any active (not released, not watch-expired) payment of this wallet whose engine
     * reservation is not held in this process: after a restart the engine has lost it, and the
     * engine offers no API to reserve an outpoint again, while the dashj-side locks restored by
     * [resume] are invisible to its coin selection. A reservation made more than
     * [RESERVATION_TRUST_MS] ago no longer counts either, because the engine's own sweep frees it
     * after about an hour; its age is taken from [SdkDeferredPayment.reservedAtMs], so a preview
     * left open before Confirm is not mistaken for a fresh one. A store that cannot be read counts as unprotected.
     *
     * Narrow on purpose: it lifts the moment the payment is committed or released.
     */
    suspend fun hasUnprotectedPendingPayment(): Boolean {
        val pending = try {
            config.getAllOrThrow()
        } catch (e: Exception) {
            log.warn("could not read pending payments; treating SDK funding as unprotected", e)
            return true
        }
        val walletId = walletData.wallet?.let { walletIdentity(it) }
        val now = System.currentTimeMillis()
        val tip = try {
            engine.chainHeightUpperBound()
        } catch (e: Exception) {
            null
        }
        return pending.any { payment ->
            !payment.abandoned && !payment.watchExpired &&
                (payment.walletId == null || walletId == null || payment.walletId == walletId) &&
                !isReservationStillHeld(reservations[payment.txId], now, tip)
        }
    }

    /**
     * Hands the SDK reservation of [txId] back to the caller, which is about to consume it itself
     * (an acknowledged payment is broadcast by the submission path). The quarantine stays as it
     * is; only the engine object leaves, so nothing here broadcasts or releases it a second time.
     */
    fun detachReservation(txId: Sha256Hash): SdkDeferredPayment? = reservations.remove(txId)

    /**
     * The transaction of a submission for [paymentRequestId] whose outcome is still unknown, or
     * null if this invoice has nothing outstanding.
     *
     * Restoring input locks is not enough to stop a second payment for the same invoice: it stops
     * the new transaction reusing those outpoints, and a wallet with other funds simply builds one
     * from different ones, leaving two payments the payee can both broadcast. A screen that has
     * lost its own state - process death, or the invoice opened again - has nothing else to ask.
     *
     * An abandoned payment does not count. Its inputs have been released because the wallet judged
     * it never sent, and refusing to let the user pay after that would strand them on an invoice
     * they never actually paid.
     */
    suspend fun unresolvedSubmissionFor(paymentRequestId: String?): Sha256Hash? {
        if (paymentRequestId == null) {
            return null
        }
        return config.getAllOrThrow()
            .firstOrNull { it.paymentRequestId == paymentRequestId && !it.abandoned }
            ?.txId
    }

    /**
     * Claims the right to submit [txId] as the one payment for its invoice ([paymentRequestId])
     * and, for a gift card purchase, as the one unresolved gift card purchase. Atomic across the
     * app: of two submissions racing for the same thing, exactly one is let through.
     *
     * Returns null when the claim was taken, or the transaction it conflicts with: another
     * submission's claim still on its way to quarantine, or a saved record whose outcome is still
     * unknown. Nothing to key on (no invoice, not a gift card) takes no claim and returns null.
     *
     * A claim must be given back with [releaseSubmissionClaim] once the submission has saved its
     * quarantine (the record blocks duplicates from then on) or failed before it.
     *
     * @throws Exception if the store cannot be read: an unread store is not an empty one.
     */
    suspend fun claimSubmission(
        txId: Sha256Hash,
        paymentRequestId: String?,
        isGiftCardPurchase: Boolean
    ): Sha256Hash? = claimMutex.withLock {
        val keys = buildList {
            paymentRequestId?.let { add(REQUEST_CLAIM_PREFIX + it) }
            if (isGiftCardPurchase) add(GIFT_CARD_CLAIM)
        }
        if (keys.isEmpty()) {
            return@withLock null
        }
        keys.firstNotNullOfOrNull { key -> submissionClaims[key]?.takeIf { it != txId } }?.let {
            return@withLock it
        }
        val stored = config.getAllOrThrow().filter { !it.abandoned && it.txId != txId }
        val recorded = paymentRequestId?.let { id -> stored.firstOrNull { it.paymentRequestId == id } }
            ?: stored.firstOrNull { isGiftCardPurchase && it.isGiftCardPurchase }
        if (recorded != null) {
            return@withLock recorded.txId
        }
        keys.forEach { submissionClaims[it] = txId }
        null
    }

    /** Gives back every claim [txId] holds. Harmless when it holds none (or a wipe dropped them). */
    fun releaseSubmissionClaim(txId: Sha256Hash) {
        submissionClaims.entries.removeIf { it.value == txId }
    }

    /**
     * Undoes a quarantine once the payment's outcome is known for certain, whether acknowledged
     * or definitively refused. Stops the watch, frees the inputs and forgets the record, leaving
     * nothing behind for a restart to resume.
     *
     * Only for an outcome that is actually certain: an unknown one must stay quarantined.
     *
     * Does not touch the SDK reservation: the caller knows whether the outcome consumed it (an
     * acknowledged broadcast) or frees it (a request that never left), and does that itself.
     */
    suspend fun cancelQuarantine(tx: Transaction) {
        jobs.remove(tx.txId)?.cancel()
        reservations.remove(tx.txId)
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
     * network source, has an InstantSend or ChainLock, was announced by at least one peer, or the
     * SDK engine has observed it. The dashj signals need connected dashj peers, which there are
     * none of once the SDK owns L1; the engine's observation is what remains then.
     */
    suspend fun isTransactionOnNetwork(tx: Transaction): Boolean =
        networkEvidence(tx) == EngineObservation.OBSERVED

    /**
     * What is known about [tx] reaching the network. [EngineObservation.UNAVAILABLE] when the
     * dashj wallet has not seen it and the SDK engine cannot say either way (unreadable store, or
     * an engine that is not running and caught up): that is not evidence the payment was never
     * sent, and nothing may be released or discarded on the strength of it.
     */
    private suspend fun networkEvidence(tx: Transaction): EngineObservation {
        val seenByDashj = try {
            walletData.wallet?.let { wallet ->
                val inWalletTx = wallet.getTransaction(tx.txId)
                val confidence = (inWalletTx ?: tx).confidence
                confidence != null && (
                    (inWalletTx != null && confidence.source == TransactionConfidence.Source.NETWORK) ||
                        confidence.isChainLocked ||
                        confidence.isTransactionLocked ||
                        confidence.numBroadcastPeers() > 0
                    )
            } ?: false
        } catch (e: Exception) {
            log.debug("Error checking transaction network status: {}", e.message)
            false
        }
        if (seenByDashj) {
            return EngineObservation.OBSERVED
        }
        return engineObservation(tx.txId)
    }

    private suspend fun track(
        tx: Transaction,
        payment: PendingDirectPayment,
        origin: Wallet
    ): Deferred<Transaction?> =
        jobsMutex.withLock {
            jobs[tx.txId]?.takeIf { it.isActive }
                ?: scope.async { verify(tx, payment, origin) }.also { jobs[tx.txId] = it }
        }

    /**
     * A durable name for [wallet]: the hash of its watching key. The same wallet gives the same
     * answer after a restart or a restore from its seed, and a replacement made after a wipe gives
     * a different one. Only public key material goes into it.
     */
    private fun walletIdentity(wallet: Wallet): String? = try {
        Sha256Hash.of(wallet.watchingKey.pubKey).toString()
    } catch (e: Exception) {
        log.warn("wallet has no watching key, recording pending payments without an owner", e)
        null
    }

    /**
     * True while [origin] is still the installed wallet. Everything done on behalf of a payment
     * is done to the wallet that made it, and a wipe swaps that out underneath work already in
     * flight, so anything holding a wallet asks again before acting on it. A wallet a wipe has
     * started on no longer counts, even though it stays installed until the wipe finishes.
     *
     * Public because the watches this class owns are not the only such work: a payment's HTTP
     * submission outlives cancellation as well, and it has to answer the same question.
     */
    fun stillOwnedBy(origin: Wallet): Boolean = walletData.wallet === origin && origin !in retiredWallets

    private suspend fun verify(tx: Transaction, payment: PendingDirectPayment, origin: Wallet): Transaction? {
        log.info("watching the network for possibly-sent tx {} (submitted to {})", tx.txId, payment.paymentUrl)
        var current = payment
        var syncedSince = 0L

        while (true) {
            try {
                if (!stillOwnedBy(origin)) {
                    log.info("the wallet that sent {} is gone; abandoning its verification", tx.txId)
                    return null
                }

                val evidence = networkEvidence(tx)
                if (evidence == EngineObservation.OBSERVED) {
                    // Also the late-broadcast case: a released payment is still watched, so a
                    // payee that held the transaction back cannot leave us with a paid order
                    // whose records were thrown away.
                    return commit(tx, current, origin)
                }

                val now = System.currentTimeMillis()
                if (evidence == EngineObservation.UNAVAILABLE) {
                    // Nothing can be concluded this round. The grace period measures continuous
                    // silence from a live engine, so it starts over, and neither the release nor
                    // the retention cleanup may act on a round that saw nothing.
                    syncedSince = 0L
                    delay(pollIntervalMs)
                    continue
                }
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
                        current = release(tx, current, origin)
                    }
                } else if (now - current.createdAt >= recordRetentionMs) {
                    discardRecords(current, origin)
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
        // The SDK engine must be running and caught up before silence counts at all: BIP70
        // payments are built by it, and the engine is where a payee's broadcast shows up once the
        // SDK owns L1. A torn-down engine publishes IDLE while the persisted row below keeps its
        // last percentage and replay flag, so without this a recently synced row would let the
        // grace run out with nothing watching the network.
        if (!engine.isObservationLive()) {
            return false
        }
        // Both of the checks below describe the dashj peer group. Once the SDK owns L1 that peer
        // group is held: it never connects, so the status never reaches CONNECTED and the peer
        // count stays 0, and the engine exposes neither of its own. What stands for them then is
        // the persisted state, which the SDK derivation keeps current: its stalled-sync signal
        // and the Android connectivity monitors (which keep running after the cutover) both
        // raise the NETWORK impediment that isSynced() refuses, and the chain tip date is the
        // SDK's own.
        if (!engine.sdkOwnsL1()) {
            if (blockchainStateProvider.getNetworkStatus() != NetworkStatus.CONNECTED) {
                return false
            }
            // Peers, not just NetworkStatus. The status only leaves CONNECTED by way of
            // DISCONNECTING, so losing P2P while the device keeps internet leaves it reading
            // CONNECTED with no peers, and the cached chain tip stays acceptable for another half
            // hour. Without this the grace period could expire having seen no network at all, and
            // a payment the merchant did broadcast would be declared dead and its order deleted.
            if (blockchainStateProvider.getConnectedPeerCount() <= 0) {
                return false
            }
        }
        val state = blockchainStateProvider.getState() ?: return false
        val bestChainDate = state.bestChainDate ?: return false
        return state.isSynced() && System.currentTimeMillis() - bestChainDate.time < RECENT_CHAIN_TIP_MS
    }

    /**
     * Commits to [wallet], the one this payment was made from, rather than to whatever is
     * installed at the moment. The two differ only after a wipe, and then committing, attributing
     * and rebroadcasting an old payment onto the replacement wallet is exactly the harm to avoid;
     * writing to the wallet that is on its way out is merely wasted work.
     */
    private suspend fun commit(tx: Transaction, payment: PendingDirectPayment, wallet: Wallet): Transaction {
        if (!stillOwnedBy(wallet)) {
            throw IllegalStateException("the wallet that sent ${tx.txId} has been wiped")
        }
        Context.propagate(wallet.context)

        val existing = wallet.getTransaction(tx.txId)
        // Whether the SDK bridge committed it; the bridge also announces it through dashj.
        var bridged = false
        if (existing == null) {
            // Through the same bridge every SDK send is committed by, so a recovered payment
            // carries the same stamps and arms the same self-spend grace; plain maybeCommitTx,
            // which the bridge wraps, when it cannot.
            bridged = engine.bridge(tx.txId.toString(), tx.unsafeBitcoinSerialize(), wallet) != null
            if (!bridged) {
                wallet.maybeCommitTx(tx)
            }
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
            metadataProvider.markGiftCardTransaction(tx.txId.toTxId(), payment.serviceName, payment.merchantIconUrl)
        } else {
            val recordedService = metadataProvider.getTransactionMetadata(tx.txId.toTxId())?.service
            if (recordedService.isNullOrEmpty()) {
                payment.serviceName?.let { metadataProvider.setTransactionService(tx.txId.toTxId(), it) }
            } else {
                log.info("keeping the service already recorded for {}: {}", tx.txId, recordedService)
            }
        }
        val walletTx = wallet.getTransaction(tx.txId) ?: tx
        // harmless if the merchant already broadcast it; makes sure the network has it otherwise.
        // A reservation still held is consumed through the engine that made it, and the dashj
        // announce is the same tail the SDK bridge runs after every SDK send.
        reservations.remove(tx.txId)?.let { engine.broadcast(it) }
        if (!bridged) {
            walletApplication.broadcastTransaction(walletTx)
        }
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
    private suspend fun release(tx: Transaction, payment: PendingDirectPayment, origin: Wallet): PendingDirectPayment {
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
            config.add(abandoned) { stillOwnedBy(origin) }
        } catch (e: Exception) {
            log.error("could not record the release of {}, keeping it locked and pending", tx.txId, e)
            throw e
        }

        // Past this point the stored payment says abandoned, so a restart will not lock these
        // inputs again, only carry on watching.
        unlockInputs(origin, tx)
        reservations.remove(tx.txId)?.let { engine.release(it) }
        return abandoned
    }

    /**
     * Removes everything saved against a payment that was never sent. The payment is only dropped
     * once that succeeds: discarding it earlier would leave gift cards, metadata or queued
     * platform changes behind with nothing left to retry them.
     */
    private suspend fun discardRecords(payment: PendingDirectPayment, origin: Wallet) {
        // The transaction can arrive between the last network check and this call. forgetTransaction
        // then reports settled without deleting anything, because the wallet holds it, and finishing
        // here would lose the gift card metadata commit() restores. Look again first.
        origin.takeIf { stillOwnedBy(it) }?.getTransaction(payment.txId)?.let { walletTx ->
            log.info("abandoned payment {} turned out to be real after all, committing it", payment.txId)
            commit(walletTx, payment, origin)
            return
        }
        // The dashj wallet is not the only place a real payment can be by now: once the SDK owns
        // L1 the transaction may be known to the engine alone, and forgetTransaction() only asks
        // the dashj wallet whether it is real.
        if (stillOwnedBy(origin)) {
            when (engineObservation(payment.txId)) {
                EngineObservation.OBSERVED -> {
                    log.info("abandoned payment {} was observed by the SDK engine after all, committing it", payment.txId)
                    commit(Transaction(origin.params, payment.txBytes), payment, origin)
                    return
                }
                EngineObservation.UNAVAILABLE -> {
                    // Unknown is not absent. Keep everything; the next resume() asks again.
                    log.warn("could not ask the SDK engine about {}, keeping its records for now", payment.txId)
                    jobs.remove(payment.txId)
                    return
                }
                EngineObservation.ABSENT -> Unit
            }
        }

        if (payment.isGiftCardPurchase) {
            // Stop polling, but keep everything needed to make sense of a late arrival. Dropping
            // the record would take the selected provider with it: the card rows do not store it,
            // and the metadata could never be written while the transaction was absent from the
            // wallet. Without it GiftCardDetailsViewModel has nothing to dispatch on and a paid
            // purchase cannot be retrieved. Marked so resume() applies it if the transaction ever
            // shows up, without watching for it.
            log.warn(
                "giving up watching {} after the retention period, keeping its order and provider " +
                    "in case the transaction still arrives",
                payment.txId
            )
            try {
                config.add(payment.copy(watchExpired = true)) { stillOwnedBy(origin) }
            } catch (e: Exception) {
                log.error("could not record the expired watch for {}", payment.txId, e)
            }
            jobs.remove(payment.txId)
            return
        }

        val settled = try {
            metadataProvider.forgetTransaction(payment.txId.toTxId())
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

    /** What the SDK engine can say about [txId]; a failure to ask is [EngineObservation.UNAVAILABLE]. */
    private suspend fun engineObservation(txId: Sha256Hash): EngineObservation = try {
        engine.observation(txId.toString())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("could not ask the SDK engine about {}", txId, e)
        EngineObservation.UNAVAILABLE
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
