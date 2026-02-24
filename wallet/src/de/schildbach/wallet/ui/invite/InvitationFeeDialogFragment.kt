/*
 * Copyright (c) 2024 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import org.bitcoinj.core.Coin
import de.schildbach.wallet.ui.CheckPinDialog
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogInvitationFeeBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class InvitationFeeDialogFragment : OffsetDialogFragment(R.layout.dialog_invitation_fee) {
    private val binding by viewBinding(DialogInvitationFeeBinding::bind)
    private var selectedFee = Constants.DASH_PAY_FEE_CONTESTED
    private var spendableBalance = Coin.ZERO
    @OptIn(ExperimentalCoroutinesApi::class)
    private val viewModel by viewModels<InvitationFragmentViewModel>()
    private val args by navArgs<InvitationFeeDialogFragmentArgs>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setMode(true)
        binding.mixButton.setOnClickListener {
            CheckPinDialog.show(requireActivity()) { pin ->
                if (pin != null) {
                    findNavController().navigate(
                        InvitationFeeDialogFragmentDirections.toConfirmInviteDialog(selectedFee.value, args.source)
                    )
                    // TODO: why doesn't safeNavigate work
                    // safeNavigate(InvitationFeeDialogFragmentDirections.toConfirmInviteDialog(selectedFee.value))
                }
            }
        }
        viewModel.walletData.observeSpendableBalance().observe(viewLifecycleOwner) { walletBalance ->
            spendableBalance = walletBalance
            binding.contestedName.isEnabled = walletBalance >= Constants.DASH_PAY_FEE_CONTESTED
            updateContinueButton()
        }
        binding.contestedName.setOnClickListener {
            setMode(true)
        }

        binding.nonContestedName.setOnClickListener {
            setMode(false)
        }
    }

    private fun setMode(isContestedName: Boolean) {
        if (isContestedName) {
            binding.contestedName.isSelected = true
            binding.nonContestedName.isSelected = false
            selectedFee = Constants.DASH_PAY_FEE_CONTESTED
        } else {
            binding.contestedName.isSelected = false
            binding.nonContestedName.isSelected = true
            selectedFee = Constants.DASH_PAY_FEE
        }
        updateContinueButton()
    }

    private fun updateContinueButton() {
        binding.mixButton.isEnabled = spendableBalance >= selectedFee
    }
}
