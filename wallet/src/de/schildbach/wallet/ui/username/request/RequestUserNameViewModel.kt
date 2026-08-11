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
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig.Companion.CREATION_STATE_ERROR_MESSAGE
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
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.service.platform.sdk.AssetLockFundingEvidence
import de.schildbach.wallet.service.platform.sdk.SdkAssetLockFundingPreflight
import de.schildbach.wallet.service.platform.sdk.assetLockFundingVerdict
import de.schildbach.wallet.service.platform.sdk.SdkShieldedUsernameCreation
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.ShieldedUsernameSubmitState
import de.schildbach.wallet.service.platform.sdk.creditsToDash
import de.schildbach.wallet.service.platform.sdk.shieldedIdentityFundingRequirement
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.IdentityCreationStatusHolder
import de.schildbach.wallet.ui.dashpay.PlatformRepo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
     * contested-dependent (L1 0.03/0.25, v13 shielded denomination 0.03/0.25,
     * identity credits 0.01/0.2). Empty until a name has been checked.
     */
    val requiredAmount: String = "",
    /**
     * The DISPLAY balance covers the fee but the funds the asset-lock
     * build can actually select (final — confirmed/IS-locked — BIP44
     * coins; see [SdkAssetLockFundingPreflight]) do not. Renders the
     * "recently received funds may still be settling" variant of the
     * insufficient-balance row instead of the plain requirement (the
     * plain copy would gaslight a user whose balance LOOKS sufficient).
     * Only ever true together with `enoughBalance = false`.
     */
    val fundsSettling: Boolean = false,
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
    val shieldedSyncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
    /**
     * Whether the shielded funding note is ANCHORED (confirmed) and covers
     * the username denomination — the re-keyed confirmation gate. The pool
     * sync reaching [ShieldedSyncStatus.READY] clears BEFORE a freshly
     * shielded note anchors, so the button/message gate stays in
     * [UsernameSubmitButtonState.PreparingShielded] (keeping the 2-hour
     * privacy-window advisory visible) until this turns true — pre-empting
     * the `ShieldedNoRecordedAnchor` bounce of a too-soon create. Only
     * consulted on the shielded path (default false = not anchored yet).
     */
    val fundingNoteAnchored: Boolean = false
)

/**
 * Which kind of username an invitation's funding actually pays for — the
 * SINGLE source of truth the claim screen's notice, its requirement rows and
 * its submit gate must all derive from. Previously those three read different
 * things and could contradict each other on screen (the notice said
 * "non-contested only" while the button happily accepted a contested name).
 */
enum class InviteUsernameTier {
    /** The invite funded the contested denomination — any username is fine. */
    CONTESTED,

    /** The invite funded the non-contested denomination only. */
    NON_CONTESTED,

    /**
     * The invite's funding amount is NOT READABLE, so no claim may be made
     * about which usernames it supports. This is the honest state for a
     * shielded invitation minted before the link carried its note value
     * ([InvitationLinkData.shieldedFundingCredits]) — a shielded note has no
     * on-chain asset lock to read, and the SDK exposes no way to value a
     * one-time key's note before spending it. The UI must not assert a tier
     * here; it lets the user proceed, because a contested name against an
     * actually-non-contested note fails CLOSED at claim time (the FFI cannot
     * find a note covering the larger denomination and returns a
     * pre-broadcast refusal, so nothing is spent and the invite is not burnt).
     */
    UNKNOWN
}

/**
 * Resolve an invitation's [InviteUsernameTier] — pure, so the contradictory
 * combinations that shipped are covered by unit tests.
 *
 * - L1 invites carry an asset-lock txid, so the funded amount is read off
 *   chain into [l1InviteBalance] (ZERO until the lookup completes, which
 *   reads as NON_CONTESTED exactly as before — the callers re-render when
 *   the balance lands).
 * - Shielded invites have no asset lock. They carry their note value in the
 *   link when minted by a build that includes it; older links carry nothing
 *   and are [InviteUsernameTier.UNKNOWN].
 *
 * The threshold is the same for both: the contested fee. The shielded note
 * values (current v13 mints 0.03 / 0.25 DASH, legacy links 0.1 / 0.3)
 * straddle it just as the L1 amounts do — the contested mints (0.25 / 0.3)
 * are >= the 0.25 fee, the non-contested ones (0.03 / 0.1) below it.
 */
fun inviteUsernameTier(invite: InvitationLinkData?, l1InviteBalance: Coin): InviteUsernameTier {
    if (invite == null) return InviteUsernameTier.UNKNOWN
    if (!invite.isShielded) {
        return if (l1InviteBalance >= Constants.DASH_PAY_FEE_CONTESTED) {
            InviteUsernameTier.CONTESTED
        } else {
            InviteUsernameTier.NON_CONTESTED
        }
    }
    val credits = invite.shieldedFundingCredits ?: return InviteUsernameTier.UNKNOWN
    val funded = Coin.valueOf(creditsToDash(credits).duffs)
    return if (funded >= Constants.DASH_PAY_FEE_CONTESTED) {
        InviteUsernameTier.CONTESTED
    } else {
        InviteUsernameTier.NON_CONTESTED
    }
}

/**
 * Whether a claim screen driven by [tier] may submit a username whose
 * contestability is [contestable]. Only a KNOWN non-contested invite blocks
 * a contested name; an unreadable tier must not block, because refusing on a
 * guess would strand a user who genuinely paid the contested fee.
 */
fun inviteTierAllowsUsername(tier: InviteUsernameTier, contestable: Boolean): Boolean =
    when (tier) {
        InviteUsernameTier.CONTESTED -> true
        InviteUsernameTier.NON_CONTESTED -> !contestable
        InviteUsernameTier.UNKNOWN -> true
    }

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
    usernameContestable: Boolean,
    /**
     * Whether the shielded funding note is anchored/confirmed and covers the
     * denomination (see [RequestUsernameUIState.fundingNoteAnchored]). The
     * shielded gate now clears only once the pool is READY AND this is true,
     * so the button stays in [UsernameSubmitButtonState.PreparingShielded]
     * through the post-READY window where the note is not yet anchored.
     * Defaults true so non-shielded callers/tests are unaffected.
     */
    fundingNoteAnchored: Boolean = true
): UsernameSubmitButtonState {
    if (usernameType == UsernameType.Secondary) {
        return if (!usernameExists && !usernameContestable) {
            UsernameSubmitButtonState.Enabled
        } else {
            UsernameSubmitButtonState.Disabled
        }
    }
    if (paymentSource == UsernamePaymentSource.SHIELDED_BALANCE &&
        (shieldedSyncStatus != ShieldedSyncStatus.READY || !fundingNoteAnchored)
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
 *
 * [usableUsernameActive] OVERRIDES all three: once the identity owns a
 * registered, immediately usable (instant / non-contested) name the wallet
 * IS ready to use, so the completion belongs on Home even though a
 * contested name is still in voting (Brian, 11.10.54: a dual creation of
 * contested "gffh" + instant "gffh-2" landed on More — "disorienting, the
 * wallet is ready"). The pending contested request keeps its own home tile
 * and its More-screen voting tile; it no longer hijacks the destination.
 * Route elsewhere only when there is nothing usable yet.
 */
fun usernameCompletionRoute(
    creationState: IdentityCreationState?,
    usernameContestable: Boolean,
    primaryUsernameContestable: Boolean = false,
    usableUsernameActive: Boolean = false
): UsernameCompletionRoute {
    if (usableUsernameActive) {
        return UsernameCompletionRoute.HOME
    }
    return if (creationState == IdentityCreationState.VOTING ||
        usernameContestable ||
        primaryUsernameContestable
    ) {
        UsernameCompletionRoute.MORE
    } else {
        UsernameCompletionRoute.HOME
    }
}

/**
 * Whether the identity already owns a REGISTERED, immediately usable
 * (instant / non-contested) username at completion time — the signal
 * [usernameCompletionRoute] routes Home on.
 *
 * The only such name a create-username flow produces alongside a contested
 * primary is the SECONDARY (instant) one, and
 * [CreateIdentityService][de.schildbach.wallet.ui.dashpay.CreateIdentityService]
 * registers it BEFORE the primary pass: the state machine only advances past
 * [IdentityCreationState.USERNAME_SECONDARY_REGISTERED] once the secondary
 * domain document is on chain, and a failed pass REWINDS below it. So the
 * name being present on the record AND the state having advanced past that
 * marker together prove the instant name is live and usable.
 *
 * [usernameSecondary] must come from the PERSISTED identity record, not the
 * screen's requested-name field: the state marker alone is meaningless for a
 * single-name creation (which skips the secondary pass entirely and still
 * advances past the marker).
 */
fun hasUsableUsername(
    creationState: IdentityCreationState?,
    usernameSecondary: String?
): Boolean {
    if (usernameSecondary.isNullOrEmpty()) {
        return false
    }
    val state = creationState ?: return false
    return state.ordinal >= IdentityCreationState.USERNAME_SECONDARY_REGISTERED.ordinal
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
    private val transparentUsernameCreation: SdkTransparentUsernameCreation,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val platformHealthProbe: PlatformHealthProbe,
    private val identityCreationStatus: IdentityCreationStatusHolder,
    private val assetLockFundingPreflight: SdkAssetLockFundingPreflight
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(RequestUserNameViewModel::class.java)
        private val CONTEST_DOCUMENT_FEE = Coin.valueOf(0, 20).value * 1000
        private val NON_CONTEST_DOCUMENT_FEE = Coin.valueOf(1000000).value * 1000

        /**
         * Re-read cadence for the asset-lock funding preflight WHILE the
         * "funds settling" explanation is showing. Settling clears when the
         * SDK mirror flips the funds final (IS lock / confirmation) — an
         * event with NO balance-amount change, so [WalletData.observeBalance]
         * never re-emits for it and the entry/balance triggers alone leave
         * the gate stale (observed on S21: a change output confirmed at
         * 10:38:41, the screen still showed settling at ~10:40 until
         * re-entry). Internal so the host test drives the poll on virtual
         * time. Only runs while `fundsSettling` is true, so the steady-state
         * cost is zero.
         */
        internal const val SETTLING_ELIGIBILITY_POLL_MS = 5_000L
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
     * gate contested names on the 0.25 funding denomination. Replaces the
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
                        // A balance change is when a note lands/anchors — re-read
                        // the funding-note anchor gate so the button clears
                        // PreparingShielded only once the note is confirmed.
                        refreshFundingNoteAnchor()
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
                        // READY alone no longer clears the gate: a sync pass can
                        // reach READY before a fresh note anchors, so re-key on
                        // the funding-note anchor read each pass (Part B).
                        refreshFundingNoteAnchor()
                    }
            }
        }
    }

    /**
     * A contested username funded from the shielded pool needs the 0.25
     * DASH exit denomination (0.25 contested fee → smallest covering
     * denomination in the v13 set), and the balance is only evidence once a
     * sync pass completed — mid-sync zeros are placeholders.
     */
    private fun canShieldedFundContestedUsername(): Boolean {
        if (_shieldedSyncStatus.value != ShieldedSyncStatus.READY) return false
        val requirement = shieldedIdentityFundingRequirement(Dash(Constants.DASH_PAY_FEE_CONTESTED.value))
            ?: return false
        return _shieldedBalance.value >= requirement
    }

    /**
     * The shielded funding denomination the anchor gate must cover for the
     * last-checked username — the 0.25 DASH exit denomination for a contested
     * name, 0.03 otherwise (Part B, v13 set). Defaults to the 0.03
     * non-contested requirement before any name has been checked.
     */
    private fun requiredShieldedDenomination(): Dash {
        val contestable = lastGateUsername?.let {
            runCatching { Names.isUsernameContestable(it) }.getOrDefault(false)
        } ?: false
        val fee = if (contestable) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
        return shieldedIdentityFundingRequirement(Dash(fee.value))
            ?: shieldedIdentityFundingRequirement(Dash(Constants.DASH_PAY_FEE.value))
            ?: Dash(Constants.DASH_PAY_FEE.value)
    }

    /**
     * Re-read the funding-note anchor gate (Part B) and mirror it into
     * [RequestUsernameUIState.fundingNoteAnchored]. Called on every shielded
     * balance/status emission — a fresh note anchors right around a sync pass,
     * and the button must not leave PreparingShielded until it does.
     */
    private suspend fun refreshFundingNoteAnchor() {
        val anchored = runCatching {
            shieldedBalanceService.isFundingNoteAnchoredForDenomination(requiredShieldedDenomination())
        }.onFailure { log.warn("funding-note anchor read failed", it) }
            .getOrDefault(false)
        _uiState.update {
            if (it.fundingNoteAnchored == anchored) it else it.copy(fundingNoteAnchored = anchored)
        }
    }

    private val _identityBalance = MutableStateFlow(0L)
    val identityBalance: StateFlow<Long>
        get() = _identityBalance

    private val _walletBalance = MutableStateFlow(Coin.ZERO)
    val walletBalance: StateFlow<Coin>
        get() = _walletBalance

    /**
     * PRE-FLIGHT funding-eligibility snapshot: what the SDK mirror can say
     * about the coins the asset-lock selection could fund an identity with
     * (see [SdkAssetLockFundingPreflight]), or null when the preflight does
     * not apply (pre-cutover / no evidence — fail OPEN, the display-balance
     * gate alone decides). Refreshed asynchronously on entry, on every
     * balance change, and on a modest poll while the settling row is showing
     * (finality flips with no balance-amount change — see
     * [SETTLING_ELIGIBILITY_POLL_MS]); [recomputeBalanceGate] re-resolves the
     * gate when it lands — the same async-gate-input pattern the shielded
     * sync status uses.
     *
     * Prevents the observed S22 failure mode: display balance ~0.994
     * (a non-final/out-of-account output) passed the old gate, then the
     * real build bounced "Insufficient funds: available 1449" after the
     * user had already picked a name and sat through the ~30s dialog.
     */
    private val _assetLockFundingEvidence = MutableStateFlow<AssetLockFundingEvidence?>(null)

    /** One refresh in flight at a time (entry + every balance emission would stack). */
    private var assetLockEligibilityRefreshing = false

    private fun refreshAssetLockFundingEligibility() {
        synchronized(this) {
            if (assetLockEligibilityRefreshing) return
            assetLockEligibilityRefreshing = true
        }
        viewModelScope.launch {
            try {
                // No explicit IO hop here: the preflight dispatches its own
                // DB read to Dispatchers.IO internally, and keeping this
                // call in the caller's context makes the refresh
                // deterministic under the host-JVM tests' unconfined main.
                val evidence = assetLockFundingPreflight.assetLockFundingEvidenceOrNull()
                if (_assetLockFundingEvidence.value != evidence) {
                    _assetLockFundingEvidence.value = evidence
                    recomputeBalanceGate()
                }
            } finally {
                synchronized(this@RequestUserNameViewModel) {
                    assetLockEligibilityRefreshing = false
                }
            }
        }
    }

    /**
     * Whether a fresh asset lock for [fee] would find enough ELIGIBLE
     * funds ([_assetLockFundingEvidence] + the shared fee headroom). True
     * when the preflight has no evidence (fail open) — the real build
     * stays the authority; only a PROVEN shortfall gates, which is why
     * this routes through [assetLockFundingVerdict] rather than comparing
     * the eligible sum itself (a mirror that has not yet classified the
     * wallet's coins reports 0, and 0 is not a shortfall).
     */
    private fun assetLockFundingEligible(fee: Coin): Boolean {
        val evidence = _assetLockFundingEvidence.value ?: return true
        return assetLockFundingVerdict(evidence, fee.value) ?: true
    }

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
        // A TERMINAL, non-retryable creation FAILURE (a double-claimed invite —
        // CreateIdentityService stamps "Invite has already been used") leaves the
        // creation state at an intermediate value, so the DONE-based check above
        // never fires and the ~30s processing dialog stayed up until a manual
        // dismiss (Brian: "the dialog persists on failure, I have to dismiss it to
        // see the home screen result"). Clear submitting on this terminal failure
        // too, so the dialog auto-dismisses and the flow finishes to home where
        // InviteHandler surfaces the already-claimed result. Deliberately scoped to
        // the non-retryable already-used string: transient errors are auto-retried
        // by the service, so leaving them keeps the dialog's "still working"
        // watchdog line (closing on the first retry stamp was a prior live bug).
        identityConfig.observe(CREATION_STATE_ERROR_MESSAGE)
            .onEach { error ->
                val terminalFailure =
                    error?.contains("Invite has already been used", ignoreCase = true) == true
                if (terminalFailure && _uiState.value.usernameRequestSubmitting) {
                    log.info("identity creation hit a terminal failure — clearing the processing dialog")
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
                // A balance change is when funds land/settle — re-read the
                // asset-lock funding eligibility so the settling gate clears
                // the moment the funds become final.
                refreshAssetLockFundingEligibility()
            }
            .launchIn(viewModelScope)
        // Entry kick: the balance flow may not emit until a change, but the
        // eligibility snapshot must exist before the first gate compute.
        refreshAssetLockFundingEligibility()
        // While the settling row is SHOWING, the event that clears it (the
        // SDK mirror flipping the funds final) changes no balance amount, so
        // neither trigger above ever re-fires — poll the preflight at a
        // modest cadence until the gate resolves (see
        // [SETTLING_ELIGIBILITY_POLL_MS]). `flatMapLatest` cancels the loop
        // the moment `fundsSettling` drops, and `refreshAssetLockFunding-
        // Eligibility` only recomputes the gate on a CHANGED snapshot, so an
        // unchanged poll tick is a single no-op DB read.
        _uiState.map { it.fundsSettling }
            .distinctUntilChanged()
            .flatMapLatest { settling ->
                if (!settling) {
                    emptyFlow()
                } else {
                    flow {
                        while (true) {
                            delay(SETTLING_ELIGIBILITY_POLL_MS)
                            emit(Unit)
                        }
                    }
                }
            }
            .onEach { refreshAssetLockFundingEligibility() }
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
                applyUsernameSubmitState(state, shieldedUsernameCreation::acknowledge)
            }
        }
        // Mirror the app-scoped TRANSPARENT (post-cutover, non-shielded)
        // username creation into uiState exactly the same way. Only one of
        // the two executors is ever driven per submission (the routing in
        // submit() picks shielded XOR transparent XOR dashj), so the inactive
        // one stays Idle and its collector is a no-op.
        viewModelScope.launch {
            transparentUsernameCreation.submitState.collect { state ->
                applyUsernameSubmitState(state, transparentUsernameCreation::acknowledge)
            }
        }
    }

    /**
     * Shared uiState mapping for a [ShieldedUsernameSubmitState] emitted by
     * EITHER the shielded or the transparent app-scoped username-creation
     * executor (both use the same three-valued state machine). [acknowledge]
     * resets the emitting executor once the terminal state is surfaced — a
     * no-op for the sticky funds-critical MayHaveGoneThrough.
     */
    private fun applyUsernameSubmitState(
        state: ShieldedUsernameSubmitState,
        acknowledge: () -> Unit
    ) {
        when (state) {
            ShieldedUsernameSubmitState.Idle -> Unit
            ShieldedUsernameSubmitState.Proving ->
                _uiState.update { it.copy(usernameRequestSubmitting = true) }
            is ShieldedUsernameSubmitState.Created -> {
                log.info("username creation completed: {}", state.outcome.nameStatus)
                _uiState.update {
                    it.copy(usernameRequestSubmitting = false, usernameRequestSubmitted = true)
                }
                // Result surfaced; the legacy handoff was enqueued by the
                // service — the blockchainIdentity observers take over.
                acknowledge()
            }
            is ShieldedUsernameSubmitState.NotSent -> {
                log.warn("username creation not sent: {}", state.reason)
                // The pool-not-ready refusals ("still syncing" / "runtime not
                // ready") are transient, not errors — surface the calm "still
                // preparing" message rather than the red "network error"
                // dialog (Fix A). (Transparent funding never emits these.)
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
                // Provably nothing spent — retry-safe; reset so a retry can
                // re-submit.
                acknowledge()
            }
            ShieldedUsernameSubmitState.MayHaveGoneThrough -> {
                // Distinct from usernameSubmittedError: the generic error
                // dialog says "try again at no extra cost" and offers a retry
                // — both wrong here (observed live: an ambiguous creation
                // surfaced the retry dialog).
                _uiState.update {
                    it.copy(usernameRequestSubmitting = false, usernameSubmittedAmbiguous = true)
                }
                // Deliberately NOT acknowledged: the state stays sticky and
                // submit() keeps refusing — an ambiguous outcome must never be
                // retried (funds safety).
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
            // Post-cutover the dashj L1 engine is HELD (0 UTXOs), so the
            // dashj asset-lock funding CreateIdentityService drives fails
            // InsufficientMoneyException — the funds live in the SDK. Route
            // transparent (Dash-balance) REGISTRATION funding through the SDK
            // once the cutover is committed. Invite and reuse-transaction
            // submissions keep the dashj path (their funding is already
            // committed elsewhere / has no transparent SDK API); pre-cutover
            // keeps dashj byte-for-byte.
            val cutoverCommitted = !isUsingInvite() && !reuseTransaction &&
                paymentSource != UsernamePaymentSource.SHIELDED_BALANCE &&
                transparentUsernameCreation.isCutoverCommitted()
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
                if (accepted) {
                    // Same as the transparent path: the funding runs on the
                    // app-scoped executor, but the app could still go cached on
                    // a screen lock and be reaped mid-proof. Hold the process
                    // foreground for the duration and drive the home identity
                    // tile via CreateIdentityService's lightweight SDK-hold
                    // action (it does NO funding). The shielded=true flag makes
                    // the hold observe the shielded executor's submit state.
                    // Started here, from the tap path with the activity visible,
                    // to satisfy the Android 12+ FGS-start rules.
                    walletApplication.startService(
                        CreateIdentityService.createIntentForSdkForegroundHold(
                            walletApplication,
                            requestedUserName!!,
                            requestedUsernameSecondary,
                            shielded = true
                        )
                    )
                } else {
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
            } else if (cutoverCommitted) {
                // Committed cutover, Dash-balance funding: the identity's
                // asset lock is built from the transparent UTXOs the SDK now
                // holds (register / resume), NOT dashj. Same app-scoped
                // single-flight + no-double-broadcast contract as the
                // shielded path.
                log.info("routing username creation to the transparent-funded SDK path (cutover committed)")
                val accepted = transparentUsernameCreation.submit(requestedUserName!!, requestedUsernameSecondary)
                if (accepted) {
                    // The funding runs on the app-scoped executor (survives the
                    // screen), but the app would still go cached on a screen
                    // lock and could be reaped mid-proof. Hold the process
                    // foreground for the duration of the creation and drive the
                    // home identity tile via CreateIdentityService's lightweight
                    // SDK-hold action (it does NO funding). Started here, from
                    // the tap path with the activity visible, to satisfy the
                    // Android 12+ FGS-start rules.
                    walletApplication.startService(
                        CreateIdentityService.createIntentForSdkForegroundHold(
                            walletApplication,
                            requestedUserName!!,
                            requestedUsernameSecondary
                        )
                    )
                } else {
                    val state = transparentUsernameCreation.submitState.value
                    log.info("username submit rejected: transparent creation refused (executor state: {})", state)
                    _uiState.update {
                        when (state) {
                            ShieldedUsernameSubmitState.Proving ->
                                it.copy(usernameRequestSubmitting = true)
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

    /** The resolved balance gate: button enablement + row copy inputs. */
    private data class BalanceGateResult(
        val enoughBalance: Boolean,
        val requiredAmount: String,
        /** See [RequestUserNameUIState.fundsSettling]. */
        val fundsSettling: Boolean
    )

    /**
     * `enoughBalance` + the DASH amount the insufficient-balance row must
     * name, resolved for the chosen payment source. Every failed gate is
     * logged at info level — a submit attempt that bounces must always
     * leave a forensic trail (Brian: the contested attempts left ZERO log
     * lines).
     *
     * The `identityBalance == 0` (fresh identity, Dash-balance) arms gate
     * on BOTH the display balance AND the asset-lock funding PREFLIGHT
     * ([assetLockFundingEligible]): post-cutover the identity is funded by
     * an SDK asset lock whose coin selection only accepts FINAL
     * (confirmed/IS-locked) BIP44 coins — a display balance backed by
     * non-final or out-of-account outputs must not let the user start a
     * creation the funding cannot complete (the S22 repro). When the
     * display balance covers the fee but the eligibility does not, the
     * result carries `fundsSettling` so the row explains WHY.
     */
    private fun computeBalanceGate(username: String, contestable: Boolean): BalanceGateResult {
        val identityBalance = _identityBalance.value
        val walletBalance = _walletBalance.value
        val inviteBalance = _inviteBalance.value
        val enoughBalance = when {
            // Shielded (L2) invites have no L1 inviteBalance to check — the note is
            // verified/spent at claim time (createIdentityFromInvitation), so there is
            // no balance to compare and the gate must not block on a ZERO inviteBalance.
            // What it CAN enforce is the invite's tier, which is the same value the
            // screen's "only a non-contested username" notice is drawn from: those two
            // used to disagree (this arm returned an unconditional `true`, so the button
            // stayed enabled for a contested name under a notice saying it was not
            // allowed). An UNKNOWN tier still passes — see InviteUsernameTier.UNKNOWN.
            isUsingInvite() && createUsernameArgs?.invite?.isShielded == true ->
                inviteTierAllowsUsername(inviteTier(), contestable)
            isUsingInvite() && contestable -> inviteBalance >= Constants.DASH_PAY_FEE_CONTESTED
            isUsingInvite() && !contestable -> inviteBalance >= Constants.DASH_PAY_FEE
            // Paying from the shielded pool: the welcome-screen decision
            // point only offers this source when the pool covers the
            // non-contested 0.03 denomination, and the shielded service
            // re-verifies balance/denomination in its preflight — the L1
            // wallet balance below is irrelevant to these submissions.
            // Contested names need the larger 0.25 denomination, so they
            // get their own pool-balance gate here.
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE && !contestable -> true
            paymentSource == UsernamePaymentSource.SHIELDED_BALANCE && contestable ->
                canShieldedFundContestedUsername()
            identityBalance > 0L && contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(
                CONTEST_DOCUMENT_FEE / 1000)
            identityBalance > 0L && !contestable -> (Coin.valueOf(identityBalance / 1000) + walletBalance) > Coin.valueOf(
                NON_CONTEST_DOCUMENT_FEE / 1000)
            identityBalance == 0L && contestable ->
                walletBalance >= Constants.DASH_PAY_FEE_CONTESTED &&
                    assetLockFundingEligible(Constants.DASH_PAY_FEE_CONTESTED)
            identityBalance == 0L && !contestable ->
                walletBalance >= Constants.DASH_PAY_FEE &&
                    assetLockFundingEligible(Constants.DASH_PAY_FEE)
            else -> false // how can we get here?
        }
        // "Settling": the display balance covers the fee but the asset-lock
        // preflight says the SELECTABLE (final, BIP44) funds do not — only
        // meaningful on the fresh-identity Dash-balance path (the arms above
        // that consult the preflight).
        val fundsSettling = !isUsingInvite() &&
            paymentSource != UsernamePaymentSource.SHIELDED_BALANCE &&
            identityBalance == 0L &&
            run {
                val fee = if (contestable) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
                walletBalance >= fee && !assetLockFundingEligible(fee)
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
                    "(walletBalance={} identityCredits={} inviteBalance={} shieldedBalance={} " +
                    "shieldedSync={} assetLockEligibleDuffs={} assetLockUnclassifiedDuffs={} " +
                    "settling={})",
                username,
                paymentSource,
                contestable,
                requiredAmount,
                walletBalance.toPlainString(),
                identityBalance,
                inviteBalance.toPlainString(),
                _shieldedBalance.value.toPlainString(),
                _shieldedSyncStatus.value,
                _assetLockFundingEvidence.value?.eligibleDuffs,
                _assetLockFundingEvidence.value?.unclassifiedDuffs,
                fundsSettling
            )
        }
        return BalanceGateResult(enoughBalance, requiredAmount, fundsSettling)
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
        val gate = computeBalanceGate(username, contestable)
        _uiState.update {
            if (it.enoughBalance == gate.enoughBalance &&
                it.requiredAmount == gate.requiredAmount &&
                it.fundsSettling == gate.fundsSettling
            ) {
                it
            } else {
                it.copy(
                    enoughBalance = gate.enoughBalance,
                    requiredAmount = gate.requiredAmount,
                    fundsSettling = gate.fundsSettling
                )
            }
        }
    }

    fun checkUsernameValid(username: String, usernameType: UsernameType): Boolean {
        val validLength = validateUsernameSize(username, usernameType)
        val (validCharacters, startOrEndWithHyphen) = validateUsernameCharacters(username)
        val contestable = Names.isUsernameContestable(username)

        lastGateUsername = username
        val gate = computeBalanceGate(username, contestable)
        _uiState.update {
            it.copy(
                usernameLengthValid = validLength,
                usernameCharactersValid = validCharacters && !startOrEndWithHyphen,
                usernameContestable = contestable,
                enoughBalance = gate.enoughBalance,
                requiredAmount = gate.requiredAmount,
                fundsSettling = gate.fundsSettling,
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
        // Shielded (L2) invites carry a one-time Orchard key, not an L1 asset lock,
        // so there is no asset-lock tx to fetch — the L1 accessors would throw. Leave
        // inviteAssetLockTx null (invite balance ZERO); the shielded funding is verified
        // and spent at claim time. computeBalanceGate recognizes shielded separately.
        if (isUsingInvite() && createUsernameArgs?.invite?.isShielded != true) {
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

    /**
     * The claim screen's SINGLE source of truth for what this invitation
     * pays for. Everything on the screen — the notice, the requirement rows
     * and the submit gate — must be derived from this one value.
     *
     * Reads the L1 asset-lock amount for a standard invite and the note
     * value carried by the link for a shielded one. It is deliberately
     * [InviteUsernameTier.UNKNOWN] rather than NON_CONTESTED for a shielded
     * invite whose link has no amount: [getInvitationAmount] only ever
     * resolves an L1 asset lock, so shielded invites used to fall through it
     * as Coin.ZERO and every one of them was reported as non-contested —
     * including invites whose creator had paid the contested fee.
     */
    fun inviteTier(): InviteUsernameTier =
        inviteUsernameTier(createUsernameArgs?.invite, getInvitationAmount())

    fun isInviteForContestedNames(): Boolean = inviteTier() == InviteUsernameTier.CONTESTED

    suspend fun hasSecondaryName(): Boolean {
        return identityConfig.get(IDENTITY_ID) != null &&
                identityConfig.get(BlockchainIdentityConfig.USERNAME_SECONDARY) != null &&
                identityConfig.get(BlockchainIdentityConfig.USERNAME_SECONDARY_REGISTRATION_STATUS)?.let {
                    UsernameStatus.valueOf(it) == UsernameStatus.CONFIRMED
                } == true
    }
}
