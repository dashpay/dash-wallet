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
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.onUserInteraction
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
            onUserInteraction()
        }

        binding.verifyButton.setOnClickListener {
            val wifKey = binding.keyInput.text.toString()

            if (viewModel.verifyKey(wifKey)) {
                val key = viewModel.getKeyFromWIF(wifKey)!!
                val isValidMasternode = viewModel.verifyMasterVotingKey(key)
                binding.inputWrapper.isErrorEnabled = false
                binding.inputError.isVisible = false
                if (isValidMasternode) {
                    lifecycleScope.launch {
                        if(viewModel.hasKey(key)) {
                            viewModel.addKey(key)
                            KeyboardUtil.hideKeyboard(requireContext(), binding.keyInput)
                            delay(200)
                            safeNavigate(
                                VotingKeyInputFragmentDirections.votingKeyInputToAddKeys(
                                    args.requestId,
                                    args.vote
                                )
                            )
                        } else {
                            binding.inputError.text = getString(R.string.voting_key_input_already_present)
                            binding.inputWrapper.isErrorEnabled = true
                            binding.inputError.isVisible = true
                        }
                    }
                } else {
                    binding.inputError.text = getString(R.string.voting_key_input_not_active_error)
                    binding.inputWrapper.isErrorEnabled = true
                    binding.inputError.isVisible = true
                }
            } else {
                // determine the type of invalid key
                val isMainnet = de.schildbach.wallet.Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET
                val errorMessageId = when (viewModel.invalidKeyType(wifKey)) {
                    InvalidKeyType.WRONG_NETWORK -> if (isMainnet) R.string.voting_key_input_invalid_private_key_wrong_network_testnet else R.string.voting_key_input_invalid_private_key_wrong_network_mainnet
                    InvalidKeyType.ADDRESS -> R.string.voting_key_input_invalid_private_key_address
                    InvalidKeyType.PRIVATE_KEY_HEX -> R.string.voting_key_input_invalid_private_key_private_key_hex
                    InvalidKeyType.PUBLIC_KEY_HEX -> R.string.voting_key_input_invalid_private_key_public_key_hex
                    InvalidKeyType.SHORT -> R.string.voting_key_input_invalid_private_key_too_short
                    InvalidKeyType.CHECKSUM -> R.string.voting_key_input_invalid_private_key_incorrect
                    InvalidKeyType.CHARACTER -> R.string.voting_key_input_invalid_private_key_invalid_char

                    else -> R.string.voting_key_input_error
                }
                binding.inputError.text = getString(errorMessageId, getString(if (isMainnet) R.string.dash_private_key_mainnet else R.string.dash_private_key_testnet))
                binding.inputWrapper.isErrorEnabled = true
                binding.inputError.isVisible = true
            }
        }

        binding.keyInput.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.keyInput)
    }
}
