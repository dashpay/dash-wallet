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

package org.dash.wallet.integrations.crowdnode.ui.portal

import android.os.Bundle
import android.view.View
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentOnlineAccountEmailBinding
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel

@AndroidEntryPoint
class OnlineAccountEmailFragment : Fragment(R.layout.fragment_online_account_email) {
    private val binding by viewBinding(FragmentOnlineAccountEmailBinding::bind)
    private val viewModel by activityViewModels<CrowdNodeViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.input.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.continueBtn.isEnabled = isEmail(text)
        }

        binding.continueBtn.setOnClickListener {
            val input = binding.input.text.toString()

            if (isEmail(input)) {
                viewModel.signAndSendEmail(input)
            } else {
                binding.inputWrapper.isErrorEnabled = true
            }
        }
    }

    private fun isEmail(text: CharSequence?): Boolean {
        return !text.isNullOrEmpty() &&
                android.util.Patterns.EMAIL_ADDRESS.matcher(text).matches()
    }
}