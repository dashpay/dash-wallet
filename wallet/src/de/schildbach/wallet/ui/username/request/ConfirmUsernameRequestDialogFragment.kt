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

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.AuthenticationManager
import javax.inject.Inject
import kotlinx.coroutines.launch
import de.schildbach.wallet.ui.compose_views.createInstantUsernameDialog
import de.schildbach.wallet.ui.username.UsernameType
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogConfirmUsernameRequestBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.dialogSafeNavigate
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class ConfirmUsernameRequestDialogFragment: OffsetDialogFragment(R.layout.dialog_confirm_username_request) {

    @Inject
    lateinit var authManager: AuthenticationManager
    private val binding by viewBinding(DialogConfirmUsernameRequestBinding::bind)

    private val viewModel by viewModels<ConfirmUserNameDialogViewModel>()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    private val args by navArgs<ConfirmUsernameRequestDialogFragmentArgs>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.isContestableUsername = requestUserNameViewModel.isUsernameContestable()
        viewModel.hasIdentity = requestUserNameViewModel.identity != null
        // Shielded-funded creations show the denomination actually leaving
        // the shielded balance (0.03/0.25 under v13), not the L1 fee schedule.
        viewModel.paymentSource = requestUserNameViewModel.paymentSource
        val usernameType = args.usernameType
        viewModel.usernameType = usernameType
        binding.confirmBtn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                requestUserNameViewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_CONFIRM)
                if (usernameType == UsernameType.Primary && viewModel.isContestableUsername &&
                    !requestUserNameViewModel.hasSecondaryName()) {
                    createInstantUsernameDialog(
                        onCreateInstantUsername = {
                            // The secondary confirm sheet always shows
                            // Coin.ZERO (the instant name is free) — record
                            // THIS primary approval now so the eventual dual
                            // submit caps against it instead of the
                            // secondary sheet's zero (MO-1069 review
                            // 5431682794).
                            requestUserNameViewModel.recordApprovedFundingAmount(
                                viewModel.uiState.value.amountDuffs
                            )
                            // Navigate to the instant username fragment
                            dialogSafeNavigate(
                                ConfirmUsernameRequestDialogFragmentDirections.toRequestUsernameFragmentForInstant(
                                    usernameType = UsernameType.Secondary
                                )
                            )
                            dismiss()
                        },
                        onCancel = {
                            lifecycleScope.launch {
                                authenticateThenSubmit(
                                    this@ConfirmUsernameRequestDialogFragment,
                                    authManager,
                                    requestUserNameViewModel,
                                    viewModel.uiState.value.amountDuffs
                                )
                                dismiss()
                            }
                        }
                    ).show(requireActivity())
                } else {
                    lifecycleScope.launch {
                        authenticateThenSubmit(
                            this@ConfirmUsernameRequestDialogFragment,
                            authManager,
                            requestUserNameViewModel,
                            // The secondary confirm always shows Coin.ZERO
                            // (the instant name is free) — a dual-name submit
                            // actually spends the PRIMARY's approved amount,
                            // recorded in onCreateInstantUsername above
                            // (MO-1069 review 5431682794).
                            if (usernameType == UsernameType.Secondary) {
                                requestUserNameViewModel.approvedFundingAmountDuffs
                            } else {
                                viewModel.uiState.value.amountDuffs
                            }
                        )
                        dismiss()
                    }
                }
            }
        }
        binding.confirmBtn.isEnabled = false
        binding.confirmMessage.text = when (usernameType) {
            UsernameType.Primary -> getString(R.string.new_account_confirm_message, args.username)
            UsernameType.Secondary -> getString(R.string.new_account_secondary_confirm_message, args.username)
        }
        binding.userAccepts.setOnClickListener {
            binding.confirmBtn.isEnabled = binding.userAccepts.isChecked
        }

        binding.dismissBtn.setOnClickListener { dismiss() }

        viewModel.uiState.observe(viewLifecycleOwner) {
            binding.dashAmountView.text = it.amountStr
            binding.fiatSymbolView.text = it.fiatSymbol
            binding.fiatAmountView.text = it.fiatAmountStr
            // Shielded-funded creations spend the pool's exit denomination —
            // say so under the amount instead of letting it read as an L1
            // wallet spend.
            binding.costSourceLabel.isVisible =
                it.fromShieldedBalance && !requestUserNameViewModel.isUsingInvite()
        }

        if (requestUserNameViewModel.isUsingInvite()) {
            binding.dashAmountView.isVisible = false
            binding.dashSymbolView.isVisible = false
            binding.fiatAmountView.isVisible = false
            binding.fiatSymbolView.isVisible = false
        }
    }
}
