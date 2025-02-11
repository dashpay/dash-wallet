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
import de.schildbach.wallet.ui.send.SendCoinsViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogConfirmTopupBinding
import de.schildbach.wallet_test.databinding.DialogConfirmUsernameRequestBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class ConfirmInviteDialogFragment: OffsetDialogFragment(R.layout.dialog_confirm_topup) {
    private val binding by viewBinding(DialogConfirmTopupBinding::bind)

    private val viewModel by viewModels<ConfirmTopupDialogViewModel>()
    private val invitationFragmentViewModel by viewModels<InvitationFragmentViewModel>()
    private val args by navArgs<ConfirmInviteDialogFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.amount = Coin.valueOf(args.amount)
        binding.confirmBtn.setOnClickListener {
            lifecycleScope.launch {
                // requestUserNameViewModel.logEvent(AnalyticsConstants.UsersContacts.TOPUP_CONFIRM)
                val identityId = invitationFragmentViewModel.sendInviteTransaction(Coin.valueOf(args.amount))
                findNavController().navigate(
                    ConfirmInviteDialogFragmentDirections.toInviteCreatedFragment(identityId)
                )
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
