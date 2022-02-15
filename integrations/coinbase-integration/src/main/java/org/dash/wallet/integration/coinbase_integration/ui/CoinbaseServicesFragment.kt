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

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialog.Companion.newProgress
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseServicesBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import javax.inject.Inject

@AndroidEntryPoint
class CoinbaseServicesFragment : Fragment(R.layout.fragment_coinbase_services) {
    private val binding by viewBinding(FragmentCoinbaseServicesBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private var loadingDialog: FancyAlertDialog? = null
    private var currentExchangeRate: org.dash.wallet.common.data.ExchangeRate? = null
    @Inject lateinit var analyticsService: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.connected.setText(R.string.connected)
        binding.titleBar.toolbarTitle.setText(R.string.coinbase)
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){
            requireActivity().finish()
        }

        binding.disconnectLayout.setOnClickListener {
            viewModel.disconnectCoinbaseAccount()
        }

        binding.buyDashBtn.setOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.BUY_DASH, bundleOf())
            viewModel.getPaymentMethods()

        }
        viewModel.activePaymentMethods.observe(viewLifecycleOwner){ event ->
            event.getContentIfNotHandled()?.toTypedArray()?.let { paymentMethodsArray ->
                CoinbaseServicesFragmentDirections.servicesToBuyDash(paymentMethodsArray)
            }?.let { navDirection -> safeNavigate(navDirection) }
        }

        binding.walletBalanceDash.setFormat(viewModel.config.format.noCode())
        binding.walletBalanceDash.setApplyMarkup(false)
        binding.walletBalanceDash.setAmount(Coin.ZERO)


        viewModel.exchangeRate.observe(viewLifecycleOwner,
            { rate ->
                if (rate != null) {
                    currentExchangeRate = rate
                    if (currentExchangeRate != null) {
                        setLocalFaitAmount(viewModel.user.value?.balance?.amount ?: "0")
                    }
                }
            })

        viewModel.user.observe(
            viewLifecycleOwner,
            {
                binding.walletBalanceDash.setAmount(Coin.parseCoin(it.balance?.amount))
                if (currentExchangeRate != null) {
                    setLocalFaitAmount(it.balance?.amount ?:"0")
                }

            }
        )

        viewModel.showLoading.observe(
            viewLifecycleOwner,
            {
                if (it) {
                    showProgress(R.string.loading)
                } else
                    dismissProgress()
            }
        )

        viewModel.userAccountError.observe(viewLifecycleOwner){
            val error = CoinbaseGenericErrorUIModel(
                R.string.coinbase_dash_wallet_error_title,
                getString(R.string.coinbase_dash_wallet_error_message),
                R.drawable.ic_info_red,
                R.string.CreateـDashـAccount,
                R.string.close
            )
            analyticsService.logEvent(AnalyticsConstants.Coinbase.NO_DASH_WALLET, bundleOf())
            safeNavigate(CoinbaseServicesFragmentDirections.coinbaseServicesToError(error))
        }


        viewModel.activePaymentMethodsFailureCallback.observe(viewLifecycleOwner){
            val activePaymentMethodsError = CoinbaseGenericErrorUIModel(
                R.string.coinbase_dash_wallet_no_payment_methods_error_title,
                getString(R.string.coinbase_dash_wallet_no_payment_methods_error_message),
                R.drawable.ic_info_red,
                R.string.add_payment_method,
                R.string.close
            )
            analyticsService.logEvent(AnalyticsConstants.Coinbase.NO_PAYMENT_METHODS, bundleOf())
            safeNavigate(CoinbaseServicesFragmentDirections.coinbaseServicesToError(activePaymentMethodsError))
        }


        viewModel.coinbaseLogOutCallback.observe(viewLifecycleOwner){
            requireActivity().finish()
        }
    }

    private fun setLocalFaitAmount(balance:String) {
        val exchangeRate = ExchangeRate(Coin.COIN, currentExchangeRate?.fiat)
        val localValue =
            exchangeRate.coinToFiat(Coin.parseCoin(balance))
        binding.walletBalanceLocal.text = GenericUtils.fiatToString(localValue)
    }

    private fun showProgress(messageResId: Int) {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = newProgress(messageResId, 0)
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
}
