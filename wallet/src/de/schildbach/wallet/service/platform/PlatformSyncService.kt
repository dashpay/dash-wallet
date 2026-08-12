/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.service.platform

import android.app.ActivityManager
import android.content.Intent
import android.text.format.DateUtils
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.util.concurrent.SettableFuture
import com.google.zxing.BarcodeFormat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.dao.UsernameVoteDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import de.schildbach.wallet.database.entity.TransactionMetadataDocument
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.security.SecurityGuardException
import de.schildbach.wallet.service.BlockchainService
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.service.platform.sdk.CutoverAutoCommitObserver
import de.schildbach.wallet.service.platform.sdk.CutoverTxSeamService
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.service.platform.sdk.SdkBlockchainStateService
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.NonInteractiveWalletUnlock
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.SdkIdentityVerifyQueries
import de.schildbach.wallet.service.platform.sdk.SdkProfileQueries
import de.schildbach.wallet.service.platform.sdk.SdkUsernameQueries
import de.schildbach.wallet.service.platform.sdk.SdkWalletBinder
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.dashpay.OnContactsUpdated
import de.schildbach.wallet.ui.dashpay.OnPreBlockProgressListener
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.PreBlockStage
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.more.TxMetadataSaveFrequency
import de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.EvolutionContact
import org.bouncycastle.crypto.params.KeyParameter
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.TransactionCategory
import de.schildbach.wallet.transactions.WalletTransactionFilter
import org.dash.wallet.common.util.TickerFlow
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dashj.platform.contracts.wallet.TxMetadataDocument
import org.dashj.platform.dashpay.ContactRequest
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.dpp.voting.ContestedDocumentResourceVotePoll
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.dashj.platform.wallet.IdentityVerify
import org.dashj.platform.wallet.TxMetadataItem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import javax.inject.Inject
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import de.schildbach.wallet.util.StartupBreadcrumbs
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

interface PlatformSyncService {
    fun init()
    suspend fun initSync(runFirstUpdateBlocking: Boolean = false)
    fun resume()
    suspend fun shutdown()

    /**
     * Unconditionally stop the platform sync machinery and (bounded) wait for
     * in-flight iterations to die. Called by the wallet reset/rescan path
     * BEFORE the persisted identity is cleared: a live sync iteration holds a
     * pre-clear [BlockchainIdentityData] and would re-persist ("resurrect") it
     * after the clear. Unlike [shutdown], this does not gate on an identity
     * being present — the reset path may already have nulled it, which is
     * exactly why [shutdown] used to leave the ticker running across a reset.
     * Sync restarts naturally with the next blockchain service start
     * (preBlockDownload → initSync).
     */
    suspend fun stopSync()

    /**
     * Unconditionally stop the two Kotlin-SDK background engines (the L1
     * shadow SPV and the shielded sync loop). Both stops are best-effort,
     * failure-contained no-ops when not running. Split out of [shutdown]
     * because DEBUG builds deliberately SKIP the engine stops on the routine
     * service teardown (see [shutdown]) — the destructive paths (wallet
     * wipe, before `finalizeWipe()` deletes app data) call this directly so
     * the engines are provably down regardless of build type.
     */
    suspend fun stopSdkEngines()

    fun updateSyncStatus(stage: PreBlockStage)
    fun preBlockDownload(future: SettableFuture<Boolean>)

    /**
     * Identity/username/contacts discovery + recovery, extracted from
     * [preBlockDownload] so it can be triggered from a post-cutover synced hook
     * (where the dashj peerGroup — and thus the PreBlocksDownloadListener that
     * used to call [preBlockDownload] — never starts). Idempotent and safe to
     * call repeatedly. Returns true when it enqueued a RestoreIdentityOperation
     * (the caller must NOT resume/finish its own sync — the worker drives the
     * rest), false otherwise.
     */
    suspend fun discoverAndRecoverIdentity(): Boolean

    suspend fun updateContactRequests(initialSync: Boolean = false)

    /**
     * Fire-and-forget [updateContactRequests] pass on the sync scope. For
     * callers that want local contact state reconciled soon but must not
     * block or fail on it — e.g. the post-send reconcile fallback in
     * [PlatformBroadcastService], which defers to contact sync when the
     * just-broadcast request cannot be fetched back promptly. No-ops into
     * the "already running" guard when a pass is in flight.
     */
    fun requestContactUpdate()
    fun postUpdateBloomFilters()
    suspend fun updateUsernameRequestsWithVotes()
    suspend fun updateUsernameRequestWithVotes(username: String)
    suspend fun checkUsernameVotingStatus()

    fun addContactsUpdatedListener(listener: OnContactsUpdated)
    fun removeContactsUpdatedListener(listener: OnContactsUpdated?)
    fun fireContactsUpdatedListeners()

    suspend fun triggerPreBlockDownloadComplete()

    fun addPreBlockProgressListener(listener: OnPreBlockProgressListener)
    fun removePreBlockProgressListener(listener: OnPreBlockProgressListener)

    suspend fun clearDatabases()
    suspend fun getUnsavedTransactions(): Pair<List<Transaction>, Long>
    suspend fun hasPendingTxMetadataToSave(): Boolean
}

class PlatformSynchronizationService @Inject constructor(
    private val platform: PlatformService,
    private val platformRepo: PlatformRepo,
    private val analytics: AnalyticsService,
    private val config: DashPayConfig,
    private val walletApplication: WalletApplication,
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val transactionMetadataChangeCacheDao: TransactionMetadataChangeCacheDao,
    private val transactionMetadataDocumentDao: TransactionMetadataDocumentDao,
    private val blockchainIdentityDataDao: BlockchainIdentityConfig,
    private val dashPayProfileDao: DashPayProfileDao,
    private val dashPayContactRequestDao: DashPayContactRequestDao,
    private val dashPayConfig: DashPayConfig,
    private val giftCardDao: GiftCardDao,
    private val invitationsDao: InvitationsDao,
    private val usernameRequestDao: UsernameRequestDao,
    private val usernameVoteDao: UsernameVoteDao,
    private val identityConfig: BlockchainIdentityConfig,
    private val topUpRepository: TopUpRepository,
    private val identityRepository: IdentityRepository,
    private val walletDataProvider: WalletData,
    private val sdkProfileQueries: SdkProfileQueries,
    private val sdkUsernameQueries: SdkUsernameQueries,
    private val sdkIdentityVerifyQueries: SdkIdentityVerifyQueries,
    private val sdkWalletBinder: SdkWalletBinder,
    private val nonInteractiveWalletUnlock: NonInteractiveWalletUnlock,
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val cutoverUiDataService: CutoverUiDataService,
    private val sdkBlockchainStateService: SdkBlockchainStateService,
    private val cutoverTxSeamService: CutoverTxSeamService,
    private val cutoverAutoCommitObserver: CutoverAutoCommitObserver,
    private val shieldedTransferExecutor: ShieldedTransferExecutor,
    private val contactRequestNotificationService: ContactRequestNotificationService,
) : PlatformSyncService {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(PlatformSynchronizationService::class.java)
        private val random = Random(System.currentTimeMillis())

        val UPDATE_TIMER_DELAY = 15.seconds

        /**
         * Delay before re-running [updateContactRequests] after a FAILED run
         * (see [ContactUpdateRetryPolicy]): long enough not to hammer a broken
         * platform connection, short enough that a single transient crash
         * doesn't silence contact discovery until the next app-lifecycle
         * trigger (observed live: 16 minutes).
         */
        val CONTACT_UPDATE_RETRY_DELAY = 45.seconds
        val PUSH_PERIOD = if (BuildConfig.DEBUG || Constants.IS_TESTNET_BUILD) 3.minutes else 3.hours
        val WEEKLY_PUSH_PERIOD = 7.days.inWholeMilliseconds
        val CUTOFF_MIN = if (BuildConfig.DEBUG || Constants.IS_TESTNET_BUILD) 3.minutes else 3.hours
        val CUTOFF_MAX = if (BuildConfig.DEBUG || Constants.IS_TESTNET_BUILD) 6.minutes else 6.hours
        private val PUBLISH = MarkerFactory.getMarker("PUBLISH")
        val NON_CONTACTS_UPDATE_PERIOD = 1.minutes.inWholeMilliseconds
        val STOP_SYNC_JOIN_TIMEOUT = 5.seconds

        /**
         * How long the [updatingContacts] guard may be held before a new
         * [updateContactRequests] caller declares the holder hung, cancels it
         * and takes over. Generous on purpose: an initial sync on a large
         * wallet (hundreds of contacts, per-contact profile fetches) may
         * legitimately run for many minutes, and a healthy pass being
         * cancelled here would restart contact sync from scratch. The failure
         * this bounds was previously UNBOUNDED: a pass wedged inside a
         * platform call held the guard until app restart, with every later
         * attempt logging "already running" and returning.
         */
        const val CONTACT_SYNC_STALE_TAKEOVER_MS = 30 * 60 * 1000L

        /**
         * Whether the dashpay DATA CONTRACT itself is loaded — a strictly
         * stronger condition than `hasApp("dashpay")`, which only checks the
         * app REGISTRATION (contract id) is configured. Rebuilding a
         * [org.dashj.platform.dashpay.ContactRequest] goes through
         * `Documents.create` -> `Contracts.get`, which returns the app
         * definition's cached contract — null until the contract document has
         * actually been fetched, and `Documents.create` NPEs on that null.
         * Guard any code that BUILDS dashpay documents with this, not with
         * hasApp.
         */
        @JvmStatic
        internal fun isDashPayContractLoaded(platform: org.dashj.platform.sdk.platform.Platform): Boolean =
            runCatching { platform.apps["dashpay"]?.contract != null }.getOrDefault(false)
    }

    private var platformSyncJob: Job? = null
    private var txMetadataJob: Job? = null

    /**
     * Bounded retry after a FAILED [updateContactRequests] run — see
     * [ContactUpdateRetryPolicy] for the live incident this fixes. The policy
     * is the pure decision seam; [contactUpdateRetryJob] is the one pending
     * delayed re-run (child of [syncScope], so it dies with the scope on
     * shutdown/reset).
     */
    private val contactUpdateRetryPolicy = ContactUpdateRetryPolicy()
    private var contactUpdateRetryJob: Job? = null
    private val updatingContacts = AtomicBoolean(false)

    /**
     * Owner ([Job]) and claim time of the in-flight [updateContactRequests]
     * pass. Together they make the [updatingContacts] guard recoverable and
     * release-safe: a caller that finds the guard held longer than
     * [CONTACT_SYNC_STALE_TAKEOVER_MS] cancels the recorded owner and takes
     * over, and the finally-block release CASes on the owner so a cancelled
     * pass's late unwind cannot clear the flag out from under its successor
     * (the same CAS also stops the first finisher of two RecoveryComplete-
     * overlapped passes from releasing the guard while the second still runs).
     */
    private val updatingContactsOwner = AtomicReference<Job?>(null)
    private val updatingContactsSince = AtomicLong(0L)

    /** Clock behind the [updatingContacts] staleness arithmetic — a test seam. */
    internal var contactSyncClock: () -> Long = { System.currentTimeMillis() }
    private val preDownloadBlocks = AtomicBoolean(false)
    private var preDownloadBlocksFuture: SettableFuture<Boolean>? = null

    /**
     * FIX 1 guard: at most one in-flight identity discovery ([getIdentityFromPublicKeyId]
     * is a network DAPI call). Latched only once an identity is actually found and a
     * [RestoreIdentityOperation] enqueued; reset when no identity is found so a later
     * caller (a subsequent peerGroup start pre-cutover, or the next SDK synced tick
     * post-cutover) may retry — i.e. "runs discovery at most once per process until an
     * identity is found".
     */
    private val identityDiscoveryInFlight = AtomicBoolean(false)

    private val onContactsUpdatedListeners = arrayListOf<OnContactsUpdated>()
    private val onPreBlockContactListeners = arrayListOf<OnPreBlockProgressListener>()
    private var lastPreBlockStage: PreBlockStage = PreBlockStage.None
    // TODO: cancel these on shutdown?
    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob)
    private var lastTopupUpdateTime = 0L
    private var lastMetadataUpdateTime = 0L

    override fun init() {
        syncScope.launch {
            identityRepository.init()
            initializeStateRepository()
        }
        // Phase 3f (docs/kotlin-sdk-migration-plan.md): bind the app wallet
        // into the Kotlin SDK and attach its identity — fire-and-forget so
        // platform-sync startup latency is unaffected, and provably inert
        // unless a USE_KOTLIN_SDK_* flag is on. The unlock provider runs
        // only after the binder's eligibility gate passes: it recovers the
        // wallet-crypter key non-interactively ([NonInteractiveWalletUnlock]
        // — the SecurityGuard-stored password, no user prompt; extracted so
        // the L1 shadow recovery path reuses the identical recipe).
        kickSdkEngines()
        log.info("Starting the platform sync job")
    }

    // Phase 5a (docs/kotlin-sdk-migration-plan.md): after the bind pass
    // finishes (success or not — the service re-checks the bound state
    // itself), kick the L1 shadow-sync parity harness. Fire-and-forget,
    // provably inert unless USE_KOTLIN_SDK_L1_SHADOW is on (debug-only
    // instrumentation — it runs a second SPV engine), and failures are
    // logged+swallowed inside startIfEnabled(). Bind is single-flight and
    // startIfEnabled() is idempotent, so re-running the recipe is safe.
    private fun kickSdkEngines() {
        StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_SDK_BIND_KICKED, "SDK_BIND_KICKED")
        val bindJob = sdkWalletBinder.bindInBackground(nonInteractiveWalletUnlock::unlockOrNull)
        syncScope.launch {
            bindJob.join()
            // Async-lane breadcrumbs around the NATIVE engine start: a
            // deterministic Rust/JNI crash on resume leaves no Java trace, so a
            // crash-looped install whose previous-launch trail repeatedly ends
            // at SDK_L1_ENGINE_STARTING is the fingerprint that convicts the
            // native engine (see StartupBreadcrumbs).
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_SDK_L1_ENGINE_STARTING, "SDK_L1_ENGINE_STARTING")
            l1ShadowSyncService.startIfEnabled()
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_SDK_L1_ENGINE_STARTED, "SDK_L1_ENGINE_STARTED")
            // Phase 5d follow-up: the post-cutover UI data source (balance
            // header / tx list / coins-received detection served from the
            // SDK once the cutover is committed). Idempotent once-per-process
            // start; provably inert pre-cutover — see CutoverUiDataService.
            cutoverUiDataService.start()
            // Kill-list Step B (sync-state track): post-cutover the app-wide
            // BlockchainState (sync %, best height/date, stage, stalled
            // NETWORK impediment) derives from the SDK SPV feed. Idempotent,
            // equality-gated, provably inert pre-cutover — see
            // SdkBlockchainStateService.
            sdkBlockchainStateService.start()
            // Step B7: the post-cutover wallet-data seam (WalletDataProvider
            // tx reads served from the SDK store). Same lifecycle and the
            // same provably-inert-pre-cutover contract as above.
            cutoverTxSeamService.start()
            // Phase 5d AUTO-COMMIT: on an UPGRADE install (existing dashj
            // wallet), drive the cutover to SDK-primary with no debug
            // broadcast once the SDK's scan has caught up to the tip AND the
            // full readiness policy passes. Fail-safe (never a forced/timeout
            // commit), self-gating (inert until the shadow catches up), and
            // once-per-process idempotent. Restore/new wallets skip this and
            // commit immediately at setWallet — see
            // CutoverCoordinator.commitForFreshWalletSetup.
            cutoverAutoCommitObserver.start()
            // Bring the SHIELDED runtime up at startup too (Brian): it used
            // to start only when a shielded UI screen called
            // ensureShieldedReady(), so until the user visited More (or a
            // username/invite flow) every other surface read a NOT_READY
            // pool and "falsely thought there were no shielded funds" — and
            // cutover readiness stalled on an untouched launch. Idempotent,
            // self-gated (returns false unless USE_KOTLIN_SDK_SHIELDED is
            // on), and it kicks the pending-wallet-shield resume sweep;
            // syncNow() then lands fresh notes promptly. stopSdkEngines()
            // remains the symmetric teardown.
            // …and listen for what that sweep finishes BEFORE kicking it:
            // a wallet shield interrupted after its L1 asset lock broadcast
            // completes here, in the background, with no screen open — the
            // user was promised it "will finish automatically" and nothing
            // ever told them it had. Idempotent, once per process.
            StartupBreadcrumbs.mark(StartupBreadcrumbs.STAGE_CUTOVER_SERVICES_STARTED, "CUTOVER_SERVICES_STARTED")
            shieldedTransferExecutor.startObservingBackgroundShields()
            launch {
                runCatching {
                    if (shieldedBalanceService.ensureShieldedReady()) {
                        shieldedBalanceService.syncNow()
                    }
                }.onFailure { log.warn("startup shielded bring-up failed", it) }
            }
            // DIP-15 friend-chain backfill (docs/kotlin-sdk-migration-plan.md;
            // scratchpad/txdiff/FINDINGS.md): the app keeps DashPay contacts on
            // dashj and never drives the SDK's contact-sync path, so the bound
            // SDK L1 wallet derives NONE of the m/9'/coin'/15' friend chains and
            // misses contact/username payments. Provision them now that the bind
            // pass has (attempted to) attach the identity. Forced so it runs
            // promptly on every service (re)start; fire-and-forget and provably
            // inert unless a USE_KOTLIN_SDK_* flag is on and a wallet is bound.
            sdkWalletBinder.provisionContactAccountsInBackground(force = true)
        }
    }

    override fun resume() {
        // shutdown() stops the Kotlin-SDK background engines whenever the
        // blockchain service is torn down (unclean-kill corruption guard),
        // but this process outlives the service — so every service
        // (re)start must kick them again, or the shadow parity harness
        // stays down for the rest of the process lifetime and the shielded
        // transfer gate never reopens ("Verifying your balance" forever).
        kickSdkEngines()
    }

    override suspend fun initSync(runFirstUpdateBlocking: Boolean) {
        if (runFirstUpdateBlocking) {
            updateContactRequests(true)
        }
        // Idempotent re-arm: initSync is reachable from several triggers (the
        // dashj preBlockDownload listener, identity creation, identity restore,
        // and the post-cutover synced hook in BlockchainServiceImpl). Cancel any
        // previous tickers first so a second trigger within one service lifetime
        // can never stack a duplicate poll.
        platformSyncJob?.cancel(CancellationException("re-arming the platform sync ticker"))
        txMetadataJob?.cancel(CancellationException("re-arming the tx metadata ticker"))
        platformSyncJob = TickerFlow(UPDATE_TIMER_DELAY)
            .onEach { updateContactRequests() }
            .launchIn(syncScope)

        txMetadataJob = TickerFlow(PUSH_PERIOD)
            .onEach {
                maybePublishChangeCache()
            }
            .launchIn(syncScope)
    }

    private suspend fun maybePublishChangeCache() {
        val saveSettings = dashPayConfig.getTransactionMetadataSettings()
        if (!saveSettings.saveToNetwork) {
            return
        }
        val lastPush = config.get(DashPayConfig.LAST_METADATA_PUSH) ?: 0
        // maybe we don't need this
        // val lastTransactionTime = transactionMetadataChangeCacheDao.lastTransactionTime()
        val txIds = transactionMetadataChangeCacheDao.getAllTransactionIds()
        val now = System.currentTimeMillis()
        val everythingBeforeTimestamp = random.nextLong(
            now - CUTOFF_MAX.inWholeMilliseconds,
            now - CUTOFF_MIN.inWholeMilliseconds
        ) // Choose cutoff time between 3 and 6 hours ago

        // ensure that CUTOFF_MIN has elapsed since one or more tx timestamps with new metadata
        val timeStamps = txIds.map {
            transactionMetadataProvider.getTransactionMetadata(it)?.timestamp ?: Long.MAX_VALUE
        }.sortedByDescending { it }

        var newDataItems = txIds.size
        var newEverythingBeforeTimestamp = everythingBeforeTimestamp
        for (timestamp in timeStamps) {
            if (timestamp < everythingBeforeTimestamp) {
                newEverythingBeforeTimestamp = timestamp + 1
                break
            } else {
                newDataItems--
            }
        }
        log.info("maybe publish $newDataItems of ${txIds.size} with timestamps ${timeStamps.map { Date(it) } } < ${Date(newEverythingBeforeTimestamp)}")

        // determine how many transactions meet the cut off time
        // val newDataItems = transactionMetadataChangeCacheDao.countTransactions(newEverythingBeforeTimestamp)

        val meetsSaveFrequency = when (saveSettings.saveFrequency) {
            TxMetadataSaveFrequency.afterTenTransactions -> newDataItems >= 10
            TxMetadataSaveFrequency.afterEveryTransaction -> newDataItems >= 1
            TxMetadataSaveFrequency.oncePerWeek -> lastPush < now - WEEKLY_PUSH_PERIOD && newDataItems >= 1
        }
        // publish no more frequently than every 3 hours
        val shouldPushToNetwork = (lastPush < now - PUSH_PERIOD.inWholeMilliseconds)
        if (shouldPushToNetwork && meetsSaveFrequency) {
            log.info("maybe publish meets requirements")
            publishChangeCache(newEverythingBeforeTimestamp, saveAll = false)
        } else {
            log.info("last platform push was less than $CUTOFF_MIN ago, skipping")
        }
    }

    override suspend fun shutdown() {
        // Best-effort teardown of the Kotlin-SDK background engines. The
        // shadow SPV service had NO stop path before this (its Rust header
        // store was observed regressing after unclean kills — the suspected
        // trigger of the inflated-SDK parity corruption), so stop both it
        // and the shielded sync loop whenever the blockchain service is
        // torn down. Both stops are no-ops when not running and must never
        // block the rest of the cleanup.
        //
        // DEBUG builds skip the engine stops here — a deliberate battery
        // trade-off for testing: dashj's BlockchainService stops itself
        // whenever it idles ("idling detected, stopping service"), and every
        // engine restart left the SDK's SPV with stale quorum state — a live
        // shielded transfer right after such a restart could not verify its
        // asset lock's InstantSend lock and waited a full block (~3 min).
        // Keeping the engines warm across these routine teardowns removes
        // that window. The destructive paths are NOT weakened: the wallet
        // wipe calls stopSdkEngines() explicitly before finalizeWipe(), and
        // the shadow recovery paths (resetShadowState /
        // recoverByRecreatingWallet) stop the SPV directly themselves.
        if (BuildConfig.DEBUG) {
            log.info(
                "keeping Kotlin-SDK engines running across service teardown (debug): warm SPV " +
                    "avoids the asset-lock islock-verification delay; wipe/reset still stop " +
                    "them explicitly"
            )
        } else {
            stopSdkEngines()
        }

        if (platformSyncJob != null && identityRepository.hasBlockchainIdentity) {
            Preconditions.checkState(platformSyncJob!!.isActive)
            log.info("Shutting down the platform sync job")
            syncScope.coroutineContext.cancelChildren(CancellationException("shutdown the platform sync"))
            platformSyncJob!!.cancel(null)
            platformSyncJob = null
        }
        if (txMetadataJob != null && identityRepository.hasIdentity()) {
            if (txMetadataJob!!.isActive) {
                log.info("Shutting down the txmetdata publish job")
                syncScope.coroutineContext.cancelChildren(CancellationException("shutdown the platform sync"))
                txMetadataJob!!.cancel(null)
            }
            txMetadataJob = null
        }
    }

    override suspend fun stopSdkEngines() {
        runCatching { l1ShadowSyncService.stop() }
            .onFailure { log.warn("failed to stop the L1 shadow sync on shutdown", it) }
        runCatching { shieldedBalanceService.stop() }
            .onFailure { log.warn("failed to stop the shielded runtime on shutdown", it) }
    }

    override suspend fun stopSync() {
        log.info("stopping the platform sync machinery for a wallet reset/rescan")
        platformSyncJob?.cancel(CancellationException("wallet reset"))
        platformSyncJob = null
        txMetadataJob?.cancel(CancellationException("wallet reset"))
        txMetadataJob = null

        // Cancel every in-flight child (an iteration may hold a pre-reset
        // BlockchainIdentityData) and wait for them, bounded: a thread parked
        // in a non-cancellable network call must not stall the wipe. Any
        // straggler that outlives this window is caught by the
        // no-resurrection guard in IdentityRepositoryImpl.updateBlockchainIdentityData.
        val children = syncJob.children.toList()
        children.forEach { it.cancel(CancellationException("wallet reset")) }
        if (children.isNotEmpty()) {
            val stopped = withTimeoutOrNull(STOP_SYNC_JOIN_TIMEOUT.inWholeMilliseconds) {
                children.joinAll()
            } != null
            if (!stopped) {
                log.warn(
                    "platform sync children did not stop within {} — relying on the no-resurrection guard",
                    STOP_SYNC_JOIN_TIMEOUT
                )
            }
        }

        // Reset the in-memory sync state so a post-reset restart starts clean.
        updatingContacts.set(false)
        updatingContactsOwner.set(null)
        updatingContactsSince.set(0L)
        preDownloadBlocks.set(false)
        lastPreBlockStage = PreBlockStage.None
        hasCheckedTopups = false
        lastTopupUpdateTime = 0L
        lastMetadataUpdateTime = 0L
        log.info("platform sync machinery stopped")
    }

    var counterForReport = 0

    /**
     * updateContactRequests will fetch new Contact Requests from the network
     * and verify that we have all requests and profiles in the local database
     *
     * This method should not use blockchainIdentity because in some cases
     * when the app starts, it has not yet been initialized
     */
    override suspend fun updateContactRequests(initialSync: Boolean) {

        // if there is no wallet or identity, then skip the remaining steps of the update
        if (!identityRepository.hasBlockchainIdentity || walletApplication.wallet == null) {
            return
        }
        // Restore safety net: a registration-time SDK identity discovery
        // that failed (signer not attached yet — "External signable wallet
        // has no private key") is never retried by the SDK itself; without
        // a managed identity every contact pass below yields nothing (the
        // field 44-minute REQUESTED_NAME_CHECKING stall). Bounded +
        // backoff'd inside; no-op once managed/exhausted.
        sdkWalletBinder.maybeRetryIdentityDiscovery()
        log.info("updateContactRequests($initialSync) checking if can run")
        // only allow this method to execute once at a time
        // allow it to continue if the last state was recovery complete
        if (updatingContacts.get() && lastPreBlockStage != PreBlockStage.RecoveryComplete) {
            val since = updatingContactsSince.get()
            val heldMs = contactSyncClock() - since
            if (since == 0L || heldMs <= CONTACT_SYNC_STALE_TAKEOVER_MS) {
                log.info("updateContactRequests is already running: {}", lastPreBlockStage)
                return
            }
            // The guard has been held far beyond any healthy pass: the holder
            // is hung (observed live: a pass wedged inside a platform call
            // held the guard until app restart — "already running: None" on
            // every later attempt). Cancel it and take over; the ownership
            // CAS in this function's finally keeps the hung pass's late
            // release from clobbering the claim made just below.
            log.warn(
                "updateContactRequests guard held for {} ms (stage {}); cancelling the stale pass and taking over",
                heldMs,
                lastPreBlockStage
            )
            updatingContactsOwner.get()?.cancel(
                CancellationException("contact sync pass held the guard past ${CONTACT_SYNC_STALE_TAKEOVER_MS} ms; taken over")
            )
        }
        val thisPass = coroutineContext[Job]

        if (!platform.hasApp("dashpay")) {
            log.info("update contacts not completed because there is no dashpay contract")
            return
        }
        log.info("updateContactRequests($initialSync) starting now")

        try {
            val blockchainIdentityData = blockchainIdentityDataDao.load() ?: return
            if (blockchainIdentityData.creationState < IdentityCreationState.DONE) {
                // Is the Voting Period complete?
                if (blockchainIdentityData.creationState == IdentityCreationState.VOTING) {
                    val timeWindow = UsernameRequest.VOTING_PERIOD_MILLIS
                    val votingPeriodStart = blockchainIdentityData.votingPeriodStart ?: 0L
                    if (System.currentTimeMillis() - votingPeriodStart >= timeWindow) {
                        val resource = platformRepo.getUsername(blockchainIdentityData.username!!)
                        if (resource.status == Status.SUCCESS && resource.data != null) {
                            val domainDocument = DomainDocument(resource.data)
                            if (domainDocument.dashUniqueIdentityId == blockchainIdentityData.identity?.id) {
                                blockchainIdentityData.creationState =
                                    IdentityCreationState.DONE_AND_DISMISS
                                identityRepository.updateBlockchainIdentityData(blockchainIdentityData)
                            }
                        }
                    }
                }
                if (blockchainIdentityData.votingInProgress && !blockchainIdentityData.showSecondaryUsername) {
                    log.info("update contacts not completed username registration/recovery is not complete")
                    // if username creation or request is not complete, then allow the sync process to finish
                    if (preDownloadBlocks.get()) {
                        finishPreBlockDownload()
                    }
                    return
                }
            }

            if (blockchainIdentityData.username == null || blockchainIdentityData.userId == null) {
                return // this is here because the wallet is being reset without removing blockchainIdentityData
            }

            val userId = blockchainIdentityData.userId!!

            val userIdList = HashSet<String>()
            val watch = Stopwatch.createStarted()
            var addedContact = false
            /**
             * Whether this pass INSERTED a contact-request row, as opposed to
             * [addedContact], which is only true when a DIP-15 keychain was also
             * added to the wallet. The bell/badge must refresh on the former: a
             * request whose keychain already existed (or whose keychain add
             * failed) is still a brand new notification for the user, and gating
             * the listener fire on [addedContact] silently dropped it.
             */
            var insertedContactRequest = false
            /** Received requests newly inserted by this pass, for the system notification. */
            val newReceivedRequests = mutableListOf<DashPayContactRequest>()
            Context.propagate(platformRepo.walletApplication.wallet!!.context)

            val lastContactRequestTimeToMe = if (dashPayContactRequestDao.countAllRequestsToUser(userId) > 0) {
                val lastTimeStamp = dashPayContactRequestDao.getLastTimestampToUser(userId)
                // if the last contact request was received in the past 10 minutes, then query for
                // contact requests that are 10 minutes before it.  If the last contact request was
                // more than 10 minutes ago, then query all contact requests that came after it.
                if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10) {
                    lastTimeStamp
                } else {
                    lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
                }
            } else {
                0L
            }

            val lastContactRequestTimeFromMe = if (dashPayContactRequestDao.countAllRequestsFromUser(userId) > 0) {
                val lastTimeStamp = dashPayContactRequestDao.getLastTimestampFromUser(userId)
                // if the last contact request was received in the past 10 minutes, then query for
                // contact requests that are 10 minutes before it.  If the last contact request was
                // more than 10 minutes ago, then query all contact requests that came after it.
                if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10) {
                    lastTimeStamp
                } else {
                    lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
                }
            } else {
                0L
            }

            updatingContactsOwner.set(thisPass)
            updatingContactsSince.set(contactSyncClock())
            updatingContacts.set(true)
            updateSyncStatus(PreBlockStage.Starting)
            updateSyncStatus(PreBlockStage.Initialization)
            if (!initialSync) {
                checkDatabaseIntegrity(userId)
                updateSyncStatus(PreBlockStage.FixMissingProfiles)
            } else {
                // Refresh our own identity (revision/keys/balance) before the
                // full update. Non-fatal: this is a freshness optimization only,
                // and letting a failure here propagate previously aborted the
                // ENTIRE contact sync before the contact-request fetch below
                // ever ran (observed live, S21 2026-08-02: the legacy CBOR
                // identity-cache failure on our own contract-bound keys left an
                // iPhone acceptance undiscovered for ~16 minutes). The
                // repository call is itself cache-tolerant now (see
                // IdentityRepository.updateIdentity), so this guard only has to
                // absorb genuinely unexpected failures.
                try {
                    identityRepository.updateIdentity()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("could not refresh own identity before contact sync; continuing", e)
                }
            }

            // Get all out our contact requests
            val toContactDocuments = platform.contactRequests.get(
                userId,
                toUserId = false,
                afterTime = lastContactRequestTimeFromMe,
                retrieveAll = true
            )
            toContactDocuments.forEach {
                val contactRequest = ContactRequest(it)
                log.info("found accepted/sent request: ${contactRequest.toUserId}")
                val dashPayContactRequest = DashPayContactRequest.fromDocument(contactRequest)
                if (!dashPayContactRequestDao.exists(
                        dashPayContactRequest.userId,
                        dashPayContactRequest.toUserId,
                        contactRequest.accountReference
                    )
                ) {
                    log.info("adding accepted/send request to database: ${contactRequest.toUserId}")
                    userIdList.add(dashPayContactRequest.toUserId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)
                    insertedContactRequest = true

                    // add our receiving from this contact keychain if it doesn't exist
                    addedContact = checkAndAddSentRequest(userId, contactRequest) || addedContact
                    log.info("contactRequest: added sent request from ${contactRequest.toUserId}")
                }
            }
            updateSyncStatus(PreBlockStage.GetReceivedRequests)
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = platform.contactRequests.get(
                userId,
                toUserId = true,
                afterTime = lastContactRequestTimeToMe,
                retrieveAll = true
            )
            fromContactDocuments.forEach {
                val dashPayContactRequest = DashPayContactRequest.fromDocument(it)
                val contactRequest = ContactRequest(it)
                log.info("found received request: ${dashPayContactRequest.userId}")
                platform.stateRepository.addValidIdentity(dashPayContactRequest.userIdentifier)
                if (!dashPayContactRequestDao.exists(
                        dashPayContactRequest.userId,
                        dashPayContactRequest.toUserId,
                        dashPayContactRequest.accountReference
                    )
                ) {
                    log.info("adding received request: ${dashPayContactRequest.userId} to database")
                    userIdList.add(dashPayContactRequest.userId)
                    dashPayContactRequestDao.insert(dashPayContactRequest)
                    insertedContactRequest = true
                    newReceivedRequests.add(dashPayContactRequest)

                    // add the sending to contact keychain if it doesn't exist
                    addedContact = checkAndAddReceivedRequest(userId, contactRequest) || addedContact
                    log.info("contactRequest: added received request from ${contactRequest.ownerId}")
                }
            }
            updateSyncStatus(PreBlockStage.GetSentRequests)

            if (!initialSync) {
                // If new keychains were added to the wallet, then update the bloom filters
                if (addedContact) {
                    postUpdateBloomFilters()
                }

                // obtain profiles from new contacts
                if (userIdList.isNotEmpty()) {
                    updateContactProfiles(userIdList.toList(), 0L)
                }

                updateSyncStatus(PreBlockStage.GetNewProfiles)

                coroutineScope {
                    try {
                        val myEncryptionKey = platformRepo.getWalletEncryptionKey()

                        awaitAll(
                            async {
                                identityRepository.upgradeIdentity(platformRepo.getWalletEncryptionKey())
                            },
                            // fetch updated invitations
                            async {
                                if (Constants.SUPPORTS_INVITES) {
                                    topUpRepository.updateInvitations()
                                    // check for unused invites
                                    topUpRepository.checkInvites(myEncryptionKey)
                                    updateSyncStatus(PreBlockStage.GetInvites)
                                }
                            },
                            // fetch updated transaction metadata
                            async {
                                val shouldUpdate = System.currentTimeMillis() - lastMetadataUpdateTime >= NON_CONTACTS_UPDATE_PERIOD
                                if (shouldUpdate) {
                                    updateTransactionMetadata(myEncryptionKey)
                                    updateSyncStatus(PreBlockStage.TransactionMetadata)
                                    lastMetadataUpdateTime = System.currentTimeMillis()
                                }
                            }, // TODO: this is skipped in VOTING state, but shouldn't be
                            // fetch updated profiles from the network
                            async {
                                updateContactProfiles(userId, min(lastContactRequestTimeToMe, lastContactRequestTimeFromMe))
                                updateSyncStatus(PreBlockStage.GetUpdatedProfiles)
                            },
                            // check for unused topups
                            async {
                                val shouldUpdate = System.currentTimeMillis() - lastTopupUpdateTime >= NON_CONTACTS_UPDATE_PERIOD
                                if (shouldUpdate) {
                                    checkTopUps(myEncryptionKey)
                                    updateSyncStatus(PreBlockStage.Topups)
                                    lastTopupUpdateTime = System.currentTimeMillis()
                                }
                            }
                        )
                    } catch (e: Exception) {
                        log.error("error syncing secondary items: ", e)
                        return@coroutineScope
                    }
                }

            } else {
                if (config.get(DashPayConfig.FREQUENT_CONTACTS) == null) {
                    identityRepository.updateFrequentContacts()
                }
            }
            // Fire listeners if a contact request was inserted, whether or not a
            // keychain came with it. Gating this on `addedContact` alone meant a
            // received request whose sending keychain already existed never woke the
            // contacts/notification observers, so the home-screen bell stayed unlit.
            if (addedContact || insertedContactRequest) {
                fireContactsUpdatedListeners()
                // Post-restore contact-attribution repair: display-cache rows planned
                // by CutoverUiDataService BEFORE the DIP-15 friendship keychains were
                // (re)established are cached with contactUsername=NULL and would stay
                // that way until the next ticker tick. A contact (or its keychain) was
                // just added, so re-run the idempotent sync/plan pass with fresh
                // contact resolution now. Fire-and-forget; inert pre-cutover.
                cutoverUiDataService.requestContactReResolution()
            }

            // Refresh the unseen-notification badge and (when warranted) post the
            // system notification. Deliberately NOT gated on `addedContact` or on an
            // active observer: ContactRequestNotificationService is application-scoped,
            // so the count is recomputed even when no screen is listening to the fire
            // above. Fail-soft — it swallows and logs its own errors.
            contactRequestNotificationService.onContactRequestsSynced(newReceivedRequests, initialSync)

            // Keep the SDK L1 wallet's DIP-15 friend chains in step with the
            // dashj contact set: this dashj sync just (re)built the DashPay
            // receiving/sending keychains, but the SDK wallet only learns of
            // contacts through its own contact-sync path (never driven here).
            // Provision them so contact/username payments are captured on the
            // SDK scan too. Forced when a contact was just (un)established so
            // the new friend account provisions immediately; otherwise
            // throttled — the binder no-ops unless a USE_KOTLIN_SDK_* flag is
            // on and a wallet is bound. Fire-and-forget (never blocks sync).
            sdkWalletBinder.provisionContactAccountsInBackground(force = addedContact)

            updateSyncStatus(PreBlockStage.Complete)

            // A successful pass resets the failed-update retry budget and marks
            // any still-pending retry as redundant (it will no-op).
            contactUpdateRetryPolicy.onSuccess()

            log.info("updating contacts and profiles took $watch")
        } catch (_: CancellationException) {
            log.info("updating contacts canceled")
        } catch (e: Exception) {
            log.error(platformRepo.formatExceptionMessage("error updating contacts", e))
            // Don't go silent on failure: without this, a single crashed run
            // left contact discovery waiting for the next app-lifecycle
            // trigger (observed live: an acceptance undiscovered for ~16
            // minutes). Bounded — see scheduleContactUpdateRetry.
            scheduleContactUpdateRetry(initialSync)
        } finally {
            // Release only while this pass still owns the guard: after a
            // stale takeover (or a RecoveryComplete overlap) the guard
            // belongs to a newer pass, whose claim must survive this unwind.
            if (updatingContactsOwner.compareAndSet(thisPass, null)) {
                updatingContactsSince.set(0L)
                updatingContacts.set(false)
            }

            counterForReport++
            if (counterForReport % 8 == 0) {
                // record the report to the logs every 2 minutes
                log.info(platform.client.reportNetworkStatus())
            }
        }
        // This needs to be here to ensure that the pre-block download stage always completes
        // This block used to be the above finally block, but was moved here to fix some issues
        if (preDownloadBlocks.get()) {
            finishPreBlockDownload()
        }
    }

    /**
     * Schedule one bounded retry of a FAILED [updateContactRequests] run (see
     * [ContactUpdateRetryPolicy] for the incident and the budget semantics).
     * Scope-local — deliberately no WorkManager: the retry only matters while
     * this process (and [syncScope]) is alive, because a fresh process re-runs
     * the update from its own startup triggers anyway.
     *
     * Re-entrancy: a retry that fails again lands back here and consumes the
     * next unit of budget, so a persistent failure retries at most
     * [ContactUpdateRetryPolicy.DEFAULT_MAX_CONSECUTIVE_RETRIES] times
     * (~[CONTACT_UPDATE_RETRY_DELAY] apart) and then waits for an external
     * trigger. A success anywhere (including a concurrent ticker/resume run)
     * resets the budget and turns a pending retry into a no-op.
     */
    private fun scheduleContactUpdateRetry(initialSync: Boolean) {
        if (!contactUpdateRetryPolicy.onFailureShouldRetry()) {
            log.warn(
                "contact update failed {} consecutive times — retry budget exhausted; waiting for " +
                    "the next external trigger (ticker/resume/contacts screen)",
                contactUpdateRetryPolicy.failureCount
            )
            return
        }
        if (contactUpdateRetryJob?.isActive == true) {
            return
        }
        log.info(
            "contact update failed (consecutive failure {}) — retrying in {}",
            contactUpdateRetryPolicy.failureCount,
            CONTACT_UPDATE_RETRY_DELAY
        )
        contactUpdateRetryJob = syncScope.launch {
            delay(CONTACT_UPDATE_RETRY_DELAY)
            // A regular run (ticker / resume / contacts screen) may have
            // succeeded while this retry was pending — skip the redundant pass.
            if (!contactUpdateRetryPolicy.retryStillWarranted) {
                log.info("skipping contact-update retry: a later run already succeeded")
                return@launch
            }
            log.info("retrying contact update after earlier failure")
            updateContactRequests(initialSync)
        }
    }

    override fun requestContactUpdate() {
        syncScope.launch {
            try {
                updateContactRequests(initialSync = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fire-and-forget by contract; the periodic sync will retry.
                log.warn("requested contact update failed", e)
            }
        }
    }

    override fun updateSyncStatus(stage: PreBlockStage) {
        log.info("updateSyncStatus: ${stage.name}")
        if (stage == PreBlockStage.Starting && lastPreBlockStage != PreBlockStage.None) {
            log.debug("skipping ${stage.name} because an identity was restored")
            return
        }
        if (preDownloadBlocks.get()) {
            firePreBlockProgressListeners(stage)
            lastPreBlockStage = stage
        } else {
            log.debug("skipping ${stage.name} because PREBLOCKS is OFF")
        }
    }

    private fun checkAndAddSentRequest(
        userId: String,
        contactRequest: ContactRequest,
        encryptionKey: KeyParameter? = null
    ): Boolean {
        val contact = EvolutionContact(userId, contactRequest.toUserId.toString())
        try {
            if (!platformRepo.walletApplication.wallet!!.hasReceivingKeyChain(contact)) {
                Context.propagate(walletApplication.wallet!!.context)
                log.info("adding accepted/send request to wallet: ${contactRequest.toUserId}")
                // getContactIdentity tolerates the legacy identity cache being
                // unable to CBOR-serialize a v4.1 identity (e.g. an iOS contact);
                // identities.get would otherwise throw "No converter for ..."
                // and drop this reconciled contact.
                val contactIdentity = platform.getContactIdentity(contactRequest.toUserId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && platformRepo.walletApplication.wallet!!.isEncrypted) {
                    val password = try {
                        // always create a SecurityGuard when it is required
                        val securityGuard = SecurityGuard.getInstance()
                        securityGuard.retrievePassword()
                    } catch (e: SecurityGuardException) {
                        log.error("There was an error retrieving the wallet password", e)
                        analytics.logError(e, "There was an error retrieving the wallet password")
                        platformRepo.fireSeriousErrorListeners(SeriousError.MissingEncryptionIV)
                        null
                    } ?: return false
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey =
                        platformRepo.walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                identityRepository.blockchainIdentity!!.addPaymentKeyChainFromContact(
                    contactIdentity!!,
                    contactRequest,
                    myEncryptionKey!!
                )
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("check and add sent requests: error", e)
        }
        return false
    }

    private fun checkAndAddReceivedRequest(
        userId: String,
        contactRequest: ContactRequest,
        encryptionKey: KeyParameter? = null
    ): Boolean {
        // add the sending to contact keychain if it doesn't exist
        val contact = EvolutionContact(
            userId,
            0,
            contactRequest.ownerId.toString(),
            contactRequest.accountReference
        )
        try {
            Context.propagate(platformRepo.walletApplication.wallet!!.context)
            if (!platformRepo.walletApplication.wallet!!.hasSendingKeyChain(contact)) {
                log.info("adding received request: ${contactRequest.ownerId} to wallet")
                // getContactIdentity tolerates the legacy identity cache being
                // unable to CBOR-serialize a v4.1 identity (e.g. an iOS contact);
                // identities.get would otherwise throw "No converter for ..." and
                // this received request would never be added to the wallet.
                val contactIdentity = platform.getContactIdentity(contactRequest.ownerId)
                var myEncryptionKey = encryptionKey
                if (encryptionKey == null && platformRepo.walletApplication.wallet!!.isEncrypted) {
                    val password = try {
                        // always create a SecurityGuard when it is required
                        val securityGuard = SecurityGuard.getInstance()
                        securityGuard.retrievePassword()
                    } catch (e: SecurityGuardException) {
                        log.error("There was an error retrieving the wallet password", e)
                        analytics.logError(e, "There was an error retrieving the wallet password")
                        platformRepo.fireSeriousErrorListeners(SeriousError.MissingEncryptionIV)
                        null
                    } ?: return false
                    // Don't bother with DeriveKeyTask here, just call deriveKey
                    myEncryptionKey =
                        platformRepo.walletApplication.wallet!!.keyCrypter!!.deriveKey(password)
                }
                identityRepository.blockchainIdentity!!.addPaymentKeyChainToContact(
                    contactIdentity!!,
                    contactRequest,
                    myEncryptionKey!!
                )
                return true
            }
        } catch (e: KeyCrypterException) {
            // we can't send payments to this contact due to an invalid encryptedPublicKey
            log.info("ContactRequest: error ${e.message}", e)
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("check and add received requests: error", e)
        }
        return false
    }

    /**
     * Fetches updated profiles associated with contacts of userId after lastContactRequestTime
     */
    private suspend fun updateContactProfiles(userId: String, lastContactRequestTime: Long) {
        val watch = Stopwatch.createStarted()
        val userIdSet = hashSetOf<String>()

        val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
        toContactDocuments.forEach {
            userIdSet.add(it.toUserId)
        }
        val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
        fromContactDocuments.forEach {
            userIdSet.add(it.userId)
        }

        invitationsDao.loadAll().forEach {
            userIdSet.add(it.userId)
        }

        // Also add our ownerId to get our profile, in case it was updated on a different device
        userIdSet.add(userId)

        updateContactProfiles(userIdSet.toList(), lastContactRequestTime)
        log.info("updating contacts and profiles took $watch")
    }

    /**
     * Fetches updated profiles of users in userIdList after lastContactRequestTime
     *
     * if lastContactRequestTime is 0, then all profiles are retrieved
     *
     * This does not handle the case if userIdList.size > 100
     */
    private suspend fun updateContactProfiles(
        userIdList: List<String>,
        lastContactRequestTime: Long,
        checkingIntegrity: Boolean = false
    ) {
        try {
            if (userIdList.isNotEmpty()) {
                val identifierList = userIdList.map { Identifier.from(it) }
                // Phase 3d (docs/kotlin-sdk-migration-plan.md): profile
                // retrieval via the Kotlin SDK behind USE_KOTLIN_SDK_DPNS_READS
                // (default off). Null means "flag off or SDK path failed" —
                // fall through to the unchanged dashj-platform query. dashj's
                // getList ignores lastContactRequestTime (getListHelper builds
                // only whereIn($ownerId) + orderBy), so parity needs no
                // $updatedAt clause on the SDK path.
                val profileDocuments = sdkProfileQueries.getProfileDocumentsOrNull(identifierList)
                    ?: platform.profiles.getList(
                        identifierList,
                        lastContactRequestTime
                    )
                val profileById = profileDocuments.associateBy({ it.ownerId }, { it })

                // Phase 3e: domain documents for the contact ids via the
                // Kotlin SDK behind the same read flag; null = flag off or
                // SDK path failed — fall through to the dashj query.
                val nameDocuments = (
                    sdkUsernameQueries.getDomainDocumentsForIdentitiesOrNull(identifierList)
                        ?: platform.names.getList(identifierList)
                    ).map { DomainDocument(it) }
                val documentsByName = nameDocuments.associateBy({ it.normalizedLabel }, { it })
                val idByNameMap = nameDocuments.associateBy({ it.normalizedLabel }, { it.ownerId })

                val nameById = hashMapOf<Identifier, DomainDocument>()

                idByNameMap.forEach { (name, identifier) ->
                    val otherNames = idByNameMap.filter { it.value == identifier && name != it.key }

                    val shouldAddName = when {
                        otherNames.isEmpty() -> true
                        Names.isUsernameContestable(name) -> true
                        else -> {
                            !otherNames.keys.any { name.contains(it) }
                        }
                    }
                    if (shouldAddName) {
                        documentsByName[name]?.let {
                            nameById[it.ownerId] = it
                        }
                    }
                }

                for (id in profileById.keys) {
                    if (nameById.containsKey(id)) {
                        updateDashPayProfile(nameById, id, profileById, checkingIntegrity)
                    } else {
                        log.info("domain document for $id could not be found, though a profile exists")
                    }
                }
                // handle any domain documents without a profile
                val remainingNames = nameById.filter { !profileById.keys.contains(it.key) }
                for (id in remainingNames) {
                    updateDashPayProfile(nameById, id.key, mapOf(), checkingIntegrity)
                }

                // add a blank profile for any identity that is still missing a profile
                if (lastContactRequestTime == 0L) {
                    val remainingMissingProfiles = userIdList.filter {
                        !profileById.containsKey(
                            Identifier.from(it)
                        )
                    }
                    for (identityId in remainingMissingProfiles) {
                        val nameDocument = nameById[Identifier.from(identityId)]
                        // what happens if there is no username for the identity? crash
                        if (nameDocument != null) {
                            val username = nameDocument.label
                            val identityIdForName = platformRepo.getIdentityForName(nameDocument)
                            dashPayProfileDao.insert(
                                DashPayProfile(
                                    identityIdForName.toString(),
                                    username
                                )
                            )
                        } else {
                            log.info("no username found for $identityId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("update contact profiles", e)
        }
    }

    private suspend fun updateDashPayProfile(
        nameById: HashMap<Identifier, DomainDocument>,
        id: Identifier,
        profileById: Map<Identifier, Document>,
        checkingIntegrity: Boolean
    ) {
        val nameDocument = nameById[id]!! // what happens if there is no username for the identity? crash
        val username = nameDocument.label
        val identityId = platformRepo.getIdentityForName(nameDocument)

        val profileDocument = profileById[id]

        val profile = if (profileDocument != null) {
            DashPayProfile.fromDocument(profileDocument, username)
        } else {
            DashPayProfile(identityId.toString(), username)
        }

        dashPayProfileDao.insert(profile)
        if (checkingIntegrity) {
            log.info("check database integrity: adding missing profile $username:$id")
        }
    }

    // This will check for missing profiles, download them and update the database
    private suspend fun checkDatabaseIntegrity(userId: String) {
        val watch = Stopwatch.createStarted()
        log.info("check database integrity: starting")

        // The reconciliation below REBUILDS ContactRequest documents from the
        // database rows (toContactRequest -> Documents.create), which needs the
        // dashpay data contract to be LOADED — hasApp("dashpay") upstream only
        // proves the app registration exists. Running before the contract
        // fetch completed NPEd out of Contracts.get and aborted the integrity
        // pass every cycle. Skip this round; the next sync cycle retries.
        if (!isDashPayContractLoaded(platform.platform)) {
            log.warn("check database integrity: skipped — the dashpay data contract is not loaded yet")
            return
        }

        try {
            val userIdList = HashSet<String>()
            val missingProfiles = HashSet<String>()

            val toContactDocuments = dashPayContactRequestDao.loadToOthers(userId)
            val toContactMap = HashMap<String, DashPayContactRequest>()
            var addedContactRequests = false
            toContactDocuments.forEach {
                userIdList.add(it.toUserId)
                toContactMap[it.toUserId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddSentRequest(userId, it.toContactRequest(platform.platform))
                if (added) {
                    log.warn(
                        "check database integrity: added sent $it to wallet since it was missing.  " +
                            "Transactions may also be missing"
                    )
                    addedContactRequests = true
                }
            }
            // Get all contact requests where toUserId == userId, the users who have added me
            val fromContactDocuments = dashPayContactRequestDao.loadFromOthers(userId)
            val fromContactMap = HashMap<String, DashPayContactRequest>()
            fromContactDocuments.forEach {
                userIdList.add(it.userId)
                fromContactMap[it.userId] = it

                // check to see if wallet has this contact request's keys
                val added = checkAndAddReceivedRequest(userId, it.toContactRequest(platform.platform))
                if (added) {
                    log.warn("check database integrity: added received $it to wallet since it was missing")
                    addedContactRequests = true
                }
            }

            // If new keychains were added to the wallet, then update the bloom filters
            if (addedContactRequests) {
                postUpdateBloomFilters()
            }

            for (user in userIdList) {
                val profile = dashPayProfileDao.loadByUserId(user)
                if (profile == null) {
                    missingProfiles.add(user)
                }
            }

            if (missingProfiles.isNotEmpty()) {
                updateContactProfiles(missingProfiles.toList(), 0, true)
            }
        } catch (e: Exception) {
            platformRepo.formatExceptionMessage("check database integrity", e)
        } finally {
            log.info("check database integrity complete in $watch")
        }
    }

    override fun postUpdateBloomFilters() {
        MainScope().launch {
            updateBloomFilters()
        }
    }

    private suspend fun updateTransactionMetadata(myEncryptionKey: KeyParameter?) {
        val watch = Stopwatch.createStarted()

        val lastTxMetadataRequestTime = if (transactionMetadataDocumentDao.countAllRequests() > 0) {
            val lastTimeStamp = transactionMetadataDocumentDao.getLastTimestamp()
            // if the last txmetadata document was received in the past 10 minutes, then query for
            // documents that are 10 minutes before it.  If the tx metadata documented was
            // more than 10 minutes ago, then query all metadata documents that came after it.
            if (lastTimeStamp < System.currentTimeMillis() - DateUtils.MINUTE_IN_MILLIS * 10) {
                lastTimeStamp
            } else {
                lastTimeStamp - DateUtils.MINUTE_IN_MILLIS * 10
            }
        } else {
            0L
        }

        log.info("fetching TxMetadataDocuments from {}", lastTxMetadataRequestTime)

        val items = identityRepository.blockchainIdentity!!
            .getTxMetaData(lastTxMetadataRequestTime, myEncryptionKey)

        if (items.isEmpty()) {
            return
        }

        // val lastItem = items.keys.last()
        // lastItem.createdAt?.let {
        //    configuration.txMetadataUpdateTime = it
        // }
        log.info("processing TxMetadataDocuments: {}", items.toString())

        items.forEach { (doc, list) ->
            if (transactionMetadataDocumentDao.count(doc.id) == 0) {
                val timestamp = doc.updatedAt!!
                log.info("processing TxMetadata: ${doc.id} with ${list.size} items")
                list.forEach { metadata ->
                    if (metadata.isNotEmpty()) {
                        val txIdAsHash = Sha256Hash.wrapReversed(metadata.txId)
                        val cachedItems = transactionMetadataChangeCacheDao.findAfter(
                            txIdAsHash.toTxId(), // tx hash is stored in LE
                            timestamp
                        )
                        log.info(
                            "processing TxMetadata: found ${cachedItems.size} related items in this document"
                        )

                        // what if the updates from platform are older

                        // if not change the main table

                        // we need to find a new way -- how can we know that we should change something?
                        // should we save to the DB table?
                        val metadataDocumentRecord = TransactionMetadataDocument(
                            doc.id,
                            doc.updatedAt!!,
                            txIdAsHash.toTxId()
                        )
                        val updatedMetadata = TransactionMetadata(txIdAsHash.toTxId(), 0, org.dash.wallet.common.money.Coin.ZERO, TransactionCategory.Invalid)
                        var iconUrl: String? = null
                        val giftCard = GiftCard(txIdAsHash.toTxId())

                        metadata.timestamp?.let { timestamp ->
                            metadataDocumentRecord.sentTimestamp = timestamp
                            log.info("processing TxMetadata: sent time stamp")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.sentTimestamp != null && it.sentTimestamp != timestamp
                                } == null
                            ) {
                                log.info("processing TxMetadata: service change: changing timestamp")
                                updatedMetadata.timestamp = timestamp
                            }
                        }
                        metadata.service?.let { service ->
                            metadataDocumentRecord.service = service
                            log.info("processing TxMetadata: service change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.service != null && it.service != service
                                } == null
                            ) {
                                log.info("processing TxMetadata: service change: changing service")
                                updatedMetadata.service = service
                            }
                        }
                        metadata.memo?.let { memo ->
                            metadataDocumentRecord.memo = memo
                            log.info(
                                "processing TxMetadata: memo change: {}",
                                cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.memo != null && it.memo != memo
                                }
                            )
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.memo != null && it.memo != memo
                                } == null
                            ) {
                                log.info("processing TxMetadata: memo change: changing memo")
                                updatedMetadata.memo = memo
                            }
                        }
                        metadata.taxCategory?.let { taxCategoryAsString ->
                            TaxCategory.fromValue(taxCategoryAsString)?.let { taxCategory ->
                                metadataDocumentRecord.taxCategory = taxCategory
                                log.info("processing TxMetadata: tax category change")
                                if (cachedItems.find {
                                        it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                            it.taxCategory != null && it.taxCategory?.name != taxCategoryAsString
                                    } == null
                                ) {
                                    log.info("processing TxMetadata: tax category change: changing category")
                                    updatedMetadata.taxCategory = taxCategory
                                }
                            }
                        }
                        if (metadata.exchangeRate != null && metadata.currencyCode != null) {
                            metadataDocumentRecord.rate = metadata.exchangeRate
                            metadataDocumentRecord.currencyCode = metadata.currencyCode

                            val prevItem = cachedItems.find {
                                it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                    it.currencyCode != null && it.rate != null &&
                                    (
                                        it.currencyCode != metadata.currencyCode ||
                                            it.rate != metadata.exchangeRate.toString()
                                        )
                            }
                            log.info("processing TxMetadata: exchange rate change change: $prevItem")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.currencyCode != null && it.rate != null &&
                                        (
                                            it.currencyCode != metadata.currencyCode ||
                                                it.rate != metadata.exchangeRate.toString()
                                            )
                                } == null
                            ) {
                                log.info("processing TxMetadata: exchange rate change change: setting rate")
                                updatedMetadata.currencyCode = metadata.currencyCode
                                updatedMetadata.rate = metadata.exchangeRate.toString()
                            }
                        }
                        metadata.customIconUrl?.let { url ->
                            metadataDocumentRecord.customIconUrl = url
                            log.info("processing TxMetadata: custom icon url change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.customIconUrl != null && it.customIconUrl != url
                                } == null
                            ) {
                                log.info("processing TxMetadata: custom icon url change: changing icon")
                                iconUrl = url
                            }
                        }
                        metadata.giftCardNumber?.let { number ->
                            metadataDocumentRecord.giftCardNumber = number
                            log.info("processing TxMetadata: gift card number change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.giftCardNumber != null && it.giftCardNumber != number
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card number change: changing number")
                                giftCard.number = number
                            }
                        }
                        metadata.giftCardPin?.let { pin ->
                            metadataDocumentRecord.giftCardPin = pin
                            log.info("processing TxMetadata: gift card pin change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.giftCardPin != null && it.giftCardPin != pin
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card pin change: changing pin")
                                giftCard.pin = pin
                            }
                        }
                        metadata.merchantName?.let { name ->
                            metadataDocumentRecord.merchantName = name
                            log.info("processing TxMetadata: merchant name change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.merchantName != null && it.merchantName != name
                                } == null
                            ) {
                                log.info("processing TxMetadata: merchant name change: changing name")
                                giftCard.merchantName = name
                            }
                        }
                        metadata.originalPrice?.let { price ->
                            metadataDocumentRecord.originalPrice = price
                            log.info("processing TxMetadata: gift card price change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.createdAt!! &&
                                        it.originalPrice != null && it.originalPrice != price
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card price change: changing price")
                                giftCard.price = price
                            }
                        }
                        metadata.barcodeValue?.let { barcodeValue ->
                            metadataDocumentRecord.barcodeValue = barcodeValue
                            log.info("processing TxMetadata: barcode value change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.barcodeValue != null && it.barcodeValue != barcodeValue
                                } == null
                            ) {
                                log.info("processing TxMetadata: barcode value change: changing value")
                                giftCard.barcodeValue = barcodeValue
                            }
                        }
                        metadata.barcodeFormat?.let { barcodeFormat ->
                            metadataDocumentRecord.barcodeFormat = barcodeFormat
                            log.info("processing TxMetadata: barcode format change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.barcodeFormat != null && it.barcodeFormat != barcodeFormat
                                } == null
                            ) {
                                log.info("processing TxMetadata: barcode value change: changing value")
                                try {
                                    giftCard.barcodeFormat = BarcodeFormat.valueOf(barcodeFormat)
                                } catch (e: IllegalArgumentException) {
                                    log.warn("Invalid barcode format: {}", barcodeFormat, e)
                                }
                            }
                        }
                        metadata.merchantUrl?.let { url ->
                            metadataDocumentRecord.merchantUrl = url
                            log.info("processing TxMetadata: merchant url change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.merchantUrl != null && it.merchantUrl != url
                                } == null
                            ) {
                                log.info("processing TxMetadata: merchant url change: changing url")
                                giftCard.merchantUrl = url
                            }
                        }
                        metadata.order?.let { order ->
                            metadataDocumentRecord.order = order
                            log.info("processing TxMetadata: order change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.order != null && it.order != order
                                } == null
                            ) {
                                log.info("processing TxMetadata: order change: changing order")
                                giftCard.note = order
                            }
                        }
                        metadata.giftCardChallenge?.let { challenge ->
                            metadataDocumentRecord.giftCardChallenge = challenge
                            log.info("processing TxMetadata: gift card challenge change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.giftCardChallenge != null && it.giftCardChallenge != challenge
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card challenge change: changing challenge")
                                giftCard.redeemUrlChallenge = challenge
                            }
                        }
                        metadata.index?.let { index ->
                            metadataDocumentRecord.index = index
                            log.info("processing TxMetadata: gift card index change")
                            if (cachedItems.find {
                                    it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                                        it.index != null && it.index != index
                                } == null
                            ) {
                                log.info("processing TxMetadata: gift card index change: changing index")
                                giftCard.index = index
                            }
                        }

                        log.info("syncing metadata with platform updates: $updatedMetadata")
                        transactionMetadataProvider.syncPlatformMetadata(txIdAsHash.toTxId(), updatedMetadata, giftCard, iconUrl)
                        log.info("adding TxMetadataItem: {}", metadata)
                        transactionMetadataDocumentDao.insert(metadataDocumentRecord)
                    } else {
                        log.info("not adding TxMetadataItem: {} since it is empty", metadata)
                    }
                }

                // configuration.txMetadataUpdateTime = doc.createdAt!!
            } else {
                log.info("TxMetadataDocument:  this item already exists ${doc.id}")
            }
        }

        log.info("fetching ${items.size} tx metadata items in $watch")
    }

    private suspend fun publishTransactionMetadata(
        txMetadataItems: List<TransactionMetadataCacheItem>,
        myEncryptionKey: KeyParameter?,
        progressListener: (suspend (Int) -> Unit)? = null
    ): Int {
        if (!identityRepository.hasBlockchainIdentity) {
            return 0
        }
        progressListener?.invoke(0)
        log.info(PUBLISH, txMetadataItems.joinToString("\n") { it.toString() })
        val metadataList = txMetadataItems.map {
            TxMetadataItem(
                it.txId.bytes.reversedArray(), // tx hash is stored in LE
                it.sentTimestamp,
                it.memo,
                it.rate?.toDouble(),
                it.currencyCode,
                it.taxCategory?.name?.lowercase(),
                it.service,
                it.customIconUrl,
                it.giftCardNumber,
                it.giftCardPin,
                it.merchantName,
                it.originalPrice,
                it.barcodeValue,
                it.barcodeFormat,
                it.merchantUrl,
                null,
                it.order,
                it.giftCardChallenge,
                it.index
            )
        }
        progressListener?.invoke(10)
        //val walletEncryptionKey = platformRepo.getWalletEncryptionKey()
        val keyIndex = 1 + transactionMetadataDocumentDao.countAllRequests()
        identityRepository.blockchainIdentity!!.publishTxMetaData(
            metadataList,
            myEncryptionKey,
            keyIndex,
            TxMetadataDocument.VERSION_PROTOBUF
        ) { progress ->
            syncScope.launch(Dispatchers.IO) {
                progressListener?.invoke(10 + progress * 90 / 100)
            }
        }
        return txMetadataItems.size
    }

    private fun mergeTransactionMetadataDocuments(txId: Sha256Hash, docs: List<TransactionMetadataDocument>): TransactionMetadataCacheItem {
        return TransactionMetadataCacheItem(
            cacheTimestamp = docs.lastOrNull()?.timestamp ?: 0,
            txId = txId.toTxId(),
            sentTimestamp = docs.lastOrNull { it.sentTimestamp != null }?.sentTimestamp,
            taxCategory = docs.lastOrNull { it.taxCategory != null }?.taxCategory,
            currencyCode = docs.lastOrNull { it.currencyCode != null }?.currencyCode,
            rate = docs.lastOrNull { it.rate != null }?.rate.toString(),
            memo = docs.lastOrNull { it.memo != null }?.memo,
            service = docs.lastOrNull { it.service != null }?.service,
            customIconUrl = docs.lastOrNull { it.customIconUrl != null }?.customIconUrl,
            giftCardNumber = docs.lastOrNull { it.giftCardNumber != null }?.giftCardNumber,
            giftCardPin = docs.lastOrNull { it.giftCardPin != null }?.giftCardPin,
            merchantName = docs.lastOrNull { it.merchantName != null }?.merchantName,
            originalPrice = docs.lastOrNull { it.originalPrice != null }?.originalPrice,
            barcodeValue = docs.lastOrNull { it.barcodeValue != null }?.barcodeValue,
            barcodeFormat = docs.lastOrNull { it.barcodeFormat != null }?.barcodeFormat,
            merchantUrl = docs.lastOrNull { it.merchantUrl != null }?.merchantUrl,
            order = docs.lastOrNull { it.order != null }?.order,
            giftCardChallenge = docs.lastOrNull { it.giftCardChallenge != null}?.giftCardChallenge,
            index = docs.lastOrNull { it.index != null }?.index
        )
    }

    override suspend fun hasPendingTxMetadataToSave(): Boolean {
        return transactionMetadataChangeCacheDao.count() > 0 || getUnsavedTransactions().first.isNotEmpty()
    }

    // this is a slow operation?
    override suspend fun getUnsavedTransactions(): Pair<List<Transaction>, Long> {
        val watch = Stopwatch.createStarted()
        val start = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE) ?: 0L
        val end = System.currentTimeMillis()

        val notCoinJoinFilter = object : WalletTransactionFilter {
            override fun matches(tx: Transaction): Boolean {
                val type = CoinJoinTransactionType.fromTx(tx, walletDataProvider.wallet)
                return type == CoinJoinTransactionType.None || type == CoinJoinTransactionType.Send
            }
        }
        val listOfUnsaved = arrayListOf<Transaction>()
        var firstUnsavedTxDate = 0L
        walletDataProvider.getTransactions(notCoinJoinFilter).forEach { tx ->
            if (tx.updateTime.time in start .. end) {
                if (!transactionMetadataProvider.exists(tx.txId.toTxId())) {
                    listOfUnsaved.add(tx)
                    firstUnsavedTxDate = if (firstUnsavedTxDate != 0L) {
                        min(firstUnsavedTxDate, tx.updateTime.time)
                    } else {
                        tx.updateTime.time
                    }
                } else {
                    val previouslySavedItems = transactionMetadataDocumentDao.getTransactionMetadata(tx.txId.toTxId())
                    val previouslySaved = mergeTransactionMetadataDocuments(tx.txId, previouslySavedItems)
                    val currentItem = transactionMetadataProvider.getTransactionMetadata(tx.txId.toTxId())!!
                    val giftCard = giftCardDao.getCardForTransaction(tx.txId.bytes)

                    if (!previouslySaved.compare(currentItem, giftCard.firstOrNull())) {
                        listOfUnsaved.add(tx)
                        firstUnsavedTxDate = if (firstUnsavedTxDate != 0L) {
                            min(firstUnsavedTxDate, tx.updateTime.time)
                        } else {
                            tx.updateTime.time
                        }
                    }
                }
            }
        }
        log.info("determining unsaved transactions: {}, {} txes", watch, listOfUnsaved.size)
        return Pair(listOfUnsaved, firstUnsavedTxDate)
    }

    suspend fun publishPastTxMetadata(progressListener: suspend (Int) -> Unit): TxMetadataSaveInfo {
        // determine any changes that haven't been saved before [DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE]
        val alreadySaved = dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_LAST_PAST_SAVE) ?: 0L
        // add to those changes to the change cache
        val txes = walletApplication.wallet?.getTransactions(true)
        var itemsToSave = 0
        txes?.forEachIndexed { i, tx ->
            if (tx.updateTime.time >= alreadySaved) {
                transactionMetadataProvider.getTransactionMetadata(tx.txId.toTxId())?.let { metadata ->
                    val giftCard = giftCardDao.getCardForTransaction(tx.txId.bytes)

                    // make sure it is not already saved?

                    val previouslySaved = transactionMetadataDocumentDao.getTransactionMetadata(tx.txId.toTxId())
                    log.info("publish: previously saved: {}", previouslySaved)

                    val saved = mergeTransactionMetadataDocuments(tx.txId, previouslySaved)
                    log.info("publish: merged saved: {}", saved)

                    val metadataItem = TransactionMetadataCacheItem(
                        metadata,
                        giftCard.firstOrNull()
                    )
                    log.info("publish: item: {}", metadataItem)
                    val diff = metadataItem - saved
                    log.info("publish: diff: {}", diff)
                    if (diff.isNotEmpty() && !transactionMetadataChangeCacheDao.has(diff)) {
                        transactionMetadataChangeCacheDao.insert(diff)
                    }
                    itemsToSave++
                }
                progressListener.invoke(i * 100 / txes.size / 2)
            }
        }
        // call publishChangeCache
        val itemsSaved = publishChangeCache(System.currentTimeMillis(), saveAll = true) { progress ->
            progressListener.invoke(50 + progress / 2)
        }
        return itemsSaved
    }

    private suspend fun publishChangeCache(before: Long, saveAll: Boolean, progressListener: (suspend (Int) -> Unit)? = null): TxMetadataSaveInfo {
        if (!Constants.SUPPORTS_TXMETADATA) {
            return TxMetadataSaveInfo.NONE
        }
        log.info("publishing updates to tx metadata items before $before")
        val itemsToPublish = hashMapOf<org.dash.wallet.common.data.TxId, TransactionMetadataCacheItem>()
        val changedItems = transactionMetadataChangeCacheDao.getCachedItemsBefore(before)

        if (changedItems.isEmpty()) {
            log.info("no tx metadata changes before this time")
            return TxMetadataSaveInfo.NONE
        }
        val saveSettings = dashPayConfig.getTransactionMetadataSettings()

        log.info("preparing to [publish] ${changedItems.size} tx metadata changes to platform")

        for (changedItem in changedItems) {
            if (itemsToPublish.containsKey(changedItem.txId)) {
                val item = itemsToPublish[changedItem.txId]!!

                if (saveSettings.shouldSavePrivateMemos(saveAll)) {
                    changedItem.memo?.let { memo ->
                        item.memo = memo
                    }
                }
                if (saveSettings.shouldSaveExchangeRates(saveAll)) {
                    if (changedItem.rate != null && changedItem.currencyCode != null) {
                        item.rate = changedItem.rate
                        item.currencyCode = changedItem.currencyCode
                    }
                    changedItem.sentTimestamp?.let { timestamp ->
                        item.sentTimestamp = timestamp
                    }
                }
                if (saveSettings.shouldSaveTaxCategory(saveAll)) {
                    changedItem.taxCategory?.let { taxCategory ->
                        item.taxCategory = taxCategory
                    }
                }
                if (saveSettings.shouldSavePaymentCategory(saveAll)) {
                    changedItem.service?.let { service ->
                        item.service = service
                    }
                }
                if (saveSettings.shouldSaveGiftcardInfo(saveAll)) {
                    changedItem.customIconUrl?.let { customIconUrl ->
                        item.customIconUrl = customIconUrl
                    }
                    changedItem.giftCardNumber?.let { giftCardNumber ->
                        item.giftCardNumber = giftCardNumber
                    }
                    changedItem.giftCardPin?.let { giftCardPin ->
                        item.giftCardPin = giftCardPin
                    }
                    changedItem.merchantName?.let { merchantName ->
                        item.merchantName = merchantName
                    }
                    changedItem.originalPrice?.let { originalPrice ->
                        item.originalPrice = originalPrice
                    }
                    changedItem.barcodeValue?.let { barcodeValue ->
                        item.barcodeValue = barcodeValue
                    }
                    changedItem.barcodeFormat?.let { barcodeFormat ->
                        item.barcodeFormat = barcodeFormat
                    }
                    changedItem.merchantUrl?.let { merchantUrl ->
                        item.merchantUrl = merchantUrl
                    }
                    changedItem.order?.let { order ->
                        item.order = order
                    }
                    changedItem.giftCardChallenge?.let { giftCardChallenge ->
                        item.giftCardChallenge = giftCardChallenge
                    }
                }
            } else {
                itemsToPublish[changedItem.txId] = changedItem
            }
        }
        progressListener?.invoke(10)
        var itemsSaved = 0
        val itemsToSave = changedItems.size
        try {
            log.info("publishing ${itemsToPublish.values.size} tx metadata items to platform")

            // publish non-empty items
            val myEncryptionKey = platformRepo.getWalletEncryptionKey()
            publishTransactionMetadata(itemsToPublish.values.filter { it.isNotEmpty() }, myEncryptionKey) {
                progressListener?.invoke(10 + it * 90 / 100)
            }
            log.info("published ${itemsToPublish.values.size} tx metadata items to platform")

            // clear out published items from the cache table
            log.info("published and remove ${changedItems.map { it.id }} tx metadata items from cache")
            transactionMetadataChangeCacheDao.removeByIds(changedItems.map { it.id })
            config.set(DashPayConfig.LAST_METADATA_PUSH, System.currentTimeMillis())
            itemsSaved = changedItems.size

            updateTransactionMetadata(myEncryptionKey)
        } catch (_: CancellationException) {
            log.info("publishing updates canceled")
        } catch (e: Exception) {
            log.error("publishing exception caught", e)
        }

        log.info("publishing updates to tx metadata items complete")
        return TxMetadataSaveInfo(itemsSaved, itemsToSave)
    }

    // uses get_vote_polls to get active vote polls, but must check remaining
    // items in the username_requests table and remove them
    override suspend fun updateUsernameRequestsWithVotes() {
        checkUsernameVotingStatus()
        log.info("updateUsernameRequestsWithVotes starting")
        try {
            log.info("updateUsernameRequestsWithVotes: getCurrentVotePolls start")
            val votePolls = platform.platform.names.getCurrentVotePolls()
            log.info("updateUsernameRequestsWithVotes: getCurrentVotePolls end")
            // usernameRequestDao.clear()
            // val myIdentifier = platformRepo.blockchainIdentity.uniqueIdentifier
            val currentRequestList = usernameRequestDao.getAll().toMutableList()
            val currentUsernames = arrayListOf<String>()
            for (votePoll in votePolls) {
                try {
                    val name :String? = when (votePoll) {
                        is ContestedDocumentResourceVotePoll -> {
                            votePoll.indexValues[1]
                        }
                        else -> null
                    }

                    name?.let { normalizedLabel ->
                        val voteContender = platformRepo.getVoteContenders(normalizedLabel)
                        val votes = usernameVoteDao.getVotes(name)

                        voteContender.map.forEach { (identifier, contender) ->

                            if (voteContender.winner.isEmpty) {
                                val contestedDocument = contender.serializedDocument?.let { serialized ->
                                    DomainDocument(
                                        platform.platform.names.deserialize(serialized)
                                    )
                                }

                                if (contestedDocument != null) {
                                    // dashpay/platform#4088 (light way): the contender's
                                    // verification link via the Kotlin SDK's generic
                                    // document search behind USE_KOTLIN_SDK_DPNS_READS
                                    // (default off); null = flag off or SDK path failed —
                                    // fall through to the unchanged dashj query.
                                    // Optional.empty() = the SDK definitively reported no
                                    // link, so dashj is NOT queried again.
                                    val sdkUrl = sdkIdentityVerifyQueries.getVerificationUrl(identifier, name)
                                    val verificationUrl = if (sdkUrl != null) {
                                        sdkUrl.orElse(null)
                                    } else {
                                        IdentityVerify(platform.platform).get(identifier, name)?.url
                                    }

                                    val requestId = UsernameRequest.getRequestId(identifier.toString(), normalizedLabel)
                                    val lastVote = votes.lastOrNull()
                                    val usernameRequest = UsernameRequest(
                                        requestId = requestId,
                                        username = contestedDocument.label,
                                        normalizedLabel = name,
                                        createdAt = contestedDocument.createdAt ?: -1L,
                                        identity = identifier.toString(),
                                        link = verificationUrl,
                                        votes = contender.votes,
                                        lockVotes = voteContender.lockVoteTally,
                                        isApproved = lastVote?.let { it.identity == identifier.toString() } ?: false
                                    )
                                    usernameRequestDao.insert(usernameRequest)
                                    currentRequestList.remove(usernameRequest)
                                    currentUsernames.add(usernameRequest.normalizedLabel)
                                } else {
                                    // voting is complete
                                    usernameRequestDao.remove(
                                        UsernameRequest.getRequestId(identifier.toString(), name)
                                    )
                                    // remove related votes
                                    usernameVoteDao.remove(name)
                                }
                            } else {
                                // there is a winner
                                usernameRequestDao.remove(
                                    UsernameRequest.getRequestId(identifier.toString(), name)
                                )
                                // remove related votes
                                usernameVoteDao.remove(name)
                            }
                        }
                    }
                } catch (e: Exception) {
                    log.warn("problem getting vote polls", e)
                }
            }

            // check the remaining items to ensure voting has ended
            currentRequestList.forEach { request ->
                val voteContender = platformRepo.getVoteContenders(request.normalizedLabel)
                if (voteContender.winner.isPresent) {
                    // remove request
                    usernameRequestDao.remove(request.requestId)
                    // remove related votes
                    usernameVoteDao.remove(request.normalizedLabel)
                }
            }
            // check votes and remove those from previous vote polls
            usernameVoteDao.getAllVotes().forEach { vote ->
                if (!currentUsernames.contains(vote.username)) {
                    usernameVoteDao.remove(vote.username)
                }
            }
        } catch (e: Exception) {
            log.info("problem obtaining votes:", e)
        } finally {
            log.info("updateUsernameRequestsWithVotes complete")
        }
    }

    /**
     * update databases for a single username (normalized)
     */
    override suspend fun updateUsernameRequestWithVotes(name: String) {
        try {
            val voteContender = platformRepo.getVoteContenders(name)

            voteContender.map.forEach { (identifier, contender) ->
                val contestedDocument = contender.serializedDocument?.let { serialized ->
                    DomainDocument(
                        platform.platform.names.deserialize(serialized)
                    )
                }
                val hasWinner = voteContender.winner.isPresent

                if (!hasWinner) {
                    if (contestedDocument != null) {
                        // dashpay/platform#4088 (light way): same routing as
                        // updateUsernameRequestsWithVotes above — SDK first
                        // behind USE_KOTLIN_SDK_DPNS_READS, dashj fallback
                        // only when the SDK path was not used.
                        val sdkUrl = sdkIdentityVerifyQueries.getVerificationUrl(identifier, name)
                        val verificationUrl = if (sdkUrl != null) {
                            sdkUrl.orElse(null)
                        } else {
                            IdentityVerify(platform.platform).get(identifier, name)?.url
                        }

                        val requestId = UsernameRequest.getRequestId(identifier.toString(), name)
                        val votes = usernameVoteDao.getVotes(name)
                        val lastVote = votes.lastOrNull()

                        val usernameRequest = UsernameRequest(
                            requestId = requestId,
                            username = contestedDocument.label,
                            normalizedLabel = name,
                            createdAt = contestedDocument.createdAt ?: -1L,
                            identity = identifier.toString(),
                            link = verificationUrl,
                            votes = contender.votes,
                            lockVotes = voteContender.lockVoteTally,
                            isApproved = lastVote?.let { it.identity == identifier.toString() } ?: false
                        )
                        usernameRequestDao.insert(usernameRequest)
                    }
                } else {
                    // voting is complete
                    usernameRequestDao.remove(
                        UsernameRequest.getRequestId(identifier.toString(), name)
                    )
                    // remove related votes
                    usernameVoteDao.remove(name)
                }
            }
        } catch (e: Exception) {
            log.info("problem obtaining votes for {}:", name, e)
        }
    }

    override suspend fun triggerPreBlockDownloadComplete() {
        finishPreBlockDownload()
    }

    private suspend fun finishPreBlockDownload() {
        log.info("PreBlockDownload: complete")
        if (config.areNotificationsDisabled()) {
            // this will enable notifications, since platform information has been synced
            config.set(DashPayConfig.LAST_SEEN_NOTIFICATION_TIME, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        }
        log.info("PreBlockDownload: $preDownloadBlocksFuture")
        preDownloadBlocksFuture?.set(true)
        preDownloadBlocks.set(false)
    }

    private fun isRunningInForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun updateBloomFilters() {
        // if we are not running in the foreground, don't try to start to update the bloom filters
        // This should be OK, since the blockchain shouldn't be syncing.

        // Nevertheless, platformSyncJob should be inactive when the BlockchainService is destroyed
        // Perhaps the updateContactRequests method is being run while the job is canceled
        if (platformSyncJob?.isActive == true) {
            if (isRunningInForeground()) {
                log.info("attempting to update bloom filters when the app is in the foreground")
                val intent = Intent(
                    BlockchainService.ACTION_RESET_BLOOMFILTERS,
                    null,
                    walletApplication,
                    BlockchainServiceImpl::class.java
                )
                walletApplication.startService(intent)
            } else {
                log.info("attempting to update bloom filters when the app is in the background")
            }
        }
    }

    /**
     * Called before DashJ starts synchronizing the blockchain,
     * Platform DAPI calls should be delayed until this function
     * is called because an updated Masternode and Quorun List is
     * required for proof verification
     */
    override fun preBlockDownload(future: SettableFuture<Boolean>) {
        syncScope.launch(Dispatchers.IO) {
            preDownloadBlocks.set(true)
            lastPreBlockStage = PreBlockStage.None
            preDownloadBlocksFuture = future
            log.info("preBlockDownload: starting")
            if (!Constants.SUPPORTS_PLATFORM) {
                finishPreBlockDownload()
                return@launch
            }

            // TODO: ideally we shoud do this, but there is not a good way
            // to determine if an EvoNode has Evolution
            // platform.setMasternodeListManager(walletApplication.wallet!!.context.masternodeListManager)


            // first check to see if there is a blockchain identity
            // or if the previous restore is incomplete
            val identityData = blockchainIdentityDataDao.load()

            // Identity discovery/recovery (+ contact refresh) is shared with the
            // post-cutover synced hook (FIX 1). When it enqueues a recovery, the
            // RestoreIdentityWorker drives the rest — do NOT resume/finish here,
            // matching the original early-return.
            val recoveryEnqueued = discoverAndRecoverIdentity()
            if (recoveryEnqueued) {
                return@launch
            }

            if (identityData == null || identityData.restoring) {
                // resume Sync process, since there is no Platform data to sync
                finishPreBlockDownload()
            }
            initSync(true)
        }
    }

    /**
     * See [PlatformSyncService.discoverAndRecoverIdentity].
     *
     * This is the body that historically lived inline in [preBlockDownload]. It
     * is now callable from the post-cutover synced hook in
     * [de.schildbach.wallet.service.BlockchainServiceImpl], because once the
     * cutover is committed the dashj peerGroup never starts and its
     * PreBlocksDownloadListener never fires — so on a wallet RESTORE the
     * identity/username/contacts would otherwise never be recovered.
     *
     * Idempotence: the (network) discovery + [RestoreIdentityOperation] enqueue
     * is guarded by [identityDiscoveryInFlight], latched only once an identity is
     * found; if none is found the guard resets so a later caller can retry. The
     * else branch (contact refresh) is a no-op when a refresh is already running.
     */
    override suspend fun discoverAndRecoverIdentity(): Boolean {
        if (!Constants.SUPPORTS_PLATFORM) {
            return false
        }

        val identityData = blockchainIdentityDataDao.load()
        if (identityData == null || identityData.restoring) {
            // Only one discovery in flight; keep it latched only if we actually
            // find an identity (see [identityDiscoveryInFlight]).
            if (!identityDiscoveryInFlight.compareAndSet(false, true)) {
                log.info("discoverAndRecoverIdentity: discovery already in flight, skipping")
                return false
            }
            try {
                log.info("discoverAndRecoverIdentity: checking for existing associated identity")

                val identity = identityRepository.getIdentityFromPublicKeyId()

                return if (identity != null) {
                    log.info("discoverAndRecoverIdentity: initiate recovery of existing identity ${identity.id}")
                    RestoreIdentityOperation(walletApplication)
                        .create(identity.id.toString())
                        .enqueue()
                    // leave the guard latched — recovery is under way
                    true
                } else {
                    log.info("discoverAndRecoverIdentity: no existing identity found")
                    identityDiscoveryInFlight.set(false) // allow a later retry
                    false
                }
            } catch (e: Exception) {
                identityDiscoveryInFlight.set(false)
                throw e
            }
        }
        // update contacts, profiles and other platform data
        else {
            checkVotingStatus(identityData)
            reconcileUsernameStatus(identityData)

            if (!updatingContacts.get()) {
                updateContactRequests(initialSync = true)
            }
            return false
        }
    }

    override suspend fun checkUsernameVotingStatus() {
        identityConfig.load()?.let {
            checkVotingStatus(it)
            reconcileUsernameStatus(it)
        }
    }

    /**
     * Repair pass for the local username REGISTRATION status: when platform
     * truth shows a username registered to this identity but the local
     * status never reached CONFIRMED, adopt the platform truth (and finish
     * the stuck creation state machine).
     *
     * Why this exists (observed live: username test12345, identity
     * G2HnoKSdqpTzcfd1HcU1RYk3R7Zmrc7yPYExrS3bXiDf): a shielded-funded
     * creation registers the DPNS name on chain and hands the identity to
     * `RestoreIdentityWorker`; when that worker runs before Drive has
     * indexed the new domain document, `recoverUsernames` finds nothing and
     * the worker aborts with `restoring = false` and `creationState =
     * USERNAME_REGISTERING` — after which NOTHING ever re-checked platform:
     * [preBlockDownload]'s recovery discovery only fires while `identityData
     * == null || restoring`, and [checkVotingStatus] is gated on
     * `creationState == VOTING`. The local status then read NOT_PRESENT
     * forever while another device could find the name via search. This
     * pass runs from the same triggers as the voting checker and only ever
     * acts on POSITIVE platform evidence.
     */
    private suspend fun reconcileUsernameStatus(identityData: BlockchainIdentityData) {
        // VOTING has its own checker (checkVotingStatus); before
        // IDENTITY_REGISTERED there is no identity to own a name.
        if (identityData.creationState < IdentityCreationState.IDENTITY_REGISTERED ||
            identityData.creationState == IdentityCreationState.VOTING
        ) {
            return
        }
        val userId = identityData.userId ?: return
        val identityId = try {
            Identifier.from(userId)
        } catch (e: Exception) {
            return
        }
        var confirmedName: String? = null
        var updated = false

        val username = identityData.username
        if (username != null && identityData.usernameStatus != UsernameStatus.CONFIRMED &&
            isUsernameRegisteredToIdentity(username, identityId)
        ) {
            log.info(
                "reconcile: platform shows username '{}' registered to {} — correcting local status {} -> CONFIRMED",
                username,
                userId,
                identityData.usernameStatus
            )
            identityData.usernameStatus = UsernameStatus.CONFIRMED
            confirmedName = username
            updated = true
        }

        val secondary = identityData.usernameSecondary
        if (secondary != null && identityData.usernameSecondaryStatus != UsernameStatus.CONFIRMED &&
            isUsernameRegisteredToIdentity(secondary, identityId)
        ) {
            log.info(
                "reconcile: platform shows secondary username '{}' registered to {} — " +
                    "correcting local status {} -> CONFIRMED",
                secondary,
                userId,
                identityData.usernameSecondaryStatus
            )
            identityData.usernameSecondaryStatus = UsernameStatus.CONFIRMED
            confirmedName = confirmedName ?: secondary
            updated = true
        }

        if (!updated) {
            return
        }

        if (identityData.creationState < IdentityCreationState.DONE) {
            // The interrupted flow can never finish its own state machine —
            // the name is already on chain, so a "retry" from the stuck
            // state would double-register. Complete it here.
            identityData.creationState = IdentityCreationState.DONE_AND_DISMISS
            identityData.creationStateErrorMessage = null
        }
        identityRepository.updateBlockchainIdentityData(identityData)

        // Same follow-up as the won-vote path: the local profile row must
        // carry the confirmed name.
        confirmedName?.let { name ->
            identityRepository.getLocalUserProfile()?.let { profile ->
                if (profile.username.isEmpty()) {
                    dashPayProfileDao.insert(profile.copy(username = name))
                }
            }
        }
    }

    /**
     * Platform truth for one label: SUCCESS + a domain document whose
     * identity record points at [identityId]. Errors and misses are false —
     * the reconcile only ever acts on positive evidence.
     */
    private fun isUsernameRegisteredToIdentity(username: String, identityId: Identifier): Boolean {
        return try {
            val resource = platformRepo.getUsername(username)
            val document = resource.data
            if (resource.status != Status.SUCCESS || document == null) {
                return false
            }
            val domain = DomainDocument(document)
            domain.dashUniqueIdentityId == identityId || domain.dashAliasIdentityId == identityId
        } catch (e: Exception) {
            log.warn("reconcile: username lookup failed for '{}'", username, e)
            false
        }
    }

    private suspend fun checkVotingStatus(identityData: BlockchainIdentityData) {
        if (identityData.username != null && identityData.creationState == IdentityCreationState.VOTING) {
            log.info("checking the vote status of {}", identityData.username)
            // query username first to load the data contract cache
            val resource = platformRepo.getUsername(identityData.username!!)
            val voteResults = platformRepo.getVoteContenders(identityData.username!!)
            if (voteResults.winner.isPresent) {
                val winner = voteResults.winner.get().first
                when {
                    winner.isLocked -> {
                        identityData.usernameRequested = UsernameRequestStatus.LOCKED
                        syncScope.launch { identityRepository.updateBlockchainIdentityData(identityData) }
                    }

                    winner.isWinner(Identifier.from(identityData.userId)) -> {
                        identityData.usernameRequested = UsernameRequestStatus.APPROVED
                        syncScope.launch {
                            identityRepository.updateBlockchainIdentityData(identityData)
                            identityRepository.getLocalUserProfile()?.let {
                                dashPayProfileDao.insert(it.copy(username = identityData.username!!))
                            }
                        }
                    }

                    winner.noWinner -> {
                        // ?
                    }

                    else -> {
                        identityData.usernameRequested = UsernameRequestStatus.LOST_VOTE
                        syncScope.launch { identityRepository.updateBlockchainIdentityData(identityData) }
                    }
                }
                if (resource.status == Status.SUCCESS && resource.data != null) {
                    val domainDocument = DomainDocument(resource.data)
                    if (domainDocument.dashUniqueIdentityId == identityData.identity?.id) {
                        identityData.creationState = IdentityCreationState.DONE_AND_DISMISS
                        identityRepository.updateBlockchainIdentityData(identityData)
                    }
                } else {

                }
            }
        }
    }

    override fun addContactsUpdatedListener(listener: OnContactsUpdated) {
        onContactsUpdatedListeners.add(listener)
    }

    override fun removeContactsUpdatedListener(listener: OnContactsUpdated?) {
        onContactsUpdatedListeners.remove(listener)
    }

    override fun fireContactsUpdatedListeners() {
        for (listener in onContactsUpdatedListeners) {
            listener.onContactsUpdated()
        }
    }

    override suspend fun clearDatabases() {
        // Push all changes to platform before clearing the database tables —
        // best-effort and time-bounded: this is a NETWORK + signing operation
        // running inside a wallet reset (the wallet may already be unloading),
        // and a hang or throw here must never block the wipe or prevent the
        // clears below (partially-cleared DashPay state resurrects the
        // DashPay UI on the next wallet).
        if (Constants.SUPPORTS_PLATFORM && dashPayConfig.shouldSaveOnReset()) {
            try {
                withTimeout(30_000) {
                    publishChangeCache(System.currentTimeMillis(), saveAll = true) // Before now - push everything
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                log.warn("pre-reset metadata publish failed/timed out; continuing with the clears", e)
            }
        }
        transactionMetadataChangeCacheDao.clear()
        transactionMetadataDocumentDao.clear()
    }

    override fun addPreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.add(listener)
    }

    override fun removePreBlockProgressListener(listener: OnPreBlockProgressListener) {
        onPreBlockContactListeners.remove(listener)
    }

    private fun firePreBlockProgressListeners(stage: PreBlockStage) {
        for (listener in onPreBlockContactListeners) {
            listener.onPreBlockProgressUpdated(stage)
        }
    }

    private var hasCheckedTopups = false // only run once
    private suspend fun checkTopUps(myEncryptionKey: KeyParameter?) {
        if (!hasCheckedTopups) {
            topUpRepository.checkTopUps(myEncryptionKey)
            hasCheckedTopups = true
        }
    }

    /**
     * This method looks at all items in the database tables
     * that have existing identites and saves them for future use.
     *
     * Sometimes Platform Nodes return IdentityNotFound Errors and
     * this list is used to determine if that node should be banned
     */
    private suspend fun initializeStateRepository() {
        // load our id
        val blockchainIdentity = identityRepository.blockchainIdentity
        if (blockchainIdentity != null && blockchainIdentity.isRegistered()) {
            val identityId = blockchainIdentity.uniqueIdString
            platform.stateRepository.addValidIdentity(Identifier.from(identityId))

            // load all id's of users who have sent us a contact request
            dashPayContactRequestDao.loadFromOthers(identityId).forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }

            // load all id's of users for whom we have profiles
            dashPayProfileDao.loadAll().forEach {
                platform.stateRepository.addValidIdentity(it.userIdentifier)
            }

            platform.stateRepository.storeIdentity(blockchainIdentity.identity!!)
        }
    }
}
