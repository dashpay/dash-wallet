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
package org.dash.wallet.integrations.coinbase.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.databinding.FragmentIntegrationPortalBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.blinkAnimator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.R
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseServicesViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.coinbaseViewModels

@AndroidEntryPoint
class CoinbaseServicesFragment : Fragment(R.layout.fragment_integration_portal) {
    private val binding by viewBinding(FragmentIntegrationPortalBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private val sharedViewModel by coinbaseViewModels<CoinbaseViewModel>()
    private var balanceAnimator: ObjectAnimator? = null

    private val coinbaseAuthResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uri = intent.extras?.get("uri") as Uri?
            val code = uri?.getQueryParameter("code")

            if (code != null) {
                lifecycleScope.launch {
                    withResumed {
                        handleCoinbaseAuthResult(code)
                    }
                }
            }

            startActivity(intent)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.toolbarTitle.text = getString(R.string.coinbase)
        binding.toolbarIcon.setImageResource(R.drawable.ic_coinbase)
        binding.balanceHeader.text = getString(R.string.balance_on_coinbase)
        binding.transferSubtitle.text = getString(R.string.between_dash_wallet_and_coinbase)
        binding.convertSubtitle.text = getString(R.string.between_dash_wallet_and_coinbase)
        binding.disconnectTitle.text = getString(R.string.disconnect_coinbase_account)

        binding.disconnectBtn.setOnClickListener {
            viewModel.disconnectCoinbaseAccount()
        }

        binding.buyBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_DASH)
            safeNavigate(CoinbaseServicesFragmentDirections.servicesToBuyDash())
        }

        // the convert or buy swap feature should be hidden
        // as there are not enough supported currencies with the v3 API
        binding.convertBtn.isVisible = false
        binding.convertBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_DASH)
            safeNavigate(CoinbaseServicesFragmentDirections.servicesToConvertCrypto(true))
        }

        binding.transferBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.TRANSFER_DASH)
            safeNavigate(CoinbaseServicesFragmentDirections.servicesToTransferDash())
        }

        binding.balanceDash.setFormat(viewModel.balanceFormat)
        binding.balanceDash.setApplyMarkup(false)
        binding.balanceDash.setAmount(Coin.ZERO)
        this.balanceAnimator = binding.balanceHeader.blinkAnimator

        binding.root.setOnRefreshListener {
            viewModel.refreshBalance()
        }

        sharedViewModel.uiState.observe(viewLifecycleOwner) { state ->
            setNetworkState(state.isNetworkAvailable)

            if (state.isSessionExpired) {
                sharedViewModel.clearWasLoggedOut()

                AdaptiveDialog.create(
                    R.drawable.ic_relogin,
                    getString(R.string.your_coinbase_session_has_expired),
                    getString(R.string.please_log_in_to_your_coinbase_account),
                    getString(R.string.cancel),
                    getString(R.string.log_in)
                ).also {
                    it.isCancelable = false
                }.show(requireActivity()) { login ->
                    if (login == true) {
                        requireActivity().openCustomTab(CoinbaseConstants.LINK_URL)
                    } else {
                        findNavController().popBackStack()
                    }
                }
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (!state.isLoggedIn) {
                findNavController().popBackStack()
                return@observe
            }

            if (state.error == CoinbaseErrorType.USER_ACCOUNT_ERROR) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.coinbase_dash_wallet_error_title),
                    getString(R.string.coinbase_dash_wallet_error_message),
                    getString(R.string.close),
                    getString(R.string.create_dash_account),
                ).show(requireActivity()) { createAccount ->
                    if (createAccount == true) {
                        viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_CREATE_ACCOUNT)
                        openCoinbaseWebsite()
                    }
                }
                viewModel.clearError()
            } else {
                binding.balanceDash.setAmount(state.balance)
                binding.balanceLocal.text = state.balanceFiat?.toFormattedString() ?: ""

                if (state.isBalanceUpdating) {
                    this.balanceAnimator?.start()
                } else {
                    binding.root.isRefreshing = false
                    this.balanceAnimator?.end()
                }
            }
        }

        viewModel.refreshBalance()
        sharedViewModel.getBaseIdForFiatModel()

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            coinbaseAuthResultReceiver,
            IntentFilter(CoinbaseConstants.AUTH_RESULT_ACTION)
        )
    }

    private fun setNetworkState(hasInternet: Boolean) {
        binding.lastKnownBalance.isVisible = !hasInternet
        binding.networkStatusStub.isVisible = !hasInternet
        binding.actionsView.isVisible = hasInternet
        binding.disconnectBtn.isVisible = hasInternet
        binding.disconnectedIndicator.isVisible = !hasInternet
    }

    private fun openCoinbaseWebsite() {
        val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        defaultBrowser.data = Uri.parse(getString(R.string.coinbase_website))
        startActivity(defaultBrowser)
    }

    private fun handleCoinbaseAuthResult(code: String) {
        lifecycleScope.launch {
            val success = AdaptiveDialog.withProgress(getString(R.string.loading), requireActivity()) {
                sharedViewModel.loginToCoinbase(code)
            }

            if (success) {
                return@launch
            }

            val retry = AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.login_error_title, getString(R.string.coinbase)),
                getString(R.string.login_error_message, getString(R.string.coinbase)),
                getString(android.R.string.cancel),
                getString(R.string.retry)
            ).showAsync(requireActivity())

            if (retry == true) {
                handleCoinbaseAuthResult(code)
            } else {
                findNavController().popBackStack()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(
            coinbaseAuthResultReceiver
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        this.balanceAnimator = null
    }
}
