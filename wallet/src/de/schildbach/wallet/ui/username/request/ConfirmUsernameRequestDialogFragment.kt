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
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.compose_views.createInstantUsernameDialog
import de.schildbach.wallet.ui.username.UsernameType
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogConfirmUsernameRequestBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.dialogSafeNavigate
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class ConfirmUsernameRequestDialogFragment: OffsetDialogFragment(R.layout.dialog_confirm_username_request) {
    private val binding by viewBinding(DialogConfirmUsernameRequestBinding::bind)

    private val viewModel by viewModels<ConfirmUserNameDialogViewModel>()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    private val args by navArgs<ConfirmUsernameRequestDialogFragmentArgs>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.isContestableUsername = requestUserNameViewModel.isUsernameContestable()
        viewModel.hasIdentity = requestUserNameViewModel.identity != null
        val usernameType = args.usernameType
        viewModel.usernameType = usernameType
        binding.confirmBtn.setOnClickListener {
            requestUserNameViewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_CONFIRM)
            if (usernameType == UsernameType.Primary && viewModel.isContestableUsername) {
                createInstantUsernameDialog(
                    onCreateInstantUsername = {
                        // Navigate to the instant username fragment
                        dialogSafeNavigate(
                            ConfirmUsernameRequestDialogFragmentDirections.toRequestUsernameFragmentForInstant(
                                usernameType = UsernameType.Secondary
                            )
                        )
                        dismiss()
                    },
                    onCancel = {
                        requestUserNameViewModel.submit()
                        dismiss()
                    }
                ).show(requireActivity())
            } else {
                requestUserNameViewModel.submit()
                dismiss()
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
        }

        if (requestUserNameViewModel.isUsingInvite()) {
            binding.dashAmountView.isVisible = false
            binding.dashSymbolView.isVisible = false
            binding.fiatAmountView.isVisible = false
            binding.fiatSymbolView.isVisible = false
        }
    }
}
