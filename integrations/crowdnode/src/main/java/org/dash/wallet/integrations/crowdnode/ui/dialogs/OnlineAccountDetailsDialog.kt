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
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentOnlineAccountDetailsBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel

class OnlineAccountDetailsDialog : OffsetDialogFragment(R.layout.fragment_online_account_details) {
    private val binding by viewBinding(FragmentOnlineAccountDetailsBinding::bind)
    private val viewModel by activityViewModels<CrowdNodeViewModel>()
    override val forceExpand: Boolean = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.primaryDashAddress.text = viewModel.primaryDashAddress.toString()
        binding.copyPrimaryAddressBtn.setOnClickListener {
            viewModel.copyPrimaryAddress()
            Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        binding.dashAddress.text = viewModel.accountAddress.value.toString()
        binding.copyAddressBtn.setOnClickListener {
            viewModel.copyAccountAddress()
            Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
    }
}
