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

package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVotingKeyInputBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class VotingKeyInputFragment : Fragment(R.layout.fragment_voting_key_input) {
    private val binding by viewBinding(FragmentVotingKeyInputBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val args by navArgs<VotingKeyInputFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.keyInput.doOnTextChanged { text, _, _, _ ->
            val key = text.toString()
            binding.verifyButton.isEnabled = key.isNotEmpty()
            binding.inputWrapper.isEndIconVisible = key.isNotEmpty()
            binding.inputWrapper.isErrorEnabled = false
            binding.inputError.isVisible = false
        }

        binding.verifyButton.setOnClickListener {
            val key = binding.keyInput.text.toString()

            if (viewModel.verifyKey(key)) {
                viewModel.addKey(key)
                lifecycleScope.launch {
                    KeyboardUtil.hideKeyboard(requireContext(), binding.keyInput)
                    delay(200)
                    safeNavigate(VotingKeyInputFragmentDirections.votingKeyInputToAddKeys(args.requestId))
                }
            } else {
                binding.inputWrapper.isErrorEnabled = true
                binding.inputError.isVisible = true
            }
        }

        binding.keyInput.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.keyInput)
    }
}
