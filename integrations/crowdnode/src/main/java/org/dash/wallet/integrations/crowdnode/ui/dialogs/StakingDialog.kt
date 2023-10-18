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

package org.dash.wallet.integrations.crowdnode.ui.dialogs

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.copy
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.DialogStakingBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import java.util.*

@AndroidEntryPoint
class StakingDialog : OffsetDialogFragment(R.layout.dialog_staking) {
    private val binding by viewBinding(DialogStakingBinding::bind)
    val viewModel by activityViewModels<CrowdNodeViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stakingMessageTwo.text = getString(
            R.string.crowdnode_staking_info_message_second,
            String.format(Locale.getDefault(), "%.1f", viewModel.getMasternodeAPY())
        )
        binding.stakingFirstMinDepositMessage.text = getString(
            R.string.crowdnode_staking_info_message,
            CrowdNodeConstants.DASH_FORMAT.format(CrowdNodeConstants.MINIMUM_DASH_DEPOSIT)
        )
        binding.stakingApyTitle.text = getString(
            R.string.crowdnode_staking_apy_title,
            String.format(Locale.getDefault(), "%.1f", viewModel.getCrowdNodeAPY())
        )
        binding.stakingConnectedDashAddress.text = viewModel.accountAddress.value?.toBase58()
        binding.stakingConnectedAddressContainer.setOnClickListener {
            viewModel.accountAddress.value?.toBase58()?.copy(requireActivity(), "dash address")
            Toast.makeText(requireContext(), R.string.crowdnode_staking_toast_address_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
