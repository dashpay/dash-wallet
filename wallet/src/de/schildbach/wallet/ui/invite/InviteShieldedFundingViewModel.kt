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
package de.schildbach.wallet.ui.invite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.SHIELDED_INVITE_FEE_MARGIN_CREDITS
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.service.platform.sdk.creditsToDash
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.money.Dash
import org.slf4j.LoggerFactory
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

/**
 * Which sheet the create-invitation flow shows before the fee/confirm step —
 * the shielded-funding decision point, mirroring the create-username flow's
 * "Make your username private" arm (Figma flow 555:811, sheet 1856:1519;
 * invite variant 25163:53221):
 *
 * - [NONE]: shielded balances are unavailable (flag off / platform
 *   unsupported) — continue straight to the fee dialog, exactly the
 *   pre-design behavior. Never blocks the flow.
 * - [MAKE_INVITE_PRIVATE]: shielded is available — inform the user their
 *   funds can be shielded, show the shielded contested/non-contested cost,
 *   and offer "Shield your funds first" or "Continue without privacy".
 *
 * Unlike the username flow there is NO shielded-funded invite creation path
 * (an invitation is always an L1 asset lock), so the SELECT_PAYMENT_OPTION
 * arm does not apply here — the only decision is shield-first vs continue.
 */
enum class InviteShieldedFundingPrompt { NONE, MAKE_INVITE_PRIVATE }

/**
 * The action buttons the shielded-funding decision sheet offers, in display
 * order. Derived purely from [InviteShieldedFundingUIState] (balance/sync),
 * so the option set is host-JVM unit-testable:
 *
 * - [CONTINUE_WITHOUT_PRIVACY]: always present — the unchanged L1 asset-lock
 *   invite path.
 * - [CREATE_PRIVATE_INVITE]: present when the shielded pool already holds
 *   enough to fund an invitation directly (L2) — routes to the new shielded
 *   inviter path. When present it REPLACES [SHIELD_FIRST] (there is nothing to
 *   shield first).
 * - [SHIELD_FIRST]: present only when the pool CANNOT yet fund an invite but
 *   the L1 wallet holds enough to shield first.
 */
enum class InviteShieldedOption { CREATE_PRIVATE_INVITE, SHIELD_FIRST, CONTINUE_WITHOUT_PRIVACY }

/**
 * Pure decision for the sheet's option set (extracted from the `Constants`-
 * referencing [InviteShieldedFundingUIState] getters so it is host-JVM
 * unit-testable): given whether the shielded pool can already fund an invite
 * ([canCreatePrivateInvite]) and whether the L1 wallet holds the shield-first
 * minimum ([canShieldMinimum]), pick the buttons in display order. "Continue
 * without privacy" (L1) is always offered; the private-invite path replaces
 * shield-first when the pool can fund; shield-first shows only when it cannot.
 */
internal fun inviteShieldedOptions(
    canCreatePrivateInvite: Boolean,
    canShieldMinimum: Boolean
): List<InviteShieldedOption> = buildList {
    when {
        canCreatePrivateInvite -> add(InviteShieldedOption.CREATE_PRIVATE_INVITE)
        canShieldMinimum -> add(InviteShieldedOption.SHIELD_FIRST)
    }
    add(InviteShieldedOption.CONTINUE_WITHOUT_PRIVACY)
}

/**
 * UI state for the shielded-funding decision step of the create-invitation
 * flow.
 *
 * The shielded contested/non-contested "amount to shield" figures come
 * from the same fund-minimum constants the create-username flow shows
 * ([Constants.SHIELDED_USERNAME_FUND_MIN] 0.035 DASH /
 * [Constants.SHIELDED_USERNAME_FUND_MIN_CONTESTED] 0.26 DASH) — the 0.03 /
 * 0.25 v13 Type-20 exit denomination plus [Constants.SHIELDED_FEE_MARGIN]
 * for the Shield operation's own fee.
 *
 * The balance/sync pair follows the More-screen balance-card rule: a
 * shielded balance is only trusted when the sync status is
 * [ShieldedSyncStatus.READY] — `Dash.ZERO` mid-sync is a placeholder, not
 * evidence of an empty pool.
 */
data class InviteShieldedFundingUIState(
    /** `USE_KOTLIN_SDK_SHIELDED` on (callers additionally gate on `Constants.SUPPORTS_PLATFORM`). */
    val shieldedEnabled: Boolean = false,
    val syncStatus: ShieldedSyncStatus = ShieldedSyncStatus.NOT_READY,
    val shieldedBalance: Dash = Dash.ZERO,
    /** The L1 wallet balance — what "Shield your funds first" would shield FROM. */
    val walletBalance: Dash = Dash.ZERO,
    /** L1 cost of a non-contested invitation (`DASH_PAY_FEE`). */
    val nonContestedFee: Dash = Dash.ZERO,
    /** L1 cost of a contested invitation (`DASH_PAY_FEE_CONTESTED`). */
    val contestedFee: Dash = Dash.ZERO,
    /** True once the resolving `shieldedEnabled` read has completed (see [prompt]). */
    val resolved: Boolean = false
) {
    /**
     * The shielded balance a NON-contested invitation requires the user to
     * hold: [Constants.SHIELDED_USERNAME_FUND_MIN] (0.035 DASH) — the 0.03
     * v13 Type-20 exit denomination plus [Constants.SHIELDED_FEE_MARGIN] for
     * the Shield operation's own fee. This is the "amount to shield" the
     * sheet asks for, NOT the bare exit denomination (0.03), which is only
     * what finally leaves the pool.
     */
    val nonContestedShieldedCost: Dash = Dash(Constants.SHIELDED_USERNAME_FUND_MIN.value)

    /** The shielded balance a CONTESTED invitation requires (0.253 DASH — 0.25 + the fee margin). */
    val contestedShieldedCost: Dash = Dash(Constants.SHIELDED_USERNAME_FUND_MIN_CONTESTED.value)

    /**
     * The Private (shielded) amount actually WITHDRAWN — the Type-20 exit
     * denomination that leaves the pool (0.03 non-contested / 0.25 contested,
     * the v13 mint mapping `shieldedInviteDenominationCredits`), i.e. what the
     * user "pays". The decision sheet's cost-comparison table shows this
     * against the Standard [nonContestedFee]/[contestedFee] (Fix G1),
     * consistent with the fee/confirm screens; distinct from the 0.08/0.30
     * pool minimum the wallet must HOLD ([nonContestedShieldedCost] /
     * [contestedShieldedCost]). Under v13 the Private withdrawn amounts EQUAL
     * the Standard L1 fees — the table stays (the comparison is the point),
     * the numbers now match. Pinned to the mint by
     * `InviteShieldedFundingUIStateTest`.
     */
    val nonContestedPrivateWithdrawn: Dash = Dash(3_000_000L) // 0.03 DASH

    /** The Private CONTESTED withdrawn amount (0.25 DASH). */
    val contestedPrivateWithdrawn: Dash = Dash(25_000_000L) // 0.25 DASH

    /**
     * The pool balance that actually lets a private invite be MINTED — the
     * non-contested withdrawn denomination PLUS the Type-16 transfer-fee
     * margin ([SHIELDED_INVITE_FEE_MARGIN_CREDITS]): the mint's consensus
     * fee is carved from the pool on top of the funded notes, so a pool
     * holding exactly the denomination cannot mint (it would pass a bare
     * check and then fail opaquely at the FFI's note selection). Same bar as
     * the downstream fee gate ([inviteFeeRequirement]/`inviteFeeGate`).
     */
    val nonContestedPrivateInviteGate: Dash =
        Dash(nonContestedPrivateWithdrawn.duffs + creditsToDash(SHIELDED_INVITE_FEE_MARGIN_CREDITS).duffs)

    /**
     * The sheet to show at the invite decision point. Until the flag read
     * resolves the prompt is [InviteShieldedFundingPrompt.NONE] so an
     * undecided state never renders a sheet — the fragment waits for
     * [resolved] before it forwards a NONE straight to the fee dialog.
     */
    val prompt: InviteShieldedFundingPrompt
        get() = when {
            !shieldedEnabled -> InviteShieldedFundingPrompt.NONE
            else -> InviteShieldedFundingPrompt.MAKE_INVITE_PRIVATE
        }

    /**
     * "Shield your funds first" is only useful when the wallet holds at
     * least the SHIELD-guidance amount ([Constants.SHIELDED_USERNAME_FUND_MIN],
     * 0.035 DASH — round guidance above the 0.03 pool denomination plus the Shield fee
     * fee margin) — below it the sheet disables the button and offers
     * only "Continue without privacy".
     */
    val canShieldMinimum: Boolean
        get() = walletBalance >= Dash(Constants.SHIELDED_USERNAME_FUND_MIN.value)

    /**
     * Whether the shielded pool already holds enough to fund an invitation
     * directly (the L2 "Create a private invitation" path). Requires the
     * shielded features on, the pool [ShieldedSyncStatus.READY] (a mid-sync
     * `Dash.ZERO` is a placeholder, never evidence), and a trusted balance of
     * at least [nonContestedPrivateInviteGate] — the NON-contested withdrawn
     * denomination plus the Type-16 transfer-fee margin the mint carves from
     * the pool on top of the notes. This is the SAME bar the downstream fee
     * gate checks ([de.schildbach.wallet.ui.invite.inviteFeeRequirement] /
     * `inviteFeeGate`); gating entry on the shield-IN fund-minimum (which
     * bakes in the L1 Shield fee that is irrelevant once funds are already
     * shielded) wrongly hid the private-invite option. The contested choice
     * is re-checked when the inviter picks the username kind at the fee
     * step; here we only gate whether ANY private invite is offerable.
     */
    val canCreatePrivateInvite: Boolean
        get() = shieldedEnabled &&
            syncStatus == ShieldedSyncStatus.READY &&
            shieldedBalance >= nonContestedPrivateInviteGate

    /**
     * The action buttons the sheet renders, in display order. See
     * [InviteShieldedOption]: "Continue without privacy" (L1) is always last;
     * "Create a private invitation" (L2) is offered when the pool can already
     * fund an invite and then REPLACES "Shield your funds first", which only
     * shows when the pool cannot yet fund but the L1 wallet can shield.
     */
    val options: List<InviteShieldedOption>
        get() = inviteShieldedOptions(canCreatePrivateInvite, canShieldMinimum)

    /**
     * True while the shielded private-invite decision is still resolving —
     * the flag read has completed ([shieldedEnabled] is true) but the pool
     * has not yet reached [ShieldedSyncStatus.READY], so neither
     * [canCreatePrivateInvite] nor [canShieldMinimum] can be TRUSTED yet
     * (a mid-sync balance is a placeholder). While this is true the sheet
     * shows a single neutral "Preparing shielded balance…" primary instead
     * of first rendering "Shield your funds first" and then flipping to
     * "Create a private invitation" once the pool reaches READY (Fix B — the
     * button-label flicker). The pool always reaches READY even when empty,
     * so this resolves without hanging.
     */
    val privateDecisionLoading: Boolean
        get() = shieldedEnabled && syncStatus != ShieldedSyncStatus.READY
}

/**
 * Balance/requirement source for the create-invitation shielded-funding
 * decision sheet (Figma 25163:53221), mirroring
 * [de.schildbach.wallet.ui.username.request.UsernamePaymentViewModel].
 *
 * Degrades gracefully: with `USE_KOTLIN_SDK_SHIELDED` off the state stays
 * at its inert defaults ([InviteShieldedFundingUIState.prompt] == NONE), the
 * [ShieldedBalanceService] is never brought up, and nothing can throw out
 * of the init path.
 */
@HiltViewModel
class InviteShieldedFundingViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val walletData: org.dash.wallet.common.WalletDataProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(InviteShieldedFundingViewModel::class.java)
    }

    private val _uiState = MutableStateFlow(
        InviteShieldedFundingUIState(
            nonContestedFee = Dash(Constants.DASH_PAY_FEE.value),
            contestedFee = Dash(Constants.DASH_PAY_FEE_CONTESTED.value)
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val enabled = runCatching {
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
            }.getOrDefault(false)
            _uiState.update { it.copy(shieldedEnabled = enabled, resolved = true) }

            if (!enabled) {
                return@launch
            }

            // Bring the runtime up so the balance loads and the status can
            // advance past NOT_READY (idempotent + single-flight). Never
            // crashes the flow.
            launch {
                runCatching { shieldedBalanceService.ensureShieldedReady() }
                    .onFailure { log.warn("shielded bring-up failed", it) }
            }
            launch {
                shieldedBalanceService.observeShieldedBalance()
                    .catch { log.warn("shielded balance flow failed", it) }
                    .collect { balance -> _uiState.update { it.copy(shieldedBalance = balance) } }
            }
            launch {
                shieldedBalanceService.shieldedSyncStatus
                    .catch { log.warn("shielded status flow failed", it) }
                    .collect { status -> _uiState.update { it.copy(syncStatus = status) } }
            }
            launch {
                walletData.observeTotalBalance()
                    .catch { log.warn("wallet balance flow failed", it) }
                    .collect { balance ->
                        _uiState.update { it.copy(walletBalance = balance) }
                    }
            }
        }
    }
}
