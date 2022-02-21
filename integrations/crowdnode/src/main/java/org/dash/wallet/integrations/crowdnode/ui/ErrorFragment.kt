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

package org.dash.wallet.integrations.crowdnode.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.InsufficientMoneyException
import org.dash.wallet.common.services.SecurityModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentErrorBinding
import javax.inject.Inject

@AndroidEntryPoint
class ErrorFragment : Fragment(R.layout.fragment_error) {
    companion object {
        private const val INSUFFICIENT_MONEY_PREFIX = "Insufficient money"
    }

    private val binding by viewBinding(FragmentErrorBinding::bind)
    private val viewModel by activityViewModels<CrowdNodeViewModel>()

    @Inject
    lateinit var securityModel: SecurityModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.subtitle.text = viewModel.crowdNodeError?.message ?: ""

        binding.sendReportBtn.setOnClickListener {
            viewModel.sendReport()
        }

        viewModel.crowdNodeError?.let { ex ->
            if (ex is InsufficientMoneyException ||
                ex.message?.startsWith(INSUFFICIENT_MONEY_PREFIX) == true
            ) {
                binding.positiveBtn.isVisible = false
            }
        }

        binding.positiveBtn.setOnClickListener {
            lifecycleScope.launch {
                securityModel.requestPinCode(requireActivity())?.let {
                    findNavController().popBackStack()
                    viewModel.retry()
                }
            }
        }
        binding.negativeBtn.setOnClickListener {
            viewModel.reset()
            findNavController().popBackStack()
        }
    }
}