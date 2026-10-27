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
import de.schildbach.wallet.Constants
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.bitcoinj.core.Utils
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.data.NetworkStatus
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.PaymentRecoveryMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
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
    private val engine: PendingPaymentEngine,
    /** Shared with fresh SDK funding; see [FundingSelectionLock]. */
    private val fundingSelection: FundingSelectionLock
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
        /** First wait before retrying a record removal that failed; doubles up to the cap. */
        private const val DEFAULT_REMOVAL_RETRY_INITIAL_MS = 2_000L
        private const val REMOVAL_RETRY_MAX_MS = 5 * 60_000L

        private const val REQUEST_CLAIM_PREFIX = "request:"
        private const val ORDER_CLAIM_PREFIX = "order:"
        private const val RESUBMISSION_CLAIM_PREFIX = "resubmission:"
        private const val GIFT_CARD_CLAIM = "gift-card"
    }

    @VisibleForTesting internal var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    @VisibleForTesting internal var minAgeMs = DEFAULT_MIN_AGE_MS
    @VisibleForTesting internal var syncedGraceMs = DEFAULT_SYNCED_GRACE_MS
    @VisibleForTesting internal var restoreTimeoutMs = DEFAULT_RESTORE_TIMEOUT_MS
    @VisibleForTesting internal var removalRetryInitialMs = DEFAULT_REMOVAL_RETRY_INITIAL_MS

    /**
     * Test seam: runs in the removal retry worker right after it finds the queue drained, before
     * it decides whether to retire: the window in which another removal can fail and be queued.
     */
    @VisibleForTesting internal var afterRemovalQueueDrained: suspend () -> Unit = {}

    /**
     * Test seam: runs in the removal retry worker for each queued id, after it has taken its list
     * and before it removes that record: the window in which the payment can be quarantined again.
     */
    @VisibleForTesting internal var afterRemovalSnapshot: suspend (Sha256Hash) -> Unit = {}

    /**
     * Test seam: runs in a recovery scan right after it has read the stored payments, before it
     * applies them: the window in which a payment can be settled under the scan's feet.
     */
    @VisibleForTesting internal var afterRestoreSnapshotRead: suspend () -> Unit = {}

    /**
     * Test seam: runs in a watch's release right before it records the payment as abandoned: the
     * window in which the payment can be settled under the watch's feet.
     */
    @VisibleForTesting internal var beforeReleaseWrite: suspend () -> Unit = {}

    /**
     * Test seam: runs in a watch's release once its record lock is given back, before it returns:
     * the window in which another lifecycle step (a resubmission's reactivation) can take the lock.
     */
    @VisibleForTesting internal var afterReleaseLocked: suspend () -> Unit = {}

    /**
     * Test seam: runs in a watch that has judged its payment invalidated, before it settles that
     * under the funding-selection and record locks: the window in which a resubmission can
     * reactivate the payment or a quarantine rewrite it.
     */
    @VisibleForTesting internal var beforeInvalidationSettled: suspend () -> Unit = {}

    /**
     * Test seam: runs in a watch that has seen its payment on the network, after its ownership
     * check and before it takes the wipe-barrier hold its commit runs under: the window in which a
     * Reset Wallet can start.
     */
    @VisibleForTesting internal var afterObservedOnNetwork: suspend () -> Unit = {}

    /**
     * Test seam: runs inside that hold, with [origin]'s ownership asked again, right before the
     * commit: the window in which a Reset Wallet cancels the hold.
     */
    @VisibleForTesting internal var beforeObservedCommit: suspend () -> Unit = {}

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

    /**
     * Payments whose outcome is settled (committed, acknowledged, definitively never sent, or
     * discarded) but whose stored record could not be removed yet. They count as resolved
     * everywhere in this process: the funding gate, the invoice lookup and claim, and the scan,
     * which neither watches nor locks them again. [retryPendingRemovals] keeps trying, with backoff,
     * until each removal lands. In memory and per wallet: a wipe clears the store and this set.
     */
    private val pendingRemovals: MutableSet<Sha256Hash> = ConcurrentHashMap.newKeySet()

    @Volatile private var removalRetryJob: Job? = null

    /**
     * Serializes settling a payment: its watch committing it on observing it, and an acknowledged
     * resubmission completing it. Whichever runs second finds it settled and does not repeat it.
     */
    private val settleMutex = Mutex()
    private val claimMutex = Mutex()

    /**
     * Serializes settling a stored record (cancelling its quarantine, or finishing it once committed
     * or invalidated) with a recovery scan applying its snapshot of that record. The scan reads every
     * record first and applies them later; without this, a record settled in between was locked and
     * watched again from the stale copy, and that watch's release later wrote the record back
     * (review 4302333212). The scan re-reads each record under this lock before applying it.
     *
     * A watch's release takes it too, to write the abandoned record only while that record is
     * still stored and unsettled. So do a quarantine's write, the removal retry worker (which
     * re-checks that an id is still queued, review 4302741459), the scan's discard of another
     * wallet's record, [reactivateForResubmission] / [releaseUnsentResubmission], the settlement of
     * an invalidated payment, and the wipe's clear: every write and delete of a record this class
     * makes.
     *
     * Innermost: taken after [scanMutex] or [settleMutex], never before them. While holding it,
     * only [jobsMutex] (through [track]) is taken. The full order: [FundingSelectionLock] (shared by
     * fresh SDK funding and by an invalidation's settlement, exclusive and fail-fast for
     * [reactivateForResubmission], exclusive and waiting for the wipe), then [scanMutex] or
     * [settleMutex] or [claimMutex], then [recordMutex], then [jobsMutex].
     *
     * Settlement on network evidence ([commit], [commitAcknowledged]) takes no revision check, on
     * purpose: a transaction seen on the network is paid whatever the record says, and a
     * reactivation it overtakes is settled by the same evidence in its new watch. It does take a
     * SHARED funding hold, outermost, with its wallet's ownership asked again under it: that hold
     * is the wipe barrier, so a Reset Wallet never has a commit, release or restore of the old
     * wallet's payments running underneath its teardown ([whileOwned]).
     */
    private val recordMutex = Mutex()

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
        // Inside the wipe barrier (outermost, like every other user of the lock). Reset Wallet
        // raised it at its very start and drained it before it detached the wallet or stopped the
        // SDK (WalletWipeSequence.finish), so the fresh fundings, SDK broadcasts, BIP70 POSTs and
        // invalidation settlements of the old wallet have all ended, every new one is refused, and
        // a reactivation fails fast; this runs within that barrier rather than raising its own
        // (FundingSelectionLock.exclusiveForWipe). Called with no barrier up (a test, a listener
        // run on its own), it raises one for itself.
        //
        // Deadlock-free: nothing waits for the lock while the barrier is up (a hold is refused at
        // once), so the jobs joined below, cancelled first, never wait for it either.
        fundingSelection.exclusiveForWipe {
            val running = jobs.values.toList()
            jobs.clear()
            synchronized(pendingRemovals) {
                removalRetryJob?.cancel()
                removalRetryJob = null
            }
            running.forEach { it.cancel() }
            // Bounded: a job wedged in a call that does not answer cancellation must not hold up
            // the wipe, and the captured-wallet checks mean a straggler still cannot touch the
            // replacement wallet.
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) { running.forEach { runCatching { it.join() } } }
            // Under the record lock, like every other write of the records: a quarantine or
            // release already holding it finishes first, and any that comes after finds its
            // wallet retired and is refused where it would land.
            recordMutex.withLock {
                // The wiped wallet's engine reservations go with it; nothing will consume or
                // release them.
                reservations.clear()
                // Its in-flight submissions are refused by their own ownership checks; their
                // claims must not hold up the replacement wallet's payments in the meantime.
                submissionClaims.clear()
                // The store is cleared below; nothing of the old wallet is retried after the wipe.
                pendingRemovals.clear()
                locksRestored = false
                try {
                    config.clearAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("could not clear pending payments during wipe", e)
                }
            }
            log.info("wallet wiped; dropped {} pending payment watch(es) and reset readiness", running.size)
        }
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
        reservation: SdkDeferredPayment? = null,
        /** The stable identity of the order [paymentRequestId] is a request for; see [PendingDirectPayment.orderId]. */
        orderId: String? = null,
        /** The PKI-verified merchant name of the request, if any; see [PendingDirectPayment.merchantName]. */
        merchantName: String? = null
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
            orderId = orderId,
            merchantName = merchantName,
            serviceName = serviceName,
            createdAt = System.currentTimeMillis(),
            isGiftCardPurchase = recovery?.isGiftCardPurchase ?: false,
            merchantIconUrl = recovery?.merchantIconUrl,
            walletId = walletIdentity(wallet),
            // what the SDK built it for; a resubmission shows it (review 4303472428)
            feeDuffs = reservation?.feeDuffs,
            revision = newRevision()
        )
        lockInputs(wallet, tx)
        try {
            // Under the record lock, like every write and delete of a record: a queued removal of
            // an earlier settlement of this txid re-checks the queue under it, so it cannot
            // delete the record written here (review 4302741459).
            recordMutex.withLock {
                config.add(payment) { stillOwnedBy(wallet) }
                // A record being quarantined is active again, whatever an earlier resolution left
                // behind - but only once its new record is durable (review 4303092964). Cleared
                // before the write, a failed write left the earlier settled record on disk with
                // nothing queued to remove it, counted as unresolved by the funding gate and the
                // invoice lookup until the next process start.
                pendingRemovals.remove(tx.txId)
            }
        } catch (e: Exception) {
            // Callers quarantine before submitting, so nothing has been sent yet and aborting
            // costs nothing. Carrying on without a record would be the expensive choice: the
            // locks live only in memory, so a process death during submission or the wait would
            // leave resume() with nothing to restore and the inputs of an uncertain payment free
            // to fund a retry.
            log.error("could not persist pending direct payment {}, releasing its inputs", tx.txId, e)
            unlockInputs(wallet, tx)
            // An earlier settlement of this txid is still queued for removal: keep it retried.
            if (tx.txId in pendingRemovals) {
                scheduleRemovalRetry()
            }
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
    private suspend fun scan(): Boolean = try {
        // Inside the wipe barrier, outermost (before scanMutex): restoring locks, records and
        // watches, and committing a payment that arrived after its watch expired, all act on the
        // wallet, and none of it may run underneath a Reset Wallet's teardown. Refused at once
        // while a wipe is under way, and cancelled (then drained) by one that starts meanwhile.
        fundingSelection.withShared {
            scanMutex.withLock {
                try {
                    restorePersisted()
                    locksRestored = true
                    true
                } catch (e: Exception) {
                    log.error("could not restore pending direct payments", e)
                    false
                }
            }
        }
    } catch (e: WalletWipedException) {
        log.info("not restoring pending direct payments: the wallet is being wiped")
        false
    } catch (e: WalletWipedCancellationException) {
        log.info("restoring pending direct payments was cancelled by a wallet wipe")
        false
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
                afterRestoreSnapshotRead()
                if (pending.isEmpty()) {
                    return@restore
                }
                log.info("resuming verification of {} pending direct payment(s)", pending.size)
                val walletId = walletIdentity(wallet)
                for (payment in pending) {
                    if (payment.txId in pendingRemovals) {
                        // Settled in this process; only its removal is outstanding. Never watched
                        // or locked again: the retry, not the scan, finishes it.
                        continue
                    }
                    when (ownershipOf(payment, wallet, walletId)) {
                        Ownership.OWNED -> Unit
                        Ownership.OTHER_WALLET -> {
                            // Left by a wallet that has since been wiped: a write that was already
                            // on its way when the wipe cleared the store, or a clear that failed.
                            // Its outpoints are not this wallet's, so there is nothing here to
                            // protect, and binding it here is the harm - a watch on this wallet
                            // would commit and rebroadcast a transaction this wallet never made.
                            // Dropped, not kept: no wallet that could use it is left.
                            log.warn("discarding pending payment {}, it belongs to a wallet that was wiped", payment.txId)
                            recordMutex.withLock { removeRecord(payment.txId) }
                            continue
                        }
                        Ownership.UNKNOWN -> {
                            // Nothing proves it is another wallet's: this wallet's identity is
                            // unreadable, or the record names no owner and its inputs are not ones
                            // dashj knows (an SDK-only input looks exactly like that). It may be
                            // the only record of a payment this wallet made, so it is kept; but it
                            // is not adopted either (no locks, no watch, no owner written down)
                            // until something proves whose it is. Meanwhile it keeps blocking its
                            // invoice and its merchant (unresolvedSubmissionFor, claimSubmission).
                            log.warn(
                                "keeping pending payment {}: cannot tell which wallet made it, so not adopting it",
                                payment.txId
                            )
                            continue
                        }
                    }
                    try {
                        val tx = Transaction(wallet.params, payment.txBytes)
                        // Outside the lock: committing finishes the record, which takes it.
                        restoreRecord(payment, tx, wallet)?.let { (arrived, current) ->
                            log.info("transaction {} arrived after its watch expired", tx.txId)
                            commit(arrived, current, wallet)
                        }
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

    /**
     * Applies the scan's [snapshot] of one record, under [recordMutex] and from the record as it is
     * stored NOW: one settled since the snapshot was read is gone (or awaiting its removal) and is
     * left alone, so its inputs are not locked and no watch is started for it again.
     *
     * @return the arrived transaction and the record to commit it with, for a watch-expired record
     *   whose transaction has since been seen; the caller commits it once the lock is released.
     */
    private suspend fun restoreRecord(
        snapshot: PendingDirectPayment,
        tx: Transaction,
        wallet: Wallet
    ): Pair<Transaction, PendingDirectPayment>? = recordMutex.withLock {
        val read = config.getAllOrThrow().firstOrNull { it.txId == snapshot.txId }
        if (read == null || read.txId in pendingRemovals) {
            log.info("pending payment {} was settled while the scan ran; not restoring it", snapshot.txId)
            return@withLock null
        }
        // A record with no owner (written before owners were stored) that the scan has just
        // proven to be this wallet's: its owner is written down now, durably, so a later wipe
        // leaves nothing a replacement could mistake for its own.
        val owner = walletIdentity(wallet)
        val payment = if (read.walletId == null && owner != null) {
            read.copy(walletId = owner, revision = newRevision()).also { config.add(it) { stillOwnedBy(wallet) } }
        } else {
            read
        }

        if (payment.watchExpired) {
            // Polling stopped long ago (a record from before released payments were watched until
            // resolved). If ordinary syncing has since found the transaction, attribute it and let
            // the record go; otherwise watch it again like any released payment, until it is seen
            // or invalidated.
            val arrived = wallet.getTransaction(tx.txId)
                ?: tx.takeIf { engineObservation(tx.txId) == EngineObservation.OBSERVED }
            if (arrived != null) {
                return@withLock arrived to payment
            }
            track(tx, payment, wallet)
            return@withLock null
        }

        // An abandoned payment keeps its watch but not its locks: the inputs were already
        // released, and re-locking outputs the user may since have spent would be wrong. The
        // watch continues so a late broadcast is still caught.
        if (!payment.abandoned && !isTracked(tx.txId)) {
            lockInputs(wallet, tx)
        }
        track(tx, payment, wallet)
        null
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
        // No wallet installed: nothing can be told apart, so every record counts (fail closed).
        val installed = walletData.wallet
        val walletId = installed?.let { walletIdentity(it) }
        val now = System.currentTimeMillis()
        val tip = try {
            engine.chainHeightUpperBound()
        } catch (e: Exception) {
            null
        }
        return pending.any { payment ->
            payment.txId !in pendingRemovals &&
                !payment.abandoned && !payment.watchExpired &&
                (installed == null || belongsTo(payment, installed, walletId)) &&
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
     * The transaction of a submission for [paymentRequestId], or for any refresh of the same
     * order ([orderId]), whose outcome is still unknown, or null if this invoice has nothing
     * outstanding.
     *
     * Restoring input locks is not enough to stop a second payment for the same invoice: it stops
     * the new transaction reusing those outpoints, and a wallet with other funds simply builds one
     * from different ones, leaving two payments the payee can both broadcast. A screen that has
     * lost its own state - process death, or the invoice opened again - has nothing else to ask.
     *
     * An abandoned (released) payment counts too (review 9fa90c30ccf1). Its inputs were freed,
     * but its signatures were not revoked: a payee that kept it can still broadcast it, alongside
     * any new payment built from other coins. Its record leaves the store, and with it this block,
     * only once it is seen on the network (paid) or a confirmed transaction spends one of its
     * inputs (it can never confirm). Until then the invoice is paid again by resubmitting that same
     * transaction ([abandonedSubmissionFor]), never with a new one.
     *
     * @param legacyOrderId the url-and-outputs identity of the same order (`legacyOrderIdentity`),
     *   which records stamped before merchant data was keyed on carry
     * @param paymentUrl where the request posts its Payment: a record with no order identity at
     *   all matches any request to the same url (fail closed, see [isForInvoice])
     * @param merchant who the request pays, for a lookup before funding a new payment: when nothing
     *   matches the exact identity, an unresolved payment to the same merchant is returned
     *   ([isUnresolvedFor]). Null for anything that funds nothing new (a resubmission of T1).
     */
    suspend fun unresolvedSubmissionFor(
        paymentRequestId: String?,
        orderId: String? = null,
        legacyOrderId: String? = null,
        paymentUrl: String? = null,
        merchant: Merchant? = null
    ): Sha256Hash? {
        val invoice = Invoice(paymentRequestId, orderId, legacyOrderId, paymentUrl, merchant)
        if (invoice.isEmpty) {
            return null
        }
        val stored = config.getAllOrThrow().filter { it.txId !in pendingRemovals }
        // the exact identity first; another order of the same merchant only when it finds nothing
        val recorded = stored.firstOrNull { it.isForInvoice(invoice) }
            ?: stored.firstOrNull { it.isUnresolvedFor(invoice.merchant) }
        return recorded?.txId
    }

    /** What a payment request is known by; see [isForInvoice]. */
    private class Invoice(
        val paymentRequestId: String?,
        orderId: String?,
        legacyOrderId: String?,
        /** Null when records with no order identity must not be matched by url. */
        val paymentUrl: String?,
        /** Null when unresolved payments to the same merchant must not block (not a fresh funding). */
        merchant: Merchant? = null
    ) {
        val orderIds = setOfNotNull(orderId, legacyOrderId)
        val merchant = merchant?.takeIf { !it.isEmpty }
        val isEmpty get() = paymentRequestId == null && orderIds.isEmpty() && paymentUrl == null && this.merchant == null
    }

    /**
     * Whether this record paid [invoice]: the exact request ([Invoice.paymentRequestId]), or any
     * refresh of its order ([Invoice.orderIds]: the order identity, and the url-and-outputs one a
     * record stamped before merchant data was keyed on carries).
     *
     * A record written before order identities were stored holds nothing one can be recomputed
     * from: not the request, not its merchant data, and not which of its transaction's outputs were
     * the payee's rather than change. It cannot be told apart from a refresh of its own order, so
     * it fails closed: it matches every request posting to the same payment_url, until it is
     * resolved (seen on the network, or invalidated by a conflict). A lookup that resubmits a
     * stored transaction passes no url, so such a record is never resubmitted for a request it may
     * not be for.
     */
    private fun PendingDirectPayment.isForInvoice(invoice: Invoice): Boolean =
        (invoice.paymentRequestId != null && paymentRequestId == invoice.paymentRequestId) ||
            (orderId != null && orderId in invoice.orderIds) ||
            (orderId == null && invoice.paymentUrl != null && paymentUrl == invoice.paymentUrl)

    /**
     * Whether this record is a payment to [merchant] whose outcome is still unknown, which blocks
     * fresh funding of ANY order of that merchant, not only its own. Asked only once the exact
     * identity ([isForInvoice]) has found nothing: the order identity is hashed from fields the
     * merchant controls (merchant_data, payment_url, outputs), so the same unpaid order re-served
     * with any of them changed after an ambiguous POST is no longer recognised as itself, and a
     * second payment would be built while the signed first one can still be broadcast. Failing
     * closed per merchant closes that.
     *
     * The same merchant when ANY holds: the same verified merchant name (when both have one), the
     * same payment_url host (case-insensitive), or a payee script of the request among this
     * record's outputs. Only an active record counts: one released (abandoned or watch-expired)
     * still blocks its own order, which is resubmitted rather than paid anew, but not others; one
     * settled (gone, or awaiting removal) blocks nothing. Callers leave out the settled ones.
     */
    private fun PendingDirectPayment.isUnresolvedFor(merchant: Merchant?): Boolean {
        merchant ?: return false
        if (abandoned || watchExpired) return false
        if (merchant.verifiedName != null && merchantName.equals(merchant.verifiedName, ignoreCase = true)) {
            return true
        }
        if (merchant.host != null && hostOf(paymentUrl) == merchant.host) {
            return true
        }
        if (merchant.payeeScripts.isNotEmpty()) {
            val outputScripts = try {
                Transaction(walletData.wallet?.params ?: Constants.NETWORK_PARAMETERS, txBytes)
                    .outputs.map { Utils.HEX.encode(it.scriptBytes) }
            } catch (e: Exception) {
                log.warn("could not read the outputs of pending payment {}", txId, e)
                emptyList()
            }
            if (outputScripts.any { it in merchant.payeeScripts }) {
                return true
            }
        }
        return false
    }

    /**
     * Claims the right to submit [txId] as the one payment for its invoice ([paymentRequestId])
     * and, for a gift card purchase, as the one unreleased gift card purchase. Atomic across the
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
        isGiftCardPurchase: Boolean,
        /**
         * A resubmission of [txId] itself ([PendingDirectPaymentVerifier.reactivateForResubmission]):
         * exclusive per transaction. The keys above let [txId] itself through, so two
         * resubmissions of one T1 (confirmed from separate screens) both passed, and the one that
         * then failed before its POST released T1 while the other's was in flight. Refused, with
         * [txId], while another resubmission of it holds its claim.
         */
        resubmission: Boolean = false,
        /** The order [paymentRequestId] is a request for: a refreshed request for it is the same invoice. */
        orderId: String? = null,
        /** As for [unresolvedSubmissionFor]. */
        legacyOrderId: String? = null,
        /** As for [unresolvedSubmissionFor]. */
        paymentUrl: String? = null,
        /** As for [unresolvedSubmissionFor]. */
        merchant: Merchant? = null
    ): Sha256Hash? = claimMutex.withLock {
        val invoice = Invoice(paymentRequestId, orderId, legacyOrderId, paymentUrl, merchant)
        val resubmissionKey = RESUBMISSION_CLAIM_PREFIX + txId
        val keys = buildList {
            paymentRequestId?.let { add(REQUEST_CLAIM_PREFIX + it) }
            invoice.orderIds.forEach { add(ORDER_CLAIM_PREFIX + it) }
            if (isGiftCardPurchase) add(GIFT_CARD_CLAIM)
            if (resubmission) add(resubmissionKey)
        }
        // The other keys let txId itself through; this one does not.
        if (resubmission && submissionClaims.containsKey(resubmissionKey)) {
            return@withLock txId
        }
        if (keys.isEmpty() && invoice.merchant == null) {
            return@withLock null
        }
        keys.firstNotNullOfOrNull { key -> submissionClaims[key]?.takeIf { it != txId } }?.let {
            return@withLock it
        }
        // For the SAME invoice, released (abandoned) records count too (review 9fa90c30ccf1): their
        // signed transaction is still valid, so a second one could pay it twice. Only T1 itself is
        // let through, to be resubmitted as it is.
        //
        // The gift-card key is different (the #1560 rule): only an UNRELEASED gift card purchase
        // blocks another. A new purchase is a separate order with its own invoice, not a duplicate
        // of a released one, whose late broadcast is still attributed to its own order.
        //
        // A different order of the same merchant is blocked only by an unresolved payment, and only
        // once the exact identity has matched nothing ([isUnresolvedFor]).
        val stored = config.getAllOrThrow().filter { it.txId != txId && it.txId !in pendingRemovals }
        val recorded = stored.firstOrNull { it.isForInvoice(invoice) }
            ?: stored.firstOrNull { isGiftCardPurchase && it.isGiftCardPurchase && !it.abandoned }
            ?: stored.firstOrNull { it.isUnresolvedFor(invoice.merchant) }
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
     * Makes the released record of [tx] active again before [tx] is posted to the payee once more
     * (review 4307041830), exactly like a fresh quarantine: the record is rewritten durably as
     * neither abandoned nor watch-expired, with a fresh [PendingDirectPayment.createdAt] so the
     * release grace starts over; its inputs are locked again; and its watch is replaced, since the
     * old one holds the released state. Its SDK reservation cannot be made again for a transaction
     * already signed, so until it resolves it counts as unprotected and the funding gate
     * ([hasUnprotectedPendingPayment]) blocks fresh SDK funding.
     *
     * A resubmission does not pass through the payment build and its restoration barrier, so
     * restoration is awaited here first (review 4302741463). If the request then never leaves,
     * [releaseUnsentResubmission] puts the payment back as it was.
     *
     * Eligibility is decided here, under the lock, from the record as it is now (review
     * 4307815170): the caller's lookup may be stale, and another resubmission may since have made
     * the payment active and left it uncertain. Only a released record is reactivated; an active
     * one is left exactly as it is.
     *
     * @throws IllegalStateException when restoration fails or [origin] is no longer the wallet
     */
    suspend fun reactivateForResubmission(tx: Transaction, origin: Wallet): Reactivation {
        // Restoration first, outside the lock, so the exclusive section below stays short.
        awaitRestored()
        // EXCLUSIVE and fail-fast on the shared selection lock (review 4309551218): fresh SDK
        // funding asks the gate again and selects its inputs under a shared hold, so it never
        // selects this payment's inputs while the reactivation makes it active. A funding under way
        // can take minutes (a registration waits for its asset lock); the reactivation is refused
        // rather than waiting for it, touching nothing. Outermost, before scanMutex and recordMutex.
        return fundingSelection.tryExclusive { reactivateNow(tx, origin) }
            ?: Reactivation.FUNDING_IN_PROGRESS
    }

    private suspend fun reactivateNow(tx: Transaction, origin: Wallet): Reactivation {
        // Asked, not awaited: a scan takes a shared hold of the lock this exclusive section holds,
        // so scanning from here would wait for itself. Restoration was awaited just before; it is
        // undone only by a wipe, which cannot run while this section is held.
        check(locksRestored) { "pending payments are not restored; not reactivating ${tx.txId}" }
        return recordMutex.withLock {
            if (!stillOwnedBy(origin)) {
                throw IllegalStateException("the wallet that signed ${tx.txId} has been replaced")
            }
            val payment = config.getAllOrThrow().firstOrNull { it.txId == tx.txId }
            if (payment == null || tx.txId in pendingRemovals) {
                return@withLock Reactivation.SETTLED
            }
            if (!payment.abandoned && !payment.watchExpired) {
                return@withLock Reactivation.ALREADY_ACTIVE
            }
            val active = payment.copy(
                abandoned = false,
                watchExpired = false,
                createdAt = System.currentTimeMillis(),
                revision = newRevision()
            )
            // Locked before the record says active, as a quarantine does; given back if it cannot
            // be written, which leaves the payment released as it was.
            lockInputs(origin, tx)
            try {
                config.add(active) { stillOwnedBy(origin) }
            } catch (e: Exception) {
                unlockInputs(origin, tx)
                throw e
            }
            jobs.remove(tx.txId)?.cancel()
            track(tx, active, origin)
            Reactivation.REACTIVATED
        }
    }

    /**
     * Who a BIP70 request pays, as far as it can be told: its PKI-verified merchant name, the host
     * of its payment_url and its payee scripts. A merchant controls everything its order identity
     * is hashed from, so a re-served order can carry a different one; it cannot as easily change
     * all of these at once. See [isUnresolvedFor].
     */
    class Merchant(
        verifiedName: String?,
        paymentUrl: String?,
        payeeScripts: Collection<ByteArray>
    ) {
        val verifiedName: String? = verifiedName?.takeIf { it.isNotBlank() }
        val host: String? = hostOf(paymentUrl)
        val payeeScripts: Set<String> = payeeScripts.filter { it.isNotEmpty() }.map { Utils.HEX.encode(it) }.toSet()
        val isEmpty get() = verifiedName == null && host == null && payeeScripts.isEmpty()
    }

    /** What [reactivateForResubmission] found. */
    enum class Reactivation {
        /** Released, and now active again: this attempt owns the activation. */
        REACTIVATED,

        /** Already active: another submission has it out, its outcome unknown. Left untouched. */
        ALREADY_ACTIVE,

        /** No longer on record as unsettled: settled meanwhile. */
        SETTLED,

        /** Fresh SDK funding is selecting inputs right now: refused, the payment untouched. */
        FUNDING_IN_PROGRESS
    }

    /**
     * Puts a payment reactivated by [reactivateForResubmission] back the way it was, released,
     * once its new request definitely never left: the record durably abandoned first, then its
     * inputs freed and its watch carried on in the released state. A record that is gone was
     * settled meanwhile and is left alone. Never throws: a failed write keeps the payment active
     * and locked, which errs on the protected side, and its watch releases it in due course.
     */
    suspend fun releaseUnsentResubmission(tx: Transaction, origin: Wallet) {
        try {
            whileOwned(origin) { releaseUnsentNow(tx, origin) }
                ?: log.info("not releasing unsent resubmission {}: its wallet is being wiped", tx.txId)
        } catch (e: WalletWipedException) {
            log.info("not releasing unsent resubmission {}: its wallet is being wiped", tx.txId)
        } catch (e: WalletWipedCancellationException) {
            log.info("releasing unsent resubmission {} was cancelled by a wallet wipe", tx.txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("could not release unsent resubmission {}; it stays active and locked", tx.txId, e)
        }
    }

    private suspend fun releaseUnsentNow(tx: Transaction, origin: Wallet) {
        recordMutex.withLock {
            val payment = config.getAllOrThrow().firstOrNull { it.txId == tx.txId }
            if (payment == null || tx.txId in pendingRemovals || payment.abandoned) {
                return@withLock
            }
            val released = payment.copy(abandoned = true, revision = newRevision())
            config.add(released) { stillOwnedBy(origin) }
            if (stillOwnedBy(origin)) {
                unlockInputs(origin, tx)
            }
            jobs.remove(tx.txId)?.cancel()
            track(tx, released, origin)
        }
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
     *
     * Acts only for [origin], the wallet that quarantined [tx], and only while it is still the
     * wallet: asked before the record lock is taken and again under it, since a wipe can land
     * while it waits. Once [origin] is wiped this is a no-op. The wipe already dropped its watch,
     * reservation and record, and the installed wallet is a replacement whose locks and record of
     * the same outpoints are its own.
     */
    suspend fun cancelQuarantine(tx: Transaction, origin: Wallet) {
        if (!stillOwnedBy(origin)) {
            log.info("not cancelling the quarantine of {}: the wallet that made it has been wiped", tx.txId)
            return
        }
        // Inside the wipe barrier, with ownership asked again under it: a wipe that has started
        // owns this wallet's records and locks. Then under the scan's per-record lock: a scan
        // holding an older read of this record must not lock and watch it again once it is gone
        // (review 4302333212).
        val cancelled = try {
            whileOwned(origin) {
                recordMutex.withLock {
                    if (!stillOwnedBy(origin)) {
                        return@withLock false
                    }
                    jobs.remove(tx.txId)?.cancel()
                    reservations.remove(tx.txId)
                    unlockInputs(origin, tx)
                    removeRecord(tx.txId)
                    true
                }
            } ?: false
        } catch (e: WalletWipedException) {
            false
        } catch (e: WalletWipedCancellationException) {
            false
        }
        if (cancelled) {
            log.info("quarantine cancelled for {}, its outcome is known", tx.txId)
        } else {
            log.info("not cancelling the quarantine of {}: its wallet was wiped while it waited", tx.txId)
        }
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

    /** Whose a stored record is, as far as can be proven; see [ownershipOf]. */
    private enum class Ownership {
        /** Made by the wallet asked about. */
        OWNED,

        /** Positively another wallet's: it names an owner, and that is not this wallet. */
        OTHER_WALLET,

        /** Neither can be proven. Kept, but never adopted. */
        UNKNOWN
    }

    /**
     * Whose [payment] is, relative to [wallet] (whose identity is [walletId]).
     *
     * A record that names its owner is [Ownership.OWNED] when that is [walletId], and
     * [Ownership.OTHER_WALLET] when it is a different, readable identity: the only positive
     * evidence that it is another wallet's. Anything short of proof is [Ownership.UNKNOWN], never
     * a reason to delete it: a record that names no owner (one written before owners were stored,
     * or by a wallet whose identity could not be read), or a [wallet] whose own identity cannot be
     * read. Such a record is never taken on trust either, since it may have been left by a wallet
     * that has since been wiped: it is [wallet]'s only when its own transaction proves it, by
     * spending an output [wallet] holds and recognises as its own.
     */
    private fun ownershipOf(payment: PendingDirectPayment, wallet: Wallet, walletId: String?): Ownership {
        val owner = payment.walletId
        if (owner != null && walletId != null) {
            return if (owner == walletId) Ownership.OWNED else Ownership.OTHER_WALLET
        }
        return try {
            val tx = Transaction(wallet.params, payment.txBytes)
            val spendsOwnOutput = tx.inputs.any { input ->
                wallet.getTransaction(input.outpoint.hash)
                    ?.outputs?.getOrNull(input.outpoint.index.toInt())
                    ?.isMine(wallet) == true
            }
            if (spendsOwnOutput) Ownership.OWNED else Ownership.UNKNOWN
        } catch (e: Exception) {
            log.warn("could not tell which wallet made pending payment {}; not adopting it", payment.txId, e)
            Ownership.UNKNOWN
        }
    }

    /** Whether [payment] is provably [wallet]'s (fail closed: unknown is not). See [ownershipOf]. */
    private fun belongsTo(payment: PendingDirectPayment, wallet: Wallet, walletId: String?): Boolean =
        ownershipOf(payment, wallet, walletId) == Ownership.OWNED

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

    /**
     * Runs [submit], the hand-over of a quarantined payment to its payee (a BIP70 POST), inside
     * the wipe barrier: a SHARED hold on the [FundingSelectionLock], with [origin]'s ownership
     * asked a last time under it. Asked outside, a wipe could start between the answer and the
     * POST and clear the payment's recovery record and reservation before the payee received it.
     * Under the hold, a wipe that starts after the answer cancels [submit] and waits until it has
     * released before it clears anything.
     *
     * @throws SubmissionRefusedException when [origin] is no longer the installed wallet, or a
     *   wipe ran while this waited for the hold: [submit] was not run, nothing was sent
     * @throws WalletWipedCancellationException when a wipe cancelled [submit] while it ran: the
     *   payee may have received the payment, so it is no proof of non-delivery
     */
    suspend fun <T> submitWhileOwned(origin: Wallet, submit: suspend () -> T): T {
        val refused = { SubmissionRefusedException("the wallet that signed this payment has been wiped; not submitting") }
        return try {
            fundingSelection.withShared {
                if (!stillOwnedBy(origin)) {
                    throw refused()
                }
                submit()
            }
        } catch (e: WalletWipedException) {
            throw refused().apply { initCause(e) }
        }
    }

    /** A submission refused before anything was sent: its wallet is no longer the installed one. */
    class SubmissionRefusedException(message: String) : IllegalStateException(message)

    /**
     * [act] inside the wipe barrier: a SHARED hold on the [FundingSelectionLock], outermost, with
     * [origin]'s ownership asked again under it. Null, running nothing, when [origin] is no longer
     * the wallet. Everything a watch, a scan or a settlement does to the wallet after a suspension
     * runs through here, so a Reset Wallet that starts after an earlier ownership check either
     * refuses it or cancels it and waits for it to release before it tears anything down.
     *
     * Must be called holding none of [scanMutex], [settleMutex], [claimMutex], [recordMutex] or
     * [jobsMutex].
     *
     * @throws WalletWipedException when a wipe is under way: [act] was not run
     * @throws WalletWipedCancellationException when a wipe cancelled [act] while it ran
     */
    private suspend fun <T : Any> whileOwned(origin: Wallet, act: suspend () -> T): T? =
        fundingSelection.withShared {
            if (stillOwnedBy(origin)) act() else null
        }

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
                    afterObservedOnNetwork()
                    // Also the late-broadcast case: a released payment is still watched, so a
                    // payee that held the transaction back cannot leave us with a paid order
                    // whose records were thrown away.
                    //
                    // Inside the wipe barrier, with ownership asked again under it: the check
                    // above was made before the observation suspended, and a Reset Wallet that
                    // started since must not have this payment committed, attributed, unlocked
                    // and rebroadcast underneath its teardown. Refused or cancelled, the watch
                    // just stops; the wipe clears the payment with the rest of the wallet.
                    val committed = whileOwned(origin) {
                        beforeObservedCommit()
                        settleMutex.withLock {
                            // a wipe that cancelled the hold while it waited: nothing committed
                            currentCoroutineContext().ensureActive()
                            commit(tx, current, origin)
                        }
                    }
                    if (committed == null) {
                        log.info("the wallet that sent {} is gone; not committing it", tx.txId)
                    }
                    return committed
                }

                // The only other way out: a confirmed transaction spends one of its inputs, so it
                // can never confirm and the payee can no longer be paid with it. Until then its
                // record stays, and with it the block on its invoice (review 9fa90c30ccf1).
                if (isInvalidated(tx, origin)) {
                    beforeInvalidationSettled()
                    val now = discardInvalidated(tx, current, origin) ?: return null
                    if (now.revision != current.revision) {
                        // superseded: decide again from the newer record next round
                        current = now
                        delay(pollIntervalMs)
                        continue
                    }
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
                        current = release(tx, current, origin) ?: return null
                    }
                }
                // A released payment is never discarded for age: silence, however long, does not
                // revoke its signatures. It is watched until it is seen or invalidated.
            } catch (e: WalletWipedException) {
                log.info("the wallet that sent {} is being wiped; ending its verification", tx.txId)
                return null
            } catch (e: WalletWipedCancellationException) {
                // the hold was cancelled, not this watch: a cancelled watch rethrows
                currentCoroutineContext().ensureActive()
                log.info("the wallet that sent {} is being wiped; its verification was cancelled", tx.txId)
                return null
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
        reservations.remove(tx.txId)?.let { reservation -> engine.broadcast(reservation) { stillOwnedBy(wallet) } }
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
     * Applied only to the record [payment] was read from: under the record lock the stored record
     * is read again, and if it has been written since ([PendingDirectPayment.revision]), say made
     * active again by a reactivation or a new quarantine, the release does nothing at all: no
     * write, no unlock, the reservation left where it is. The watch carries on from the record as
     * it is stored now.
     *
     * @return the payment as now stored (marked abandoned, or the newer record that superseded the
     *   one this release decided on), or null when it was settled meanwhile and the watch has
     *   nothing left to do
     */
    private suspend fun release(tx: Transaction, payment: PendingDirectPayment, origin: Wallet): PendingDirectPayment? {
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
        val abandoned = payment.copy(abandoned = true, revision = newRevision())
        beforeReleaseWrite()
        // Under the lock settlement takes, and only for a payment still unsettled: cancelling the
        // watch is a request, so one already this far would otherwise write back the record a
        // cancellation or a commit has just removed, blocking its invoice again.
        //
        // The teardown is under the same lock, atomically with the write (review 4308598488): a
        // resubmission's reactivation takes this lock to relock the inputs and make the record
        // active again, so unlocking after giving the lock back could undo that reactivation's
        // locks, and a reservation still in the map after the write could be trusted by the
        // funding gate for a record already reactivated.
        //
        // And only for the record it decided on: anything that rewrote it since (a reactivation
        // relocking the inputs, a new quarantine) is newer than this decision, and writing the
        // snapshot over it would undo it.
        var heldReservation: SdkDeferredPayment? = null
        var released = false
        // Inside the wipe barrier, with ownership asked again under it (outermost, before the
        // record lock): a wipe that started since the watch's check owns the wallet's locks,
        // records and reservations. Not owned any more, the watch stops.
        val now = whileOwned(origin) {
            releaseLocked(tx, payment, origin, abandoned) { reservation ->
                heldReservation = reservation
                released = true
            }
        } ?: return null
        afterReleaseLocked()
        if (released) {
            // Given back to the engine outside the lock: an engine call, and no longer reachable
            // from the funding gate once it left the map above. In a hold of its own, so it never
            // runs underneath a wipe's SDK teardown; a wipe drops the reservation anyway.
            heldReservation?.let { reservation ->
                try {
                    whileOwned(origin) { engine.release(reservation) }
                } catch (e: WalletWipedException) {
                    log.info("not giving back the reservation of {}: its wallet is being wiped", tx.txId)
                }
            }
        }
        return now.payment
    }

    /** What [releaseLocked] leaves stored: null when the payment was settled meanwhile. */
    private class Stored(val payment: PendingDirectPayment?)

    private suspend fun releaseLocked(
        tx: Transaction,
        payment: PendingDirectPayment,
        origin: Wallet,
        abandoned: PendingDirectPayment,
        onReleased: (SdkDeferredPayment?) -> Unit
    ): Stored = Stored(
        recordMutex.withLock {
            currentCoroutineContext().ensureActive()
            val stored = config.getAllOrThrow().firstOrNull { it.txId == tx.txId }
            if (stored == null || tx.txId in pendingRemovals) {
                log.info("possibly-sent tx {} was settled while its release was under way; not releasing it", tx.txId)
                return@withLock null
            }
            if (stored.revision != payment.revision) {
                log.info("possibly-sent tx {} was rewritten while its release was under way; not releasing it", tx.txId)
                return@withLock stored
            }
            try {
                config.add(abandoned) { stillOwnedBy(origin) }
            } catch (e: Exception) {
                log.error("could not record the release of {}, keeping it locked and pending", tx.txId, e)
                throw e
            }
            // The stored payment says abandoned now, so a restart will not lock these inputs
            // again, only carry on watching.
            unlockInputs(origin, tx)
            onReleased(reservations.remove(tx.txId))
            abandoned
        }
    )

    /**
     * Whether [tx] can never confirm: one of its inputs is spent by a DIFFERENT transaction whose
     * place in the chain is FINAL (review 4297952515). Inclusion in a block is not enough: a
     * shallow reorganisation could take that spend out and make [tx] valid again after its records
     * were deleted. Final follows [ChainLockedCoinSelector]: chainlocked, or at or below the best
     * known chainlock height, or [ChainLockedCoinSelector.FALLBACK_CONFIRMATIONS] deep when no
     * chainlock height is known. Asked of the dashj wallet (its own view of the parent outputs) and
     * of the SDK engine's store. Affirmative evidence only; anything unknown is false, and the
     * record and its watch stay.
     */
    private suspend fun isInvalidated(tx: Transaction, origin: Wallet): Boolean {
        if (dashjConflictFinal(origin, tx)) return true
        return try {
            engine.inputSpentByFinalConflict(tx) == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not ask the SDK engine about conflicting spends of {}", tx.txId, e)
            false
        }
    }

    private suspend fun dashjConflictFinal(wallet: Wallet, tx: Transaction): Boolean = try {
        val state = blockchainStateProvider.getState()
        tx.inputs.any { input ->
            val parent = wallet.getTransaction(input.outpoint.hash) ?: return@any false
            val spender = parent.outputs.getOrNull(input.outpoint.index.toInt())?.spentBy?.parentTransaction
                ?: return@any false
            val confidence = spender.confidence
            spender.txId != tx.txId &&
                confidence.confidenceType == TransactionConfidence.ConfidenceType.BUILDING &&
                (
                    confidence.isChainLocked ||
                        ChainLockedCoinSelector.isChainLockedHeight(
                            confidence.appearedAtChainHeight,
                            state?.chainlockHeight ?: 0,
                            state?.bestChainHeight ?: 0
                        )
                    )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("could not check the wallet for conflicting spends of {}", tx.txId, e)
        false
    }

    /**
     * Drops a payment that can never confirm: its inputs are freed, its reservation given back,
     * everything recorded for it (gift cards, metadata, queued platform changes) removed, and then
     * its record, which lifts the block on its invoice.
     *
     * Settled inside the same boundary a reactivation uses: a SHARED hold on the
     * [FundingSelectionLock] (so a reactivation's exclusive section is never interleaved with it),
     * then [recordMutex], under which the stored record is read again. Like [release], it acts
     * only on the record it decided on: a record rewritten since ([PendingDirectPayment.revision]),
     * say reactivated by a resubmission, is left exactly as it is (no unlock, no reservation
     * release, no forget, no removal) and the watch carries on from it; the next round decides
     * again from the record as it is now.
     *
     * @return null when the watch has nothing left to do (discarded here, or settled meanwhile),
     *   otherwise the record to carry on watching: [payment] itself when its records could not be
     *   removed yet, or the newer record that superseded it
     */
    private suspend fun discardInvalidated(
        tx: Transaction,
        payment: PendingDirectPayment,
        origin: Wallet
    ): PendingDirectPayment? = fundingSelection.withShared {
        var heldReservation: SdkDeferredPayment? = null
        val outcome = recordMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!stillOwnedBy(origin)) {
                // the wipe owns everything of this wallet now
                return@withLock null
            }
            val stored = config.getAllOrThrow().firstOrNull { it.txId == tx.txId }
            if (stored == null || tx.txId in pendingRemovals) {
                log.info("{} was settled while its invalidation was under way; nothing to discard", tx.txId)
                jobs.remove(tx.txId)
                return@withLock null
            }
            if (stored.revision != payment.revision) {
                log.info("{} was rewritten while its invalidation was under way; not discarding it", tx.txId)
                return@withLock stored
            }
            log.info("{} can never confirm: a confirmed transaction spends one of its inputs; dropping it", tx.txId)
            unlockInputs(origin, tx)
            heldReservation = reservations.remove(tx.txId)
            val forgotten = try {
                metadataProvider.forgetTransaction(payment.txId.toTxId())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("could not discard the records of invalidated payment {}", payment.txId, e)
                false
            }
            if (!forgotten) {
                log.warn("records of invalidated payment {} are still present, retrying", payment.txId)
                return@withLock payment
            }
            removeRecord(payment.txId)
            jobs.remove(payment.txId)
            null
        }
        // Given back to the engine outside the record lock, as release() does.
        heldReservation?.let { engine.release(it) }
        outcome
    }

    /**
     * The released submission of [paymentRequestId] (or of any refresh of its order, [orderId])
     * made by [origin], if there is one on record:
     * the exact transaction a re-confirmation of that invoice resubmits instead of building a new
     * one (review 9fa90c30ccf1).
     */
    suspend fun abandonedSubmissionFor(
        paymentRequestId: String?,
        origin: Wallet,
        orderId: String? = null,
        /** As for [unresolvedSubmissionFor]. */
        legacyOrderId: String? = null
    ): PendingDirectPayment? {
        // No url: a record with no order identity is never resubmitted for another request.
        val invoice = Invoice(paymentRequestId, orderId, legacyOrderId, paymentUrl = null)
        if (invoice.isEmpty) return null
        val walletId = walletIdentity(origin)
        return config.getAllOrThrow().firstOrNull {
            it.isForInvoice(invoice) &&
                (it.abandoned || it.watchExpired) &&
                it.txId !in pendingRemovals &&
                belongsTo(it, origin, walletId)
        }
    }

    /**
     * The payee acknowledged a resubmitted [tx]: complete it from its stored record as any
     * acknowledged payment is completed. Never drops recovery on the way:
     * - already settled by its watch while the request was in flight (the payee broadcast it and
     *   the watch committed it): that committed transaction is the result;
     * - the SDK owns L1: nothing here can relay T1 (its reservation was released when it went
     *   unseen, and the dashj peers are held), so an acknowledgement alone proves no delivery. It
     *   stays recorded and watched, and is reported pending until the network shows it;
     * - finalising fails part way (attribution, metadata): the watch is kept, or started again, to
     *   finish it once it is seen, and the outcome is reported pending.
     *
     * @throws PaymentSubmissionPendingException in the last two cases, and when the record is gone
     *   but the transaction is not in the wallet either
     */
    suspend fun commitAcknowledged(tx: Transaction, origin: Wallet): Transaction {
        // Inside the wipe barrier, with ownership asked again under it (outermost, before
        // settleMutex): the payee has acknowledged it, so a wipe that refuses or cancels this
        // leaves its outcome unknown, never "not sent".
        val wiped = { cause: Throwable? ->
            PaymentSubmissionPendingException(
                tx.txId.toString(),
                cause,
                "payment ${tx.txId} was acknowledged by the payee, but its wallet is being wiped"
            )
        }
        return try {
            whileOwned(origin) { settleMutex.withLock { commitAcknowledgedLocked(tx, origin) } }
                ?: throw wiped(null)
        } catch (e: WalletWipedException) {
            throw wiped(e)
        } catch (e: WalletWipedCancellationException) {
            currentCoroutineContext().ensureActive()
            throw wiped(e)
        }
    }

    private suspend fun commitAcknowledgedLocked(tx: Transaction, origin: Wallet): Transaction {
        val payment = config.getAllOrThrow().firstOrNull { it.txId == tx.txId && it.txId !in pendingRemovals }
        if (payment == null) {
            origin.takeIf { stillOwnedBy(it) }?.getTransaction(tx.txId)?.let {
                log.info("acknowledged payment {} was already settled by its watch", tx.txId)
                return it
            }
            throw PaymentSubmissionPendingException(tx.txId.toString(), null)
        }
        // Fail closed (review 4306695898): finished here only when the dashj route that can relay
        // it is known to be live. An unreadable cutover state is treated as the SDK owning L1.
        val sdkOwnsL1 = engine.sdkOwnsL1OrUnknown()
        if (sdkOwnsL1 != false) {
            log.info(
                "payment {} acknowledged; {}, watching for it on the network",
                tx.txId,
                if (sdkOwnsL1 == null) "the cutover state cannot be read" else "nothing here can relay it"
            )
            track(tx, payment, origin)
            throw PaymentSubmissionPendingException(
                tx.txId.toString(),
                null,
                "payment ${tx.txId} was acknowledged by the payee; waiting to see it on the network"
            )
        }
        val watch = jobs[tx.txId]
        return try {
            commit(tx, payment, origin).also { watch?.cancel() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("could not finalise acknowledged payment {}; its watch finishes it", tx.txId, e)
            track(tx, payment, origin)
            throw PaymentSubmissionPendingException(tx.txId.toString(), e)
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
        // Settled either way: the watch ends here even when the removal has to be retried. Under
        // the scan's per-record lock, like cancelQuarantine: a scan applying an older read must
        // find the record gone, not an untracked record to lock and watch again.
        recordMutex.withLock {
            removeRecord(payment.txId)
            jobs.remove(payment.txId)
        }
    }

    /**
     * Removes the stored record of a payment whose outcome is settled. Never throws: a removal that
     * fails is remembered in [pendingRemovals], which makes the record count as resolved in this
     * process, and retried with backoff ([retryPendingRemovals]) until it lands. Without that, a
     * transient storage error left an active record with no watch and no reservation, which the
     * funding gate reads as unprotected and the gift card check as outstanding, until the next
     * process start: nothing else scans again once restoration has succeeded.
     */
    private suspend fun removeRecord(txId: Sha256Hash) {
        pendingRemovals.add(txId)
        try {
            config.remove(txId)
            pendingRemovals.remove(txId)
        } catch (e: CancellationException) {
            scheduleRemovalRetry()
            throw e
        } catch (e: Exception) {
            log.error("could not remove the record of settled payment {}; retrying", txId, e)
            scheduleRemovalRetry()
        }
    }

    /**
     * Starts the retry worker unless one is running. "Running" means its reference is still set:
     * the worker clears it itself, under the same lock, at the moment it decides to retire
     * ([retireRemovalWorkerIfDrained]), so a removal queued after that decision always starts a
     * new worker, and one queued before it is seen by the worker's own check.
     */
    private fun scheduleRemovalRetry() {
        synchronized(pendingRemovals) {
            if (removalRetryJob?.isActive == true) return
            removalRetryJob = scope.launch { retryPendingRemovals() }
        }
    }

    /**
     * The worker's exit decision, atomic with [scheduleRemovalRetry]: if nothing is queued it gives
     * up its reference and retires (true); otherwise it carries on (false). The queue is added to
     * before scheduling, so an id queued at any point is either seen here or starts a new worker.
     */
    private fun retireRemovalWorkerIfDrained(): Boolean = synchronized(pendingRemovals) {
        if (pendingRemovals.isNotEmpty()) return@synchronized false
        removalRetryJob = null
        true
    }

    /** Retries every pending removal with doubling backoff until none is left. */
    private suspend fun retryPendingRemovals() {
        var wait = removalRetryInitialMs
        while (true) {
            if (pendingRemovals.isEmpty()) {
                afterRemovalQueueDrained()
                if (retireRemovalWorkerIfDrained()) return
            }
            delay(wait)
            for (txId in pendingRemovals.toList()) {
                afterRemovalSnapshot(txId)
                try {
                    // Re-checked under the lock a quarantine takes: one that made this payment
                    // active again since the list was taken has also taken it out of the queue,
                    // and the record now stored is its new one (review 4302741459).
                    recordMutex.withLock {
                        if (txId in pendingRemovals) {
                            config.remove(txId)
                            pendingRemovals.remove(txId)
                            log.info("removed the record of settled payment {} on retry", txId)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("still could not remove the record of settled payment {}", txId, e)
                }
            }
            wait = minOf(wait * 2, REMOVAL_RETRY_MAX_MS)
        }
    }

    /**
     * A fresh [PendingDirectPayment.revision] for a write of a record. Random rather than counted,
     * so it needs no read of the record it replaces; never 0, which marks a record written before
     * revisions were stored.
     */
    private fun newRevision(): Long {
        var revision: Long
        do {
            revision = ThreadLocalRandom.current().nextLong()
        } while (revision == 0L)
        return revision
    }

    private fun lockInputs(wallet: Wallet, tx: Transaction) {
        tx.inputs.forEach { wallet.lockOutput(it.outpoint) }
    }

    private fun unlockInputs(wallet: Wallet, tx: Transaction) {
        tx.inputs.forEach { wallet.unlockOutput(it.outpoint) }
    }
}

/** The lower-cased host of [url], or null when it has none (a `dash:` URI) or cannot be parsed. */
private fun hostOf(url: String?): String? = try {
    url?.let { URI(it).host }?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}
