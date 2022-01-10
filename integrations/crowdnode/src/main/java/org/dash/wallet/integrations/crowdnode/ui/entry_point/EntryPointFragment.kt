/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui.entry_point

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentEntryPointBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel

@AndroidEntryPoint
class EntryPointFragment : Fragment(R.layout.fragment_entry_point) {
    private val binding by viewBinding(FragmentEntryPointBinding::bind)
    private val viewModel: CrowdNodeViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.requiredDashTxt.text = getString(
            R.string.required_dash_amount,
            CrowdNodeViewModel.MINIMUM_REQUIRED_DASH.toPlainString()
        )

        viewModel.hasEnoughBalance.observe(viewLifecycleOwner) {
            Log.i("CROWDNODE", "Has enough balance: ${it}, minimum: ${CrowdNodeViewModel.MINIMUM_REQUIRED_DASH.toPlainString()}, current balance: ${viewModel.dashBalance.value?.toPlainString() ?: "null"}")
            displayRequirements(viewModel.needPassphraseBackUp, it)
        }
    }

    private fun displayRequirements(needBackup: Boolean, enoughBalance: Boolean) {
        binding.errorPassphrase.isVisible = needBackup
        binding.errorBalance.isVisible = !enoughBalance

        if (needBackup || !enoughBalance) {
            binding.newAccountImg.clearColorFilter()
            binding.newAccountDivider.isVisible = true
            binding.newAccountNavIcon.isVisible = false
        } else {
            binding.newAccountImg.setColorFilter(resources.getColor(R.color.blue_300, null))
            binding.newAccountDivider.isVisible = false
            binding.newAccountNavIcon.isVisible = true
        }
    }
}