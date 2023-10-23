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

package de.schildbach.wallet.ui.coinjoin

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentCoinjoinLevelBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.setRoundedRippleBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class CoinJoinLevelFragment : Fragment(R.layout.fragment_coinjoin_level) {
    private val binding by viewBinding(FragmentCoinjoinLevelBinding::bind)
    private val viewModel by viewModels<CoinJoinLevelViewModel>()
    private var selectedCoinJoinMode = CoinJoinMode.NONE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.intermediateCl.setOnClickListener {
            askAndSwitch(CoinJoinMode.INTERMEDIATE)
        }

        binding.advancedCl.setOnClickListener {
            askAndSwitch(CoinJoinMode.ADVANCED)
        }

        binding.continueBtn.setOnClickListener {
            lifecycleScope.launch {
                if (viewModel.isMixing) {
                    if (confirmStopMixing()) {
                        viewModel.setMode(CoinJoinMode.NONE)
                        requireActivity().finish()
                    }
                } else {
                    viewModel.setMode(selectedCoinJoinMode)
                    requireActivity().finish()
                }
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        viewModel.mixingMode.observe(viewLifecycleOwner) { mixingMode ->
            setMode(mixingMode)
        }

        if (viewModel.isMixing) {
            binding.continueBtn.setText(R.string.coinjoin_stop)
            binding.continueBtn.setRoundedRippleBackground(R.style.PrimaryButtonTheme_Large_Red)
        } else {
            binding.continueBtn.setText(R.string.coinjoin_start)
            binding.continueBtn.setRoundedRippleBackground(R.style.PrimaryButtonTheme_Large_Blue)
        }
    }

    private fun showConnectionWaringDialog(mode: CoinJoinMode) {
        AdaptiveDialog.create(
            org.dash.wallet.integration.coinbase_integration.R.drawable.ic_warning,
            getString(
                if (mode == CoinJoinMode.INTERMEDIATE) {
                    R.string.Intermediate_level_WIFI_Warning
                } else {
                    R.string.Advanced_level_WIFI_Warning
                }
            ),
            getString(R.string.privcay_level_WIFI_warning_desc),
            getString(org.dash.wallet.integration.coinbase_integration.R.string.cancel),
            getString(R.string.continue_anyway)
        ).show(requireActivity()) {
            if (it == true) {
                viewModel.logEvent(AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_WIFI_BTN_CONTINUE)
                setMode(mode)
            } else {
                viewModel.logEvent(AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_WIFI_BTN_CANCEL)
            }
        }
    }

    private fun askAndSwitch(mode: CoinJoinMode) {
        if (viewModel.isMixing) {
            AdaptiveDialog.simple(
                getString(R.string.coinjoin_change_level_confirmation),
                getString(R.string.cancel),
                getString(
                    R.string.change_to,
                    getString(if (mode == CoinJoinMode.ADVANCED) R.string.advanced else R.string.intermediate)
                )
            ).show(requireActivity()) { toChange ->
                if (toChange == true) {
                    setMode(mode)
                    lifecycleScope.launch {
                        viewModel.setMode(mode)
                    }
                    requireActivity().finish()
                }
            }
        } else if (viewModel.isWifiConnected()) {
            setMode(mode)
        } else {
            showConnectionWaringDialog(mode)
        }
    }

    private fun setMode(mode: CoinJoinMode) {
        if (mode == CoinJoinMode.INTERMEDIATE) {
            binding.intermediateCl.isSelected = true
            binding.advancedCl.isSelected = false
            selectedCoinJoinMode = mode
            binding.continueBtn.isEnabled = true
        } else if (mode == CoinJoinMode.ADVANCED) {
            binding.intermediateCl.isSelected = false
            binding.advancedCl.isSelected = true
            selectedCoinJoinMode = mode
            binding.continueBtn.isEnabled = true
        }
    }

    private suspend fun confirmStopMixing(): Boolean {
        return AdaptiveDialog.create(
            null,
            getString(R.string.coinjoin_stop_mixing_title),
            getString(R.string.coinjoin_stop_mixing_message),
            getString(R.string.cancel),
            getString(R.string.coinjoin_stop)
        ).showAsync(requireActivity()) == true
    }
}
