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

package de.schildbach.wallet.ui.buy_sell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.integrations.coinbase.ui.CoinBaseWebClientActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentIntegrationOverviewBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class IntegrationOverviewFragment : Fragment(R.layout.fragment_integration_overview) {
    private val binding by viewBinding(FragmentIntegrationOverviewBinding::bind)
    private val viewModel by viewModels<IntegrationOverviewViewModel>()

    private val coinbaseAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data

        if (result.resultCode == Activity.RESULT_OK) {
            data?.extras?.getString(CoinBaseWebClientActivity.RESULT_TEXT)?.let { code ->
                handleCoinbaseAuthResult(code)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.continueBtn.setOnClickListener {
            continueCoinbase()
        }
        // the convert or buy swap feature should be hidden
        // as there are not enough supported currencies with the v3 API
        binding.buyConvertText.isVisible = false
        binding.buyConvertIc.isVisible = false
    }

    private fun continueCoinbase() {
        lifecycleScope.launch {
            val goodToGo = if (viewModel.shouldShowCoinbaseInfoPopup()) {
                AdaptiveDialog.custom(
                    R.layout.dialog_withdrawal_limit_info
                ).showAsync(requireActivity()) ?: false
            } else {
                true
            }

            if (goodToGo) {
                viewModel.setCoinbaseInfoPopupShown()
                coinbaseAuthLauncher.launch(
                    Intent(
                        requireContext(),
                        CoinBaseWebClientActivity::class.java
                    )
                )
            }
        }
    }

    private fun handleCoinbaseAuthResult(code: String) {
        lifecycleScope.launchWhenResumed {
            val success = AdaptiveDialog.withProgress(getString(R.string.loading), requireActivity()) {
                viewModel.loginToCoinbase(code)
            }

            if (success) {
                safeNavigate(IntegrationOverviewFragmentDirections.overviewToCoinbase())
            } else {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.login_error_title, getString(R.string.coinbase)),
                    getString(R.string.login_error_message, getString(R.string.coinbase)),
                    getString(android.R.string.cancel),
                    getString(R.string.retry)
                ).show(requireActivity()) { retry ->
                    if (retry == true) {
                        handleCoinbaseAuthResult(code)
                    }
                }
            }
        }
    }
}
