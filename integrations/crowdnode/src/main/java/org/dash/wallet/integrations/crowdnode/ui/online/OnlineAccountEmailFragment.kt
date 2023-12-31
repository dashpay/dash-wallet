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

package org.dash.wallet.integrations.crowdnode.ui.online

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentOnlineAccountEmailBinding
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
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

        binding.emailInput.doOnTextChanged { text, _, _, _ ->
            binding.inputWrapper.isErrorEnabled = false
            binding.continueBtn.isEnabled = isEmail(text)
        }

        val continueAction = {
            viewModel.logEvent(AnalyticsConstants.CrowdNode.CREATE_ONLINE_CONTINUE)
            val input = binding.emailInput.text.toString()

            if (isEmail(input)) {
                continueCreating(input)
            } else {
                binding.inputWrapper.isErrorEnabled = true
            }
        }

        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            when (actionId) {
                EditorInfo.IME_ACTION_NEXT -> {
                    continueAction()
                    true
                }
                else -> false
            }
        }

        binding.continueBtn.setOnClickListener {
            continueAction()
        }

        viewModel.observeOnlineAccountStatus().observe(viewLifecycleOwner) { status ->
            when (status) {
                OnlineAccountStatus.Creating -> {
                    binding.mainContent.isVisible = false
                    binding.progressView.isVisible = true
                }
                OnlineAccountStatus.SigningUp -> viewModel.initiateOnlineSignUp()
                else -> {
                    binding.mainContent.isVisible = true
                    binding.progressView.isVisible = false
                }
            }
        }

        viewModel.onlineAccountRequest.observe(viewLifecycleOwner) { args ->
            safeNavigate(
                OnlineAccountEmailFragmentDirections.onlineAccountEmailToSignUp(
                    args[CrowdNodeViewModel.URL_ARG]!!,
                    args[CrowdNodeViewModel.EMAIL_ARG] ?: ""
                )
            )
        }

        viewModel.networkError.observe(viewLifecycleOwner) {
            Toast.makeText(
                requireContext(),
                "${getString(R.string.cannot_send)} ${getString(R.string.network_unavailable_check_connection)}",
                Toast.LENGTH_LONG
            ).show()
        }

        viewModel.observeCrowdNodeError().observe(viewLifecycleOwner) {
            if (it != null) {
                safeNavigate(
                    OnlineAccountEmailFragmentDirections.onlineAccountEmailToResult(
                        true,
                        getString(R.string.crowdnode_signup_error),
                        ""
                    )
                )
            }
        }

        if (viewModel.onlineAccountStatus != OnlineAccountStatus.Creating) {
            KeyboardUtil.showSoftKeyboard(requireContext(), binding.emailInput)
        }
    }

    private fun continueCreating(email: String) {
        KeyboardUtil.hideKeyboard(requireContext(), binding.emailInput)
        viewModel.signAndSendEmail(email)
    }

    private fun isEmail(text: CharSequence?): Boolean {
        return !text.isNullOrEmpty() &&
            android.util.Patterns.EMAIL_ADDRESS.matcher(text).matches()
    }
}
