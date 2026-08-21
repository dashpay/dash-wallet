/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.ui.more

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.ui.invite.InvitationFragmentViewModel
import de.schildbach.wallet.ui.invite.InviteCreationFailureKind
import de.schildbach.wallet.ui.invite.classifyInviteCreationFailure
import de.schildbach.wallet.ui.invite.inviteRetryAllowed
import de.schildbach.wallet.ui.more.tools.ConfirmTopupDialogViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogConfirmTopupBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class ConfirmInviteDialogFragment: OffsetDialogFragment(R.layout.dialog_confirm_topup) {

    @javax.inject.Inject
    lateinit var authManager: org.dash.wallet.common.services.AuthenticationManager
    companion object {
        private val log = LoggerFactory.getLogger(ConfirmInviteDialogFragment::class.java)
    }
    private val binding by viewBinding(DialogConfirmTopupBinding::bind)

    private val viewModel by viewModels<ConfirmTopupDialogViewModel>()
    // activity-scoped so the shielded invite link this dialog publishes is
    // observed by InviteCreatedFragment (same shared VM), not lost to a fresh
    // fragment-scoped instance (which left the created screen on "Loading
    // Invite…" forever for shielded invites).
    private val invitationFragmentViewModel by activityViewModels<InvitationFragmentViewModel>()
    private val args by navArgs<ConfirmInviteDialogFragmentArgs>()

    /**
     * CREATE attempts that have failed in this dialog (a cancelled
     * authentication is deliberately NOT one — the user may re-try auth
     * freely). Feeds [inviteRetryAllowed] so the confirm → authorize →
     * create cycle always terminates in a clear error instead of the
     * observed unbounded re-prompt (a contested invite deterministically
     * bounced by the SDK's invitation-amount cap re-ran the full
     * confirm/authorize cycle on every tap, failing identically each time).
     */
    private var failedCreateAttempts = 0

    /**
     * Latched once [inviteRetryAllowed] says no more attempts may run —
     * either a failure retrying can never fix (deterministic rejection, or
     * an ambiguous outcome that must not be double-broadcast) or the
     * transient-failure attempt budget is spent. [setCreatingUi] keeps the
     * confirm button disabled from then on; only dismiss remains.
     */
    private var retryBlocked = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.amount = Coin.valueOf(args.amount)
        binding.confirmBtn.setOnClickListener {
            // Show the in-flight indicator SYNCHRONOUSLY on the tap so there is
            // no feedback gap before the ~30s creation spend starts (Fix C).
            // The ViewModel's inviteCreationInFlight flow keeps it shown for the
            // duration of the actual SDK spend; every non-navigating exit below
            // clears it.
            setCreatingUi(true)
            lifecycleScope.launch {
                try {
                    val inviteAmount = Coin.valueOf(args.amount)
                    // The L1 spendable-balance pre-check only applies to a
                    // standard (asset-lock) invite — a SHIELDED invite is
                    // funded from the pool, whose affordability was already
                    // gated at the fee dialog, so the (now-low) L1 balance must
                    // not falsely block it here (Fix G3).
                    if (!args.shielded) {
                        val spendableBalance = invitationFragmentViewModel.walletData.observeTotalBalance().first()
                        if (spendableBalance < inviteAmount) {
                            binding.confirmMessage.text = getString(
                                R.string.invitation_cant_afford_message,
                                inviteAmount.toFriendlyString()
                            )
                            binding.confirmMessage.isVisible = true
                            setCreatingUi(false)
                            return@launch
                        }
                        // PRE-FLIGHT funding eligibility (post-cutover SDK
                        // path only): the display balance covers the amount,
                        // but the asset-lock build can only select FINAL
                        // (confirmed/IS-locked) BIP44 coins — refuse HERE,
                        // before auth and the ~30s creation spend, when the
                        // selectable funds cannot cover it (fail-open on any
                        // preflight hiccup; the real build stays authoritative).
                        if (invitationFragmentViewModel.shouldRouteL1ToSdk() &&
                            !invitationFragmentViewModel.canFundL1Invite(inviteAmount.value)
                        ) {
                            binding.confirmMessage.text = getString(
                                R.string.invitation_funds_settling_message,
                                inviteAmount.toFriendlyString()
                            )
                            binding.confirmMessage.isVisible = true
                            setCreatingUi(false)
                            return@launch
                        }
                    }
                    // Authenticate right before the spend — the amount has been
                    // confirmed on this screen (standard order; the fee screen no
                    // longer prompts). A cancelled prompt spends nothing.
                    if (authManager.authenticate(requireActivity()) == null) {
                        setCreatingUi(false)
                        return@launch
                    }
                    // invitationFragmentViewModel.logEvent(AnalyticsConstants.UsersContacts.TOPUP_CONFIRM)
                    val identityId = if (args.shielded) {
                        // SHIELDED (L2) invite: fund a note directly from the
                        // shielded pool. Contested-ness follows the fee the
                        // inviter picked (0.25 → contested → the 0.25 v13
                        // denomination).
                        val contested = inviteAmount.value >=
                            de.schildbach.wallet.Constants.DASH_PAY_FEE_CONTESTED.value
                        when (val result = invitationFragmentViewModel.createShieldedInvite(contested)) {
                            is SdkWriteResult.Broadcast -> result.value.user
                            else -> {
                                onCreateFailed(classifyInviteCreationFailure(result), inviteAmount)
                                return@launch
                            }
                        }
                    } else if (invitationFragmentViewModel.shouldRouteL1ToSdk()) {
                        // STANDARD (L1) invite, post-cutover: fund the DIP-13
                        // voucher through the Kotlin SDK (the transparent Core
                        // UTXOs the SDK now holds) instead of the dashj
                        // asset-lock worker. Contested-ness follows the fee the
                        // inviter picked (0.25 → contested), same rule as the
                        // shielded branch above.
                        val contested = inviteAmount.value >=
                            de.schildbach.wallet.Constants.DASH_PAY_FEE_CONTESTED.value
                        when (val result = invitationFragmentViewModel.createL1Invite(contested)) {
                            is SdkWriteResult.Broadcast -> result.value.user
                            else -> {
                                onCreateFailed(classifyInviteCreationFailure(result), inviteAmount)
                                return@launch
                            }
                        }
                    } else {
                        invitationFragmentViewModel.sendInviteTransaction(inviteAmount)
                    }
                    // The spend deliberately outlives the view (fragment
                    // lifecycleScope — cancelling a ~30s funding spend on a
                    // relock/teardown would orphan it mid-flight), so the view
                    // can be gone by the time the SDK returns. Navigating off a
                    // destroyed view throws; the invite already exists and the
                    // Invitations list shows it on next open, so just skip.
                    if (view == null) {
                        log.warn("invite created but the dialog's view is destroyed — skipping navigation")
                        return@launch
                    }
                    findNavController().navigate(
                        ConfirmInviteDialogFragmentDirections.toInviteCreatedFragment(identityId, args.source)
                    )
                } catch (e: Exception) {
                    log.info("error sending transaction:", e)
                    // No SdkWriteResult to classify (dashj path, or a failure
                    // outside the create call) — treat as transient, bounded.
                    onCreateFailed(InviteCreationFailureKind.UNREACHABLE, Coin.valueOf(args.amount))
                }
            }
        }
        binding.dismissBtn.setOnClickListener { dismiss() }
        binding.confirmMessage.isVisible = false
        // Keep the in-flight indicator in sync with the actual SDK spend — the
        // synchronous set on tap covers the pre-auth gap; this covers the ~30s
        // proof/funding duration and its clearing in the ViewModel's finally.
        invitationFragmentViewModel.inviteCreationInFlight.observe(viewLifecycleOwner) { inFlight ->
            setCreatingUi(inFlight)
        }
        viewModel.uiState.observe(viewLifecycleOwner) {
            binding.dashAmountView.text = it.amountStr
            binding.fiatSymbolView.text = it.fiatSymbol
            binding.fiatAmountView.text = it.fiatAmountStr
        }
    }

    /**
     * One failed CREATE attempt: count it, pick the classified, actionable
     * message ([InviteCreationFailureKind] — an insufficient-funds failure, a
     * deterministic rejection, a possibly-landed spend, and a transient
     * network failure each tell the user something different to DO), and
     * latch [retryBlocked] once [inviteRetryAllowed] says the cycle is over —
     * so the dialog can never become the observed unbounded
     * confirm/authorize loop.
     */
    private fun onCreateFailed(kind: InviteCreationFailureKind, inviteAmount: Coin) {
        failedCreateAttempts++
        val retryAllowed = inviteRetryAllowed(kind, failedCreateAttempts)
        log.warn(
            "invite creation failed: kind={} attempt={} retryAllowed={}",
            kind, failedCreateAttempts, retryAllowed
        )
        // The creating coroutine outlives the view (fragment lifecycleScope, see
        // onViewCreated) — a failure landing after a relock/teardown must not
        // touch the binding delegate (getView() == null throws). The attempt was
        // counted and logged above; a re-opened dialog starts from honest state.
        if (view == null) {
            log.warn("invite-creation failure arrived after the dialog's view was destroyed — UI update skipped")
            return
        }
        binding.confirmMessage.text = when (kind) {
            InviteCreationFailureKind.INSUFFICIENT_FUNDS ->
                getString(R.string.invitation_cant_afford_message, inviteAmount.toFriendlyString())
            InviteCreationFailureKind.REJECTED ->
                getString(R.string.invitation_creation_rejected)
            InviteCreationFailureKind.POSSIBLY_CREATED ->
                getString(R.string.invitation_creation_possibly_created)
            InviteCreationFailureKind.UNREACHABLE ->
                if (retryAllowed) {
                    getString(R.string.invitation_creation_unreachable)
                } else {
                    getString(R.string.invitation_creation_no_more_attempts)
                }
        }
        binding.confirmMessage.isVisible = true
        if (!retryAllowed) {
            retryBlocked = true
        }
        setCreatingUi(false)
    }

    /**
     * Toggle the invite-creation in-flight UI: show/hide the progress row and
     * disable/enable the confirm + dismiss buttons so the ~30s spend cannot be
     * re-triggered or dismissed mid-flight (Fix C). Once [retryBlocked] is
     * latched the confirm button stays disabled for the dialog's remaining
     * life — only dismiss comes back.
     */
    private fun setCreatingUi(inProgress: Boolean) {
        binding.creationProgress.isVisible = inProgress
        binding.confirmBtn.isEnabled = !inProgress && !retryBlocked
        binding.dismissBtn.isEnabled = !inProgress
    }

//    override fun dismiss() {
//        lifecycleScope.launch {
//            onConfirmAction?.invoke(false)
//            super.dismiss()
//        }
//    }
//
//    fun show(fragmentActivity: FragmentActivity, onConfirmAction: (Boolean) -> Unit) {
//        this.onConfirmAction = onConfirmAction
//        show(fragmentActivity)
//    }
}
