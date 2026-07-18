/*
 * Copyright 2023 Dash Core Group.
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
package de.schildbach.wallet.ui.username.request

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.CREATION_STATE
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.IDENTITY_ID
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.USERNAME_REQUESTED
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.platform.PlatformHealth
import de.schildbach.wallet.service.platform.PlatformHealthProbe
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.platform.sdk.SdkShieldedUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameSubmitState
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.IdentityCreationStatusHolder
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.RetryStatusHint
import de.schildbach.wallet.ui.dashpay.work.BroadcastIdentityVerifyOperation
import de.schildbach.wallet.ui.main.resolveRequestedUsernameDisplay
import de.schildbach.wallet.ui.username.CreateUsernameArgs
import de.schildbach.wallet.ui.username.UsernameType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dashj.platform.dashpay.UsernameRequestStatus
import org.dashj.platform.dashpay.UsernameStatus
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.DomainDocument
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.math.max

data class RequestUserNameUIState(
    val usernameVerified: Boolean = false,
    val usernameRequestSubmitting: Boolean = false,
    val usernameRequestSubmitted: Boolean = false,
    val checkingUsername: Boolean = false,
    val usernameCheckSuccess: Boolean = false,
    /**
     * The availability lookup itself failed (network/DAPI/SDK read path)
     * — distinct from "checked and taken". Fail CLOSED: a failed lookup
     * says nothing about availability, so the request button stays
     * disabled and the screen says the check could not run (the old
     * default read a failed lookup as "name is free" — observed live
     * with an already-registered name). Cleared by the next re-check.
     */
    val usernameCheckFailed: Boolean = false,
    val usernameSubmittedError: Boolean = false,
    /**
     * A shielded submit was refused because the pool is not ready YET
     * (runtime still bringing up, or still syncing so its balance is a
     * mid-sync placeholder) — see [SdkShieldedUsernameCreation
     * .isPoolNotReadyReason]. Provably nothing was spent, so this is a
     * calm "still preparing, try again in a moment" surface, NOT the red
     * network-error dialog. Fix B's live gate normally keeps the button
     * disabled while syncing; this handles the residual race where a
     * submit still reaches the SDK.
     */
    val usernameSubmittedPoolSyncing: Boolean = false,
    /**
     * The shielded creation's outcome is UNCONFIRMED (may already be on
     * chain; the spent notes stay reserved) — the UI must NOT offer a
     * retry and must not claim "no extra cost"; the app reconciles on
     * the next sync/restart.
     */
    val usernameSubmittedAmbiguous: Boolean = false,
    val usernameLengthValid: Boolean = false,
    val usernameCharactersValid: Boolean = false,
    val usernameTooShort: Boolean = true,  // default zero length username
    val usernameContestable: Boolean = false,
    val usernameContested: Boolean = false,
    val usernameExists: Boolean = false,
    val usernameBlocked: Boolean = false,
    val enoughBalance: Boolean = false,
    /**
     * What the chosen payment source actually requires for this name, as a
     * plain DASH string for the insufficient-balance row — path- and
     * contested-dependent (L1 0.03/0.25, shielded denomination 0.1/0.3,
     * identity credits 0.01/0.2). Empty until a name has been checked.
     */
    val requiredAmount: String = "",
    val usernameNonContestedChars: Boolean = false,
    val usernameNonContestedLength: Boolean = false,
    val votingPeriodStart: Long = System.currentTimeMillis(),
    /**
     * ADVISORY network-health flag: the platform side reported a core
     * chain height lagging our local tip when the screen was entered
     * (see [PlatformHealthProbe]) — asset-lock-funded operations will
     * likely take extra minutes. Renders as a warning row only; it must
     * NEVER disable the submit button (probe failures stay false).
     */
    val networkSlow: Boolean = false,
    /**
     * LIVE shielded pool sync status, mirrored from the shielded service
     * so the request button can gate reactively (Fix B): while the
     * shielded funding path is selected and this is not [ShieldedSyncStatus
     * .READY] the button shows the disabled "Preparing shielded balance…"
     * pending state instead of an enabled "Request Username", and
     * re-enables automatically when the status reaches READY. Only ever
     * consulted for the shielded path — see [usernameSubmitButtonState].
     */
    val shieldedSyncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY
)

/**
 * Tri-state of the username request button, computed PURELY so the
 * decision is unit-testable in isolation (the fragment only maps the
 * result to `isEnabled` + label).
 */
enum class UsernameSubmitButtonState {
    /** Ready to submit — enabled, normal "Request Username" label. */
    Enabled,

    /**
     * Shielded funding is selected but the pool is not READY yet — a
     * DISABLED "Preparing shielded balance…" pending state that re-enables
     * automatically once the sync reaches READY (Fix B).
     */
    PreparingShielded,

    /** Not submittable (validity / availability / balance not satisfied). */
    Disabled
}

/**
 * Pure decision for the request button's enabled state and label. Only
 * the shielded funding path is gated on [shieldedSyncStatus]: while that
 * path is selected and the pool is not [ShieldedSyncStatus.READY] the
 * button is the disabled [UsernameSubmitButtonState.PreparingShielded]
 * pending state (the button must reflect the LIVE pool status, never a
 * stale READY cache that let a submit reach the SDK and bounce with a
 * generic error). The L1/Dash-balance path is NEVER gated on the shielded
 * status. Secondary (instant) usernames are funded from the already-created
 * identity, so the shielded status is irrelevant to them.
 */
fun usernameSubmitButtonState(
    usernameType: UsernameType,
    paymentSource: UsernamePaymentSource,
    shieldedSyncStatus: ShieldedSyncStatus,
    enoughBalance: Boolean,
    usernameExists: Boolean,
    usernameContestable: Boolean
): UsernameSubmitButtonState {
    if (usernameType == UsernameType.Secondary) {
        return if (!usernameExists && !usernameContestable) {
            UsernameSubmitButtonState.Enabled
        } else {
            UsernameSubmitButtonState.Disabled
        }
    }
    if (paymentSource == UsernamePaymentSource.SHIELDED_BALANCE &&
        shieldedSyncStatus != ShieldedSyncStatus.READY
    ) {
        return UsernameSubmitButtonState.PreparingShielded
    }
    return if (enoughBalance && !usernameExists) {
        UsernameSubmitButtonState.Enabled
    } else {
        UsernameSubmitButtonState.Disabled
    }
}

/** Where the create-username flow lands the user after a completed submit. */
enum class UsernameCompletionRoute {
    /** Back to Home — a non-contested completion shows the home welcome tile. */
    HOME,

    /**
     * To the More screen — a CONTESTED / in-voting username has no home
     * welcome tile; its status lives on the More screen's username-voting
     * tile, so the completion must land there instead of returning to Home.
     */
    MORE
}

/**
 * Pure post-completion routing decision (host-JVM unit-testable, following
 * the [usernameSubmitButtonState] helper pattern). A contested name goes to
 * [IdentityCreationState.VOTING] and is surfaced on the More screen's voting
 * tile, so route [UsernameCompletionRoute.MORE]; everything else returns
 * [UsernameCompletionRoute.HOME]. The signals are OR-ed so whichever is
 * available at the finish site suffices:
 *
 * - [creationState] == VOTING: the persisted identity state;
 * - [usernameContestable]: the UI-known contestability of the name the
 *   FINISHING screen submitted;
 * - [primaryUsernameContestable]: the contestability of the flow's PRIMARY
 *   name. Load-bearing for a DUAL creation (contested primary + instant
 *   secondary): the flow's LAST screen is the secondary one, whose own
 *   [usernameContestable] is false by definition (instant names are
 *   non-contestable) and whose dismiss can precede the VOTING flip — the
 *   primary's contestability must still route MORE (observed live: dual
 *   completion returned Home).
 */
fun usernameCompletionRoute(
    creationState: IdentityCreationState?,
    usernameContestable: Boolean,
    primaryUsernameContestable: Boolean = false
): UsernameCompletionRoute {
    return if (creationState == IdentityCreationState.VOTING ||
        usernameContestable ||
        primaryUsernameContestable
    ) {
        UsernameCompletionRoute.MORE
    } else {
        UsernameCompletionRoute.HOME
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RequestUserNameViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    private val identityConfig: BlockchainIdentityConfig,
    val walletData: WalletData,
    val platformRepo: PlatformRepo,
    val usernameRequestDao: UsernameRequestDao,
    val analytics: AnalyticsService,
    val topUpRepository: TopUpRepository,
    private val shieldedUsernameCreation: SdkShieldedUsernameCreation,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val platformHealthProbe: PlatformHealthProbe,
    private val identityCreationStatus: IdentityCreationStatusHolder
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(RequestUserNameViewModel::class.java)
        private val CONTEST_DOCUMENT_FEE = Coin.valueOf(0, 20).value * 1000
        private val NON_CONTEST_DOCUMENT_FEE = Coin.valueOf(1000000).value * 1000
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    override fun onCleared() {
        // viewModelScope is cancelled by the framework, but the IO worker
        // scope is ours to stop — its launchIn collectors never complete on
        // their own and would outlive the screen.
        workerJob.cancel()
        super.onCleared()
    }

    private val _uiState = MutableStateFlow(RequestUserNameUIState())
    val uiState: StateFlow<RequestUserNameUIState> = _uiState.asStateFlow()

    /**
     * The live transient registration status hint (30s "network catching
     * up" watchdog, per-attempt "waiting for confirmation"), surfaced by
     * the shared registration path via [IdentityCreationStatusHolder]. The
     * home-screen tile already shows this, but during creation the user is
     * watching the processing dialog — [UsernameSubmitStatusDialogs]
     * observes this to show the same copy as a live secondary line there.
     */
    val identityCreationStatusHint: StateFlow<RetryStatusHint?>
        get() = identityCreationStatus.statusHint

    private val _requestedUserNameLink = MutableStateFlow<String?>(null)
    val requestedUserNameLink: StateFlow<String?> = _requestedUserNameLink.asStateFlow()

    var identity: BlockchainIdentityData? = null
    var requestedUserName: String? = null
    var requestedUsernameSecondary: String? = null

    /**
     * Which balance the user chose to pay the username fee from on the
     * "Select your payment option" sheet (Figma 1856:1805). Selecting the
     * shielded balance starts observing the pool (lazily — the SDK runtime
     * is never touched on the default L1 path) so [checkUsernameValid] can
     * gate contested names on the 0.3 funding denomination. Replaces the
     * removed CoinJoin mixed/unmixed funding selection.
     */
    var paymentSource: UsernamePaymentSource = UsernamePaymentSource.DASH_BALANCE
        set(value) {
            field = value
            if (value == UsernamePaymentSource.SHIELDED_BALANCE) {
                startShieldedObservation()
            }
        }

    private val _shieldedBalance = MutableStateFlow(Dash.ZERO)
    private val _shieldedSyncStatus = MutableStateFlow(ShieldedSyncStatus.NOT_READY)
    private var shieldedObservationStarted = false

    /**
     * Mirror the shielded pool balance/status into this ViewModel (same
     * collection pattern as `UsernamePaymentViewModel`). Idempotent; only
     * ever called after the payment sheet offered — and the user picked —
     * the shielded source, so the runtime is already up and the flag on.
     */
    private fun startShieldedObservation() {
        synchronized(this) {
            if (shieldedObservationStarted) return
            shieldedObservationStarted = true
        }
        viewModelScope.launch {
            launch {
                runCatching { shieldedBalanceService.ensureShieldedReady() }
                    .onFailure { log.warn("shielded bring-up failed", it) }
                // Immediately re-evaluate the pool (incl. freshly-submitted
                // pending wallet-shield locks): entering this screen right
                // after a shield could catch a stale READY from before the
                // pending lock registered, briefly enabling the button with
                // no "Preparing shielded balance" gate (observed on S22).
                // Same entry-kick pattern as the More screen's syncNow().
                runCatching { shieldedBalanceService.syncNow() }
                    .onFailure { log.warn("shielded entry sync kick failed", it) }
            }
            launch {
                shieldedBalanceService.observeShieldedBalance()
                    .catch { log.warn("shielded balance flow failed", it) }
                    .collect {
                        _shieldedBalance.value = it
                        recomputeBalanceGate()
                    }
            }
            launch {
                shieldedBalanceService.shieldedSyncStatus
                    .catch { log.warn("shielded status flow failed", it) }
                    .collect {
                        _shieldedSyncStatus.value = it
                        // Mirror the LIVE status into uiState so the request
                        // button's gate is reactive (Fix B) — the button
                        // re-enables the moment the pool reaches READY.
                        _uiState.update { s ->
                            if (s.shieldedSyncStatus == it) s else s.copy(shieldedSyncStatus = it)
                        }
                        recomputeBalanceGate()
                    }
            }
        }
    }

    /**
     * A contested username funded from the shielded pool needs the 0.3
     * DASH exit denomination (0.25 contested fee → smallest covering
     * denomination), and the balance is only evidence once a sync pass
     * completed — mid-sync zeros are placeholders.
     */
    private fun canShieldedFundContestedUsername(): Boolean {
        if (_shieldedSyncStatus.value != ShieldedSyncStatus.READY) return false
        val requirement = shieldedIdentityFundingRequirement(Dash(Constants.DASH_PAY_FEE_CONTESTED.value))
            ?: return false
        return _shieldedBalance.value >= requirement
    }

    private val _identityBalance = MutableStateFlow(0L)
    val identityBalance: StateFlow<Long>
        get() = _identityBalance

    private val _walletBalance = MutableStateFlow(Coin.ZERO)
    val walletBalance: StateFlow<Coin>
        get() = _walletBalance

    private var createUsernameArgs: CreateUsernameArgs? = null
    private val inviteAssetLockTx = MutableStateFlow<AssetLockTransaction?>(null)
    private val _inviteBalance = MutableStateFlow(Coin.ZERO)
    val inviteBalance: StateFlow<Coin>
        get() = _inviteBalance

    fun setCreateUsernameArgs(createUsernameArgs: CreateUsernameArgs?) {
        createUsernameArgs?.let {
            this.createUsernameArgs = it
            viewModelScope.launch {
                getInviteAssetLockTransaction()
            }
        }
    }

    suspend fun isUserNameRequested(): Boolean {
        val hasRequestedName = identityConfig.get(USERNAME).isNullOrEmpty().not()
        val creationState = IdentityCreationState.valueOf(
            identityConfig.get(CREATION_STATE) ?: IdentityCreationState.NONE.name
        )
        return hasRequestedName && creationState != IdentityCreationState.NONE && creationState.ordinal <= IdentityCreationState.VOTING.ordinal
    }

    suspend fun isUsernameLocked(): Boolean {
        return isUserNameRequested() &&
                UsernameRequestStatus.valueOf(identityConfig.get(USERNAME_REQUESTED)!!) == UsernameRequestStatus.LOCKED
    }

    suspend fun isUsernameLostAfterVoting(): Boolean {
        return isUserNameRequested() &&
                UsernameRequestStatus.valueOf(identityConfig.get(USERNAME_REQUESTED)!!) == UsernameRequestStatus.LOST_VOTE
    }

    suspend fun isUsernameInVotingState(): Boolean {
        return IdentityCreationState.valueOf(identityConfig.get(CREATION_STATE) ?: "NONE") >= IdentityCreationState.VOTING
    }

    /**
     * Whether the identity state machine has started (creationState !=
     * NONE — the signal MoreFragment keys off) or an identity id already
     * exists. False means the pristine CREATE path: no username, no
     * identity, nothing in flight — the caller must show the full
     * designed welcome flow.
     */
    suspend fun hasIdentityOrCreationStarted(): Boolean {
        val creationState = IdentityCreationState.valueOf(
            identityConfig.get(CREATION_STATE) ?: IdentityCreationState.NONE.name
        )
        return creationState != IdentityCreationState.NONE || !identityConfig.get(IDENTITY_ID).isNullOrEmpty()
    }

    suspend fun hasUserCancelledVerification(): Boolean =
        identityConfig.get(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK) ?: false

    fun canAffordNonContestedUsername(): Boolean {
        return when {
            isUsingInvite() -> {
                false
            }

            identity?.userId != null -> {
                val credits = _identityBalance.value
                credits > Constants.DASH_PAY_FEE.value / 10 * 1000
            }

            else -> {
                _walletBalance.value >= Constants.DASH_PAY_FEE
            }
        }
    }

    fun canAffordContestedUsername(): Boolean {
        return if (identity?.userId != null) {
            val credits = _identityBalance.value
            credits > CONTEST_DOCUMENT_FEE
        } else {
            _walletBalance.value >= Constants.DASH_PAY_FEE_CONTESTED
        }
    }

    val myUsernameRequest: Flow<UsernameRequest?>
        get() = _myUsernameRequest
    private val _myUsernameRequest = MutableStateFlow<UsernameRequest?>(null)

    init {
        viewModelScope.launch {
            _requestedUserNameLink.value = withContext(Dispatchers.IO) {
                identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK)
            }
        }
        // L1/non-shielded creation completion feedback. The L1 branch of
        // submit() starts CreateIdentityService and sets
        // usernameRequestSubmitting=true, but that service runs out-of-band
        // and never reported back — so the processing dialog stayed up
        // forever until the user dismissed it manually (the identity was
        // already created behind it). Observe the persisted creation state
        // and, once it reaches a terminal state while a submit is in
        // flight, clear submitting (the dialog dismisses) and mark the
        // request submitted so the flow completes. The shielded path clears
        // submitting via its own executor states, so this is a no-op there.
        identityConfig.observe(CREATION_STATE)
            .filterNotNull()
            .onEach { raw ->
                val state = runCatching { IdentityCreationState.valueOf(raw) }
                    .getOrDefault(IdentityCreationState.NONE)
                val terminal = state == IdentityCreationState.DONE ||
                    state == IdentityCreationState.DONE_AND_DISMISS ||
                    state == IdentityCreationState.VOTING
                if (terminal && _uiState.value.usernameRequestSubmitting) {
                    log.info("identity creation reached {} — clearing the processing dialog", state)
                    _uiState.update {
                        it.copy(usernameRequestSubmitting = false, usernameRequestSubmitted = true)
                    }
                }
            }
            .launchIn(viewModelScope)
        identityConfig.observe(IDENTITY_ID)
            .filterNotNull()
            .onEach {
                identity = identityConfig.load()
                _identityBalance.value = identity?.let { identity ->
                    try {
                        platformRepo.getIdentityBalance(Identifier.from(identity.userId)).balance
                    } catch (e: Exception) {
                        // need to try again later
                        -1
                    }
                } ?: 0
                log.info("identity balance: {}", identityBalance)
                if (requestedUserName == null) {
                    // Prefer the DISPLAY form of the name: the restore path
                    // historically persisted the DPNS-normalized label into
                    // the USERNAME pref, and the display label lives on the
                    // locally known username request (also makes the
                    // requestId lookup below match — rows are keyed by the
                    // display label).
                    requestedUserName = identityConfig.get(USERNAME)?.let { stored ->
                        if (stored.isEmpty()) {
                            stored
                        } else {
                            resolveRequestedUsernameDisplay(
                                stored,
                                it,
                                try {
                                    usernameRequestDao.getRequestsByNormalizedLabel(stored)
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            )
                        }
                    }
                }
            }
            .flatMapLatest { usernameRequestDao.observeRequest(UsernameRequest.getRequestId(it, requestedUserName ?: "")) }
            .onEach {
                if (it != null) {
                    _myUsernameRequest.value = it
                } else if (requestedUserName != null) {
                    identity?.let { identityData ->
                        _myUsernameRequest.value = UsernameRequest(
                            UsernameRequest.getRequestId(identityData.userId!!, requestedUserName!!),
                            requestedUserName!!,
                            Names.normalizeString(requestedUserName!!),
                            identityData.votingPeriodStart ?: -1L,
                            identityData.userId!!,
                            identityData.verificationLink ?: "",
                            0,
                            0,
                            false
                        )
                    }
                } else {
                    _myUsernameRequest.value = null
                }
            }
            .launchIn(viewModelWorkerScope)

        walletData.observeBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
            .onEach {
                _walletBalance.value = it
            }
            .launchIn(viewModelScope)

        inviteAssetLockTx.onEach {
            _inviteBalance.value = getInvitationAmount()
        }.launchIn(viewModelWorkerScope)

        // Mirror the app-scoped shielded username creation into this
        // ViewModel's uiState — the operation (a ~30s Halo 2 proof) runs on
        // the application scope and outlives any screen; a recreated
        // ViewModel re-attaches here and can never re-submit by observing.
        viewModelScope.launch {
            shieldedUsernameCreation.submitState.collect { state ->
                when (state) {
                    ShieldedUsernameSubmitState.Idle -> Unit
                    ShieldedUsernameSubmitState.Proving ->
                        _uiState.update { it.copy(usernameRequestSubmitting = true) }
                    is ShieldedUsernameSubmitState.Created -> {
                        log.info("shielded username creation completed: {}", state.outcome.nameStatus)
                        _uiState.update {
                            it.copy(usernameRequestSubmitting = false, usernameRequestSubmitted = true)
                        }
                        // Result surfaced; the legacy handoff was enqueued by
                        // the service — the blockchainIdentity observers take
                        // over from here.
                        shieldedUsernameCreation.acknowledge()
                    }
                    is ShieldedUsernameSubmitState.NotSent -> {
                        log.warn("shielded username creation not sent: {}", state.reason)
                        // The pool-not-ready refusals ("still syncing" /
                        // "runtime not ready") are transient, not errors —
                        // surface the calm "still preparing" message rather
                        // than the red "network error" dialog (Fix A).
                        val poolNotReady = SdkShieldedUsernameCreation.isPoolNotReadyReason(state.reason)
                        _uiState.update {
                            if (poolNotReady) {
                                it.copy(
                                    usernameRequestSubmitting = false,
                                    usernameSubmittedPoolSyncing = true
                                )
                            } else {
                                it.copy(usernameRequestSubmitting = false, usernameSubmittedError = true)
                            }
                        }
                        // Provably nothing spent — retry-safe; reset so a
                        // retry can re-submit.
                        shieldedUsernameCreation.acknowledge()
                    }
                    ShieldedUsernameSubmitState.MayHaveGoneThrough -> {
                        // Distinct from usernameSubmittedError: the generic
                        // error dialog says "try again at no extra cost" and
                        // offers a retry — both wrong here (observed live:
                        // an ambiguous creation surfaced the retry dialog).
                        _uiState.update {
                            it.copy(usernameRequestSubmitting = false, usernameSubmittedAmbiguous = true)
                        }
                        // Deliberately NOT acknowledged: the state stays
                        // sticky and submit() keeps refusing — an ambiguous
                        // outcome must never be retried (funds safety).
                    }
                }
            }
        }
    }

    /**
     * Whether a network-health probe is currently in flight — the screen
     * calls [checkNetworkHealth] on every entry, and a still-running
     * probe must not be duplicated (it is a network call).
     */
    private var networkHealthProbeInFlight = false

    /**
     * One-shot ADVISORY platform-health probe, run when the username
     * entry screen is entered (once per entry; never per keystroke).
     * DEGRADED shows the "network is running slower than usual" row;
     * UNKNOWN and probe failures show nothing. The result must never
     * gate the submit button.
     */
    fun checkNetworkHealth() {
        if (networkHealthProbeInFlight) return
        networkHealthProbeInFlight = true
        viewModelScope.launch {
            try {
                val health = platformHealthProbe.probe()
                _uiState.update { it.copy(networkSlow = health == PlatformHealth.DEGRADED) }
            } finally {
                networkHealthProbeInFlight = false
            }
        }
    }

    private fun triggerIdentityCreation(reuseTransaction: Boolean) {
        val username = requestedUserName!!
        val usernameSecondary = requestedUsernameSecondary
        val isUsingInvite = isUsingInvite()
        when {
            isUsingInvite && reuseTransaction -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentFromInviteForNewUsername(
                        walletApplication,
                        username,
                        usernameSecondary
                    )
                )
            }
            isUsingInvite -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentFromInvite(
                        walletApplication,
                        username,
                        usernameSecondary,
                        createUsernameArgs!!.invite!!
                    )
                )
            }
            reuseTransaction -> {
                walletApplication.startService(
                    CreateIdentityService.createIntentForNewUsername(
                        walletApplication,
                        username,
                        usernameSecondary
                    )
                )
            }
            else -> {
                walletApplication.startService(
                    CreateIdentityService.createIntent(
                        walletApplication,
                        username,
                        usernameSecondary
                    )
                )
            }
        }
    }

    fun submit() {
        // Reset ui state for retry if needed
        resetUiForRetrySubmit()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { updateConfig() }
            // send the request / create username, assume not retry
            val reuseTransaction = identity?.let {
                it.usernameRequested == UsernameRequestStatus.LOCKED || it.usernameRequested == UsernameRequestStatus.LOST_VOTE
            } ?: false
            if (paymentSource == UsernamePaymentSource.SHIELDED_BALANCE &&
                !isUsingInvite() && !reuseTransaction
            ) {
                // The user picked the shielded balance on the payment-option
                // sheet: fund the identity DIRECTLY from the shielded pool
                // (Type 20) instead of the L1 asset-lock path. Invite and
                // reuse-transaction submissions never reach here — their
                // funding is already committed elsewhere.
                log.info("routing username creation to the shielded-funded SDK path")
                val accepted = shieldedUsernameCreation.submit(requestedUserName!!, requestedUsernameSecondary)
                if (!accepted) {
                    // A refused submit must NEVER die silently (observed
                    // live: a swallowed submit reads as a broken app).
                    // Surface a state the dialogs react to, matched to why
                    // the executor refused.
                    val state = shieldedUsernameCreation.submitState.value
                    log.info("username submit rejected: shielded creation refused (executor state: {})", state)
                    _uiState.update {
                        when (state) {
                            // An earlier operation is still proving — show
                            // its processing status instead of an error.
                            ShieldedUsernameSubmitState.Proving ->
                                it.copy(usernameRequestSubmitting = true)
                            // Sticky unconfirmed outcome: never a retryable
                            // error dialog.
                            ShieldedUsernameSubmitState.MayHaveGoneThrough ->
                                it.copy(usernameSubmittedAmbiguous = true)
                            else -> it.copy(usernameSubmittedError = true)
                        }
                    }
                }
            } else {
                // The L1/asset-lock path (Dash balance, invite, dual and
                // reuse-transaction submissions): CreateIdentityService
                // takes over and the screen closes once the identity state
                // machine starts, but that leaves a feedback gap between
                // the tap and the state flip. Show the same processing
                // status the shielded path shows; it is dismissed with the
                // screen (or replaced by the error state below).
                _uiState.update { it.copy(usernameRequestSubmitting = true) }
                triggerIdentityCreation(reuseTransaction)
            }
        }
    }

    fun reset() {
        lastGateUsername = null
        // networkSlow is screen-entry-scoped (advisory probe result), not
        // per-username state — clearing the input must not wipe it.
        _uiState.update { RequestUserNameUIState(networkSlow = it.networkSlow) }
    }

    private fun resetUiForRetrySubmit() {
        _uiState.update {
            it.copy(
                usernameSubmittedError = false,
                usernameSubmittedPoolSyncing = false,
                usernameRequestSubmitted = false,
                usernameRequestSubmitting = false
            )
        }
    }
    fun setRequestedUserNameLink(link: String) {
        _requestedUserNameLink.value = link
    }

    private suspend fun updateConfig() {
        requestedUserName?.let { name ->
            identityConfig.set(USERNAME, name)
        }
        _requestedUserNameLink.value.let { link ->
            identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, link ?: "")
        }
    }

    fun checkUsername(requestedUserName: String?) {
        viewModelScope.launch {
            requestedUserName?.let { username ->
                _uiState.update { it.copy(checkingUsername = true, usernameCheckFailed = false) }
                val usernameSearchResult = withContext(Dispatchers.IO) { platformRepo.getUsername(username) }
                if (usernameSearchResult.status != Status.SUCCESS) {
                    // Fail CLOSED: a lookup that never completed says
                    // nothing about availability — the old default read it
                    // as "name is free" (observed live: a registered name
                    // showed as available on-device).
                    log.warn(
                        "checkUsername('{}'): availability lookup failed (status={}, message={})",
                        username, usernameSearchResult.status, usernameSearchResult.message,
                        usernameSearchResult.exception
                    )
                    _uiState.update {
                        it.copy(checkingUsername = false, usernameCheckSuccess = false, usernameCheckFailed = true)
                    }
                    return@launch
                }
                val usernameExists = usernameSearchResult.data != null
                var usernameContested = false
                var firstCreatedAt = -1L
                val usernameBlocked = try {
                    withContext(Dispatchers.IO) {
                        val contenders = platformRepo.getVoteContendersOrThrow(username)
                        usernameContested = contenders.map.isNotEmpty()
                        var maxApprovalVotes = 0
                        firstCreatedAt = try {
                            contenders.map.values.minOf { contender ->
                                val document = contender.serializedDocument?.let {
                                    DomainDocument(platformRepo.platform.names.deserialize(it))
                                }
                                maxApprovalVotes = max(contender.votes, maxApprovalVotes)
                                document?.createdAt ?: -1
                            }
                        } catch (e: NoSuchElementException) {
                            -1L
                        }

                        // is the name blocked
                        firstCreatedAt == -1L && contenders.lockVoteTally > maxApprovalVotes
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Same fail-closed discipline: a failed contest lookup
                    // must not pass as "not contested".
                    log.warn("checkUsername('{}'): vote-contenders lookup failed", username, e)
                    _uiState.update {
                        it.copy(checkingUsername = false, usernameCheckSuccess = false, usernameCheckFailed = true)
                    }
                    return@launch
                }
                // One line per completed query, for on-device forensics.
                log.info(
                    "checkUsername('{}'): exists={} contested={} blocked={}",
                    username, usernameExists, usernameContested, usernameBlocked
                )
                _uiState.update {
                    it.copy(
                        checkingUsername = false,
                        usernameCheckSuccess = true,
                        usernameCheckFailed = false,
                        usernameSubmittedError = false,
                        usernameSubmittedPoolSyncing = false,
                        usernameContested = usernameContested, usernameExists = usernameExists,
                        usernameBlocked = usernameBlocked,
                        votingPeriodStart = if (firstCreatedAt == -1L) System.currentTimeMillis() else firstCreatedAt
                    )
                }
            }
        }
    }

    fun verify() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, _requestedUserNameLink.value ?: "")
                identityConfig.get(IDENTITY_ID)?.let { identityId ->
                    // this may always return null because the request hasn't been added yet.
                    val usernameRequest = usernameRequestDao.getRequest(
                        UsernameRequest.getRequestId(
                            identityId,
                            requestedUserName!!
                        )
                    )
                    usernameRequest?.let { request ->
                        request.link = _requestedUserNameLink.value
                        usernameRequestDao.update(usernameRequest)
                    }
                }
            }
            _uiState.update {
                it.copy(
                    usernameVerified = true
                )
            }
        }
    }

    @Deprecated("requests cannot be canceled")
    fun cancelRequest() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                identityConfig.set(BlockchainIdentityConfig.USERNAME, "")
                identityConfig.set(BlockchainIdentityConfig.REQUESTED_USERNAME_LINK, "")
                identityConfig.set(BlockchainIdentityConfig.CANCELED_REQUESTED_USERNAME_LINK, true)
            }
        }
    }

    private fun validateUsernameSize(uname: String, usernameType: UsernameType): Boolean {
        return when (usernameType) {
            UsernameType.Primary -> uname.length in Constants.USERNAME_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH
            UsernameType.Secondary -> uname.length in Constants.USERNAME_MIN_LENGTH..(Constants.USERNAME_MAX_LENGTH + 4)
        }
    }

    private fun validateNonContestedUsernameSize(uname: String): Boolean {
        return uname.length in Constants.USERNAME_NON_CONTESTED_MIN_LENGTH..Constants.USERNAME_MAX_LENGTH
    }

    private fun validateUsernameCharacters(uname: String): Pair<Boolean, Boolean> {
        val alphaNumHyphenValid = !Regex("[^a-zA-Z0-9\\-]").containsMatchIn(uname)
        val startOrEndWithHyphen = uname.startsWith("-") || uname.endsWith("-")
        return Pair(alphaNumHyphenValid, startOrEndWithHyphen)
    }

    private fun validateNonContestedUsernameCharacters(uname: String): Boolean {
        return Regex("[2-9]").containsMatchIn(uname)
    }

    /**
     * The last username the balance gate was computed for, so
     * [recomputeBalanceGate] can re-resolve `enoughBalance`/`requiredAmount`
     * when an INPUT of the gate changes after typing stopped. Load-bearing
     * for the shielded source: the pool sync typically reaches READY (and
     * the balance becomes evidence) seconds AFTER the user typed the name —
     * without the recompute the contested-name gate froze at `false` and
     * the submit button stayed silently disabled (observed live:
     * test-demo1/test-demo11 swallowed with no explanation).
     */
    private var lastGateUsername: String? = null

    /**
     * `enoughBalance` + the DASH amount the insufficient-balance row must
     * name, resolved for the chosen payment source. Every failed gate is
     * logged at info level — a submit attempt that bounces must always
     * leave a forensic trail (Brian: the contested attempts left ZERO log
     * lines).
     */
    private fun computeBalanceGate(username: String, contestable: Boolean): Pair<Boolean, String> {
        val identityBalance = _identityBalance.value
        val walletBalance = _walletBalance.value
        val inviteBalance = _inviteBalance.value
        val enoughBalance = when {
            isUsingInvite() && contestable -> inviteBalance >= Constants.DASH_PAY_FEE_CONTESTED
            isUsingInvite() && !contestable -> inviteBalance >= Constants.DASH_PAY_FEE
            // Paying from the shielded pool: the welcome-screen decision
            // point only offers this source when the pool covers the
            // non-contested 0.1 denomination, and the shielded service
            // re-verifies balance/denomination in its preflight — the L1
            // wallet balance below is irrelevant to these submissions.
            // Contested names need the larger 0.3 denomination, so they
            // get their own pool-balance gate here.
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE && !contestable -> true
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE && contestable ->
                canShieldedFundContestedUsername()
            identityBalance > 0L && contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(
                CONTEST_DOCUMENT_FEE / 1000)
            identityBalance > 0L && !contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(
                NON_CONTEST_DOCUMENT_FEE / 1000)
            identityBalance == 0L && contestable -> walletBalance >= Constants.DASH_PAY_FEE_CONTESTED
            identityBalance == 0L && !contestable -> walletBalance >= Constants.DASH_PAY_FEE
            else -> false // how can we get here?
        }
        // The same branch structure, resolved to the amount the
        // insufficient-balance row must name (the old layout hardcoded
        // 0.25 for every case).
        val requiredAmount = when {
            isUsingInvite() && contestable -> Constants.DASH_PAY_FEE_CONTESTED.toPlainString()
            isUsingInvite() && !contestable -> Constants.DASH_PAY_FEE.toPlainString()
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE ->
                shieldedIdentityFundingRequirement(
                    Dash((if (contestable) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE).value)
                )?.toPlainString() ?: Constants.DASH_PAY_FEE_CONTESTED.toPlainString()
            identityBalance > 0L && contestable -> Coin.valueOf(CONTEST_DOCUMENT_FEE / 1000).toPlainString()
            identityBalance > 0L && !contestable -> Coin.valueOf(NON_CONTEST_DOCUMENT_FEE / 1000).toPlainString()
            contestable -> Constants.DASH_PAY_FEE_CONTESTED.toPlainString()
            else -> Constants.DASH_PAY_FEE.toPlainString()
        }
        if (!enoughBalance) {
            log.info(
                "username balance gate failed for '{}': source={} contestable={} required={} DASH " +
                    "(walletBalance={} identityCredits={} inviteBalance={} shieldedBalance={} shieldedSync={})",
                username,
                paymentSource,
                contestable,
                requiredAmount,
                walletBalance.toPlainString(),
                identityBalance,
                inviteBalance.toPlainString(),
                _shieldedBalance.value.toPlainString(),
                _shieldedSyncStatus.value
            )
        }
        return enoughBalance to requiredAmount
    }

    /**
     * Re-resolve the balance gate for the last-checked username after a
     * gate INPUT changed (shielded sync reaching READY, pool balance
     * landing). Only the gate fields are touched — the availability check
     * flags stay whatever the platform query left them, so a recompute
     * never knocks out an already-completed availability result.
     */
    private fun recomputeBalanceGate() {
        val username = lastGateUsername ?: return
        val contestable = try {
            Names.isUsernameContestable(username)
        } catch (e: Exception) {
            return
        }
        val (enoughBalance, requiredAmount) = computeBalanceGate(username, contestable)
        _uiState.update {
            if (it.enoughBalance == enoughBalance && it.requiredAmount == requiredAmount) {
                it
            } else {
                it.copy(enoughBalance = enoughBalance, requiredAmount = requiredAmount)
            }
        }
    }

    fun checkUsernameValid(username: String, usernameType: UsernameType): Boolean {
        val validLength = validateUsernameSize(username, usernameType)
        val (validCharacters, startOrEndWithHyphen) = validateUsernameCharacters(username)
        val contestable = Names.isUsernameContestable(username)

        lastGateUsername = username
        val (enoughBalance, requiredAmount) = computeBalanceGate(username, contestable)
        _uiState.update {
            it.copy(
                usernameLengthValid = validLength,
                usernameCharactersValid = validCharacters && !startOrEndWithHyphen,
                usernameContestable = contestable,
                enoughBalance = enoughBalance,
                requiredAmount = requiredAmount,
                usernameTooShort = username.isEmpty(),
                usernameSubmittedError = false,
                usernameSubmittedPoolSyncing = false,
                usernameCheckSuccess = false,
                usernameCheckFailed = false,
                usernameNonContestedLength = validateNonContestedUsernameSize(username),
                usernameNonContestedChars = validateNonContestedUsernameCharacters(username)
            )
        }
        return validCharacters && validLength
    }

    @Throws(NullPointerException::class)
    fun isUsernameContestable(): Boolean {
        return Names.isUsernameContestable(requestedUserName!!)
    }

    fun publishIdentityVerifyDocument() {
        _requestedUserNameLink.value?.let { url ->
            BroadcastIdentityVerifyOperation(walletApplication).create(
                requestedUserName!!,
                url
            ).enqueue()
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    suspend fun getVotingStartDate(normalizedLabel: String): Long {
        return usernameRequestDao.getRequestsByNormalizedLabel(normalizedLabel).minOf {
            it.createdAt
        }
    }

    fun isUsingInvite(): Boolean = createUsernameArgs?.invite != null

    suspend fun getInviteAssetLockTransaction(): AssetLockTransaction? = withContext(Dispatchers.IO) {
        if (isUsingInvite()) {
            inviteAssetLockTx.value = try {
                topUpRepository.getAssetLockTransaction(createUsernameArgs?.invite!!)
            } catch (e: Exception) {
                log.error("error getting asset lock tx", e)
                null
            }
        }
        inviteAssetLockTx.value
    }

    private fun getInvitationAmount(): Coin {
        return inviteAssetLockTx.value?.let {
            it.assetLockPayload.creditOutputs?.find { transactionOutput ->
                if (ScriptPattern.isP2PKH(transactionOutput.scriptPubKey)) {
                    it.assetLockPublicKey.pubKeyHash.contentEquals(
                        ScriptPattern.extractHashFromP2PKH(transactionOutput.scriptPubKey)
                    )
                } else {
                    false
                }
            }?.value ?: Coin.ZERO
        } ?: Coin.ZERO
    }

    fun isInviteForContestedNames(): Boolean = getInvitationAmount() >= Constants.DASH_PAY_FEE_CONTESTED

    suspend fun hasSecondaryName(): Boolean {
        return identityConfig.get(IDENTITY_ID) != null &&
                identityConfig.get(BlockchainIdentityConfig.USERNAME_SECONDARY) != null &&
                identityConfig.get(BlockchainIdentityConfig.USERNAME_SECONDARY_REGISTRATION_STATUS)?.let {
                    UsernameStatus.valueOf(it) == UsernameStatus.CONFIRMED
                } == true
    }
}
