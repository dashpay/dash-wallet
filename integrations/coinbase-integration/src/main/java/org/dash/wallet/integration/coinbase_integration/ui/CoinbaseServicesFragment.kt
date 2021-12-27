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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.dash.wallet.common.WalletDataProvider
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialog.Companion.newProgress
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseServicesBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel
import org.dash.wallet.common.util.safeNavigate
import java.util.*
import kotlin.concurrent.schedule

@AndroidEntryPoint
class CoinbaseServicesFragment : Fragment(R.layout.fragment_coinbase_services) {
    private val binding by viewBinding(FragmentCoinbaseServicesBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private var loadingDialog: FancyAlertDialog? = null
    private var currentExchangeRate: org.dash.wallet.common.data.ExchangeRate? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val walletDataProvider = requireActivity().application as WalletDataProvider
        val defaultCurrency = walletDataProvider.defaultCurrencyCode()

        binding.titleBar.connected.setText(R.string.connected)
        binding.titleBar.toolbarTitle.setText(R.string.coinbase)
        binding.titleBar.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.disconnectLayout.setOnClickListener {
            viewModel.disconnectCoinbaseAccount()
            // Use
            Timer().schedule(1000) {
                requireActivity().finish()
            }
        }

        binding.buyDashBtn.setOnClickListener {
            safeNavigate(CoinbaseServicesFragmentDirections.servicesToBuyDash())
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

        viewModel.user.observe(
            viewLifecycleOwner,
            {
                binding.walletBalanceDash.setAmount(Coin.parseCoin(it.balance?.amount))
            }
        )

        viewModel.userAccountError.observe(
            viewLifecycleOwner,
            {
                showErrorDialog(
                    R.string.coinbase_dash_wallet_error_title,
                    R.string.coinbase_dash_wallet_error_message,
                    R.drawable.ic_info_red,
                    R.string.CreateـDashـAccount,
                    R.string.close
                )
            }
        )
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

    private fun showErrorDialog(
        @StringRes title: Int,
        @StringRes message: Int,
        @DrawableRes image: Int,
        @StringRes positiveButtonText: Int,
        @StringRes negativeButtonText: Int
    ) {
        val dialog = CoinBaseErrorDialog.newInstance(title, message, image, positiveButtonText, negativeButtonText)
        dialog.showNow(parentFragmentManager, "error")
    }
}
