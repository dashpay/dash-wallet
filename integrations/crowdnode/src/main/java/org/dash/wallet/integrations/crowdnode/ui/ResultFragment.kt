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
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.wallet.Wallet.CouldNotAdjustDownwards
import org.bitcoinj.wallet.Wallet.DustySendRequested
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentResultBinding
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.MessageStatusException
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import javax.inject.Inject

@AndroidEntryPoint
class ResultFragment : Fragment(R.layout.fragment_result) {
    companion object {
        private const val INSUFFICIENT_MONEY_PREFIX = "Insufficient money"
    }

    private val binding by viewBinding(FragmentResultBinding::bind)
    private val args by navArgs<ResultFragmentArgs>()
    private val viewModel by activityViewModels<CrowdNodeViewModel>()

    @Inject
    lateinit var securityFunctions: AuthenticationManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (args.isError) {
            setError()
            viewModel.clearError()
        } else {
            setSuccess()
        }
    }

    private fun setErrorMessage(ex: Exception) {
        binding.subtitle.text = when (ex) {
            is DustySendRequested, is CouldNotAdjustDownwards -> getString(R.string.send_coins_error_dusty_send)
            is InsufficientMoneyException -> ex.message ?: getString(R.string.send_coins_error_insufficient_money)
            is CrowdNodeException -> {
                if (ex.message == CrowdNodeException.WITHDRAWAL_ERROR) {
                    getString(R.string.crowdnode_withdrawal_limits_error)
                } else {
                    ex.message ?: ""
                }
            }
            else -> ex.message ?: ""
        }
    }

    private fun setError() {
        binding.icon.setImageResource(R.drawable.ic_error)
        binding.title.text = args.title
        binding.title.setTextAppearance(R.style.Headline5_Red)
        binding.subtitle.text = args.subtitle
        binding.sendReportBtn.isVisible = true
        binding.negativeBtn.isVisible = true

        binding.sendReportBtn.setOnClickListener {
            viewModel.sendReport()
        }

        viewModel.crowdNodeError?.let {
            setErrorMessage(it)
        }

        if (viewModel.crowdNodeError is InsufficientMoneyException ||
            viewModel.crowdNodeError?.message?.startsWith(INSUFFICIENT_MONEY_PREFIX) == true ||
            viewModel.crowdNodeError?.message == CrowdNodeException.CONFIRMATION_ERROR
        ) {
            binding.positiveBtn.isVisible = false
        } else {
            binding.positiveBtn.isVisible = true
            binding.positiveBtn.text = getString(R.string.button_retry)
            binding.positiveBtn.setOnClickListener {
                if (viewModel.crowdNodeError is MessageStatusException) {
                    retryOnlineSignUp()
                } else if (viewModel.signUpStatus == SignUpStatus.Error) {
                    viewModel.logEvent(AnalyticsConstants.CrowdNode.CREATE_ACCOUNT_ERROR_RETRY)
                    retrySignUp()
                } else {
                    // for other errors, going back to the previous screen
                    findNavController().popBackStack()
                }
            }
        }

        binding.negativeBtn.setOnClickListener {
            if (viewModel.signUpStatus == SignUpStatus.Error) {
                viewModel.resetSignUp()
                findNavController().popBackStack()
            } else {
                findNavController().popBackStack(R.id.crowdNodePortalFragment, false)
            }
        }
    }

    private fun retrySignUp() {
        // For signup error, launching a retry attempt
        lifecycleScope.launch {
            securityFunctions.authenticate(requireActivity())?.let {
                findNavController().popBackStack()
                viewModel.retrySignup()
            }
        }
    }

    private fun retryOnlineSignUp() {
        safeNavigate(ResultFragmentDirections.resultToOnlineAccountEmail())
    }

    private fun setSuccess() {
        binding.icon.setImageResource(R.drawable.ic_success_green)
        binding.title.text = args.title
        binding.title.setTextAppearance(R.style.Headline5_Green)
        binding.subtitle.text = args.subtitle
        binding.sendReportBtn.isVisible = false
        binding.negativeBtn.isVisible = false
        binding.positiveBtn.text = getString(R.string.button_close)

        binding.positiveBtn.setOnClickListener {
            findNavController().popBackStack(R.id.crowdNodePortalFragment, false)
        }
    }
}
