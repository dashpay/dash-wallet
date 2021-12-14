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
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialog.Companion.newProgress
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseServicesBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel
import org.dash.wallet.integration.coinbase_integration.model.CoinbasePaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import java.util.*
import kotlin.concurrent.schedule

@AndroidEntryPoint
class CoinbaseServicesFragment : Fragment(R.layout.fragment_coinbase_services) {
    private val binding by viewBinding(FragmentCoinbaseServicesBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private var loadingDialog: FancyAlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        binding.walletBalanceDash.setApplyMarkup(false)
        binding.walletBalanceDash.setFormat(MonetaryFormat().noCode().minDecimals(8))
        binding.walletBalanceDash.setAmount(Coin.ZERO)
        binding.walletBalanceLocal.setFormat(MonetaryFormat().noCode().minDecimals(2))
        binding.walletBalanceLocal.setAmount(Coin.ZERO)

        setupPaymentMethodPayment()

        viewModel.user.observe(
            viewLifecycleOwner,
            {
                binding.walletBalanceDash.setAmount(Coin.parseCoin(it.balance?.amount))
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

    private fun setupPaymentMethodPayment() {
        val coinbasePaymentMethods = listOf(
            CoinbasePaymentMethod(
                id = "127b4d76-a1a0-5de7-8185-3657d7b526e",
                type = "secure3d_card",
                name = "Debit card ****1234",
                currency = "USD",
                allowBuy = true,
                allowSell = false
            ),
            CoinbasePaymentMethod(
                id = "83562370-3e5c-51db-87da-752af5ab9559",
                type = "ach_bank_account",
                name = "Chase Bank *****1111",
                currency = "USD",
                allowBuy = true,
                allowSell = true
            )
        )
        val paymentMethods = coinbasePaymentMethods.map {
            val nameAccountPair = splitNameAndAccount(it.name)
            PaymentMethod(
                nameAccountPair.first,
                nameAccountPair.second,
                "", // set "Checking" to get "****1234 • Checking" in subtitle
                paymentMethodIcon = when (it.type) {
                    "secure3d_card" -> R.drawable.ic_card
                    "ach_bank_account" -> R.drawable.ic_bank
                    else -> null
                },
                // It's unlikely that we can distinguish visa/mastercard from coinbase API
                accountIcon = if (it.type == "secure3d_card") R.drawable.ic_visa else null
            )
        }
        binding.paymentMethodPicker.paymentMethods = paymentMethods
    }

    private fun splitNameAndAccount(nameAccount: String?): Pair<String, String> {
        return nameAccount?.let {
            val split = "(?=\\s[a-z]?\\*+)".toRegex().split(nameAccount)
            return Pair(split.first(), split.getOrNull(1) ?: "")
        } ?: Pair("", "")
    }
}
