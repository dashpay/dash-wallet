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
package org.dash.wallet.integration.coinbase_integration.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.dash.wallet.common.databinding.FragmentIntegrationPortalBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.blinkAnimator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.PaymentMethodsUiState
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseActivityViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel
import javax.inject.Inject

@AndroidEntryPoint
class CoinbaseServicesFragment : Fragment(R.layout.fragment_integration_portal) {
    private val binding by viewBinding(FragmentIntegrationPortalBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private var loadingDialog: AdaptiveDialog? = null
    private val sharedViewModel: CoinbaseActivityViewModel by activityViewModels()
    private var balanceAnimator: ObjectAnimator? = null
    @Inject lateinit var analyticsService: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }
        binding.toolbarTitle.text = getString(R.string.coinbase)
        binding.toolbarIcon.setImageResource(R.drawable.ic_coinbase)
        binding.balanceHeader.text = getString(R.string.balance_on_coinbase)
        binding.transferSubtitle.text = getString(R.string.between_dash_wallet_and_coinbase)
        binding.convertSubtitle.text = getString(R.string.between_dash_wallet_and_coinbase)
        binding.disconnectTitle.text = getString(R.string.disconnect_coinbase_account)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            requireActivity().finish()
        }

        binding.disconnectBtn.setOnClickListener {
            viewModel.disconnectCoinbaseAccount()
        }

        binding.buyBtn.setOnClickListener {
            sharedViewModel.paymentMethodsUiState.observe(viewLifecycleOwner) { uiState ->
                // New value received
                when (uiState) {
                    is PaymentMethodsUiState.Success -> {
                        val paymentMethodsArray = uiState.paymentMethodsList.filter { it.isValid }.toTypedArray()

                        if (paymentMethodsArray.isEmpty()) {
                            if (uiState.paymentMethodsList.isEmpty()) {
                                showNoPaymentMethodsError()
                            } else {
                                showBuyingNotAllowedError()
                            }
                        } else {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_DASH)
                            safeNavigate(CoinbaseServicesFragmentDirections.servicesToBuyDash(paymentMethodsArray))
                        }
                    }
                    is PaymentMethodsUiState.Error -> {
                        if (uiState.isError) {
                            showNoPaymentMethodsError()
                        }
                    }
                    is PaymentMethodsUiState.LoadingState ->{
                        if (uiState.isLoading) {
                            showProgress(R.string.loading)
                        } else {
                            dismissProgress()
                        }
                    }
                }
            }
        }

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

        viewModel.balanceUIState.observe(viewLifecycleOwner) { state ->
            binding.balanceDash.setAmount(state.balance)
            binding.balanceLocal.text = state.balanceFiat?.toFormattedString() ?: ""

            if (state.isUpdating) {
                this.balanceAnimator?.start()
            } else {
                binding.root.isRefreshing = false
                this.balanceAnimator?.end()
            }
        }

        viewModel.showLoading.observe(viewLifecycleOwner) {
            if (it) {
                showProgress(R.string.loading)
            } else {
                dismissProgress()
            }
        }

        viewModel.userAccountError.observe(viewLifecycleOwner) {
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
        }

        viewModel.coinbaseLogOutCallback.observe(viewLifecycleOwner) {
            requireActivity().finish()
        }

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner){ isConnected ->
            setNetworkState(isConnected)
        }

        viewModel.refreshBalance()
    }

    private fun showProgress(messageResId: Int) {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = AdaptiveDialog.progress(getString(messageResId))
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    override fun onStop() {
        dismissProgress()
        super.onStop()
    }

    private fun setNetworkState(hasInternet: Boolean) {
        binding.lastKnownBalance.isVisible = !hasInternet
        binding.networkStatusStub.isVisible = !hasInternet
        binding.actionsView.isVisible = hasInternet
        binding.disconnectBtn.isVisible = hasInternet
        binding.disconnectedIndicator.isVisible = !hasInternet
    }

    private fun showNoPaymentMethodsError() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.coinbase_no_payment_methods_error_title),
            getString(R.string.coinbase_no_payment_methods_error_message),
            getString(R.string.close),
            getString(R.string.add_payment_method),
        ).show(requireActivity()) { addMethod ->
            if (addMethod == true) {
                viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_ADD_PAYMENT_METHOD)
                openCoinbaseWebsite()
            }
        }
    }

    private fun showBuyingNotAllowedError() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.coinbase_unusable_payment_method_error_title),
            getString(R.string.coinbase_unusable_payment_method_error_message),
            getString(R.string.close),
            getString(R.string.coinbase_open_account),
        ).show(requireActivity()) { addMethod ->
            if (addMethod == true) {
                openCoinbaseWebsite()
            }
        }
    }

    private fun openCoinbaseWebsite() {
        val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        defaultBrowser.data = Uri.parse(getString(R.string.coinbase_website))
        startActivity(defaultBrowser)
    }

    override fun onDestroy() {
        super.onDestroy()
        this.balanceAnimator = null
    }
}
