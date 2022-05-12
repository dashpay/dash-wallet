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
import org.dash.wallet.common.services.SecurityModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentResultBinding
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
    lateinit var securityModel: SecurityModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (args.isError) {
            binding.icon.setImageResource(R.drawable.ic_error_red)
            binding.title.text = args.title
            binding.title.setTextAppearance(R.style.Headline5_Bold_Red)
            binding.sendReportBtn.isVisible = true
            binding.negativeBtn.isVisible = true
            binding.positiveBtn.text = getString(R.string.button_retry)

            viewModel.crowdNodeError?.let { ex ->
                setErrorMessage(ex)
            }

            binding.sendReportBtn.setOnClickListener {
                viewModel.sendReport()
            }

            binding.positiveBtn.setOnClickListener {
                if (viewModel.signUpStatus == SignUpStatus.Error) {
                    // For signup error, launching a retry attempt
                    lifecycleScope.launch {
                        securityModel.requestPinCode(requireActivity())?.let {
                            findNavController().popBackStack()
                            viewModel.retrySignup()
                        }
                    }
                } else {
                    // for transfer errors, going back to the transfer screen
                    findNavController().popBackStack()
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
            viewModel.clearError()
        } else {
            binding.icon.setImageResource(R.drawable.ic_success_green)
            binding.title.text = args.title
            binding.title.setTextAppearance(R.style.Headline5_Bold_Green)
            binding.subtitle.text = args.subtitle
            binding.sendReportBtn.isVisible = false
            binding.negativeBtn.isVisible = false
            binding.positiveBtn.text = getString(R.string.button_close)

            binding.positiveBtn.setOnClickListener {
                findNavController().popBackStack(R.id.crowdNodePortalFragment, false)
            }
        }
    }

    private fun setErrorMessage(ex: Exception) {
        binding.subtitle.text = when (ex) {
            is DustySendRequested, is CouldNotAdjustDownwards -> getString(R.string.send_coins_error_dusty_send)
            is InsufficientMoneyException -> getString(R.string.send_coins_error_insufficient_money)
            else -> ex.message ?: ""
        }

        if (ex is InsufficientMoneyException ||
            ex.message?.startsWith(INSUFFICIENT_MONEY_PREFIX) == true
        ) {
            binding.positiveBtn.isVisible = false
        }
    }
}