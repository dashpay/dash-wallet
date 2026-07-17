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
import de.schildbach.wallet.ui.invite.InvitationFragmentViewModel
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.amount = Coin.valueOf(args.amount)
        binding.confirmBtn.setOnClickListener {
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
                            return@launch
                        }
                    }
                    // Authenticate right before the spend — the amount has been
                    // confirmed on this screen (standard order; the fee screen no
                    // longer prompts). A cancelled prompt spends nothing.
                    authManager.authenticate(requireActivity()) ?: return@launch
                    // invitationFragmentViewModel.logEvent(AnalyticsConstants.UsersContacts.TOPUP_CONFIRM)
                    val identityId = if (args.shielded) {
                        // SHIELDED (L2) invite: fund a note directly from the
                        // shielded pool. Contested-ness follows the fee the
                        // inviter picked (0.25 → contested → 0.3 denomination).
                        val contested = inviteAmount.value >=
                            de.schildbach.wallet.Constants.DASH_PAY_FEE_CONTESTED.value
                        when (val result = invitationFragmentViewModel.createShieldedInvite(contested)) {
                            is de.schildbach.wallet.service.platform.sdk.SdkWriteResult.Broadcast ->
                                result.value.user
                            else -> {
                                binding.confirmMessage.text =
                                    getString(R.string.error_sending_invite_transaction)
                                binding.confirmMessage.isVisible = true
                                return@launch
                            }
                        }
                    } else {
                        invitationFragmentViewModel.sendInviteTransaction(inviteAmount)
                    }
                    findNavController().navigate(
                        ConfirmInviteDialogFragmentDirections.toInviteCreatedFragment(identityId, args.source)
                    )
                } catch (e: Exception) {
                    log.info("error sending transaction:", e)
                    binding.confirmMessage.text = getString(R.string.error_sending_invite_transaction)
                    binding.confirmMessage.isVisible = true
                }
            }
        }
        binding.dismissBtn.setOnClickListener { dismiss() }
        binding.confirmMessage.isVisible = false
        viewModel.uiState.observe(viewLifecycleOwner) {
            binding.dashAmountView.text = it.amountStr
            binding.fiatSymbolView.text = it.fiatSymbol
            binding.fiatAmountView.text = it.fiatAmountStr
        }
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
