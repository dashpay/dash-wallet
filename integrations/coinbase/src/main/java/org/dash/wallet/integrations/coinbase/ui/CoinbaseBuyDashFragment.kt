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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.coinbase.R
import org.dash.wallet.integrations.coinbase.databinding.FragmentCoinbaseBuyDashBinding
import org.dash.wallet.integrations.coinbase.databinding.KeyboardHeaderViewBinding
import org.dash.wallet.integrations.coinbase.model.CoinbaseErrorType
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseBuyDashViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.coinbaseViewModels

@AndroidEntryPoint
class CoinbaseBuyDashFragment : Fragment(R.layout.fragment_coinbase_buy_dash) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashBinding::bind)
    private val sharedViewModel by coinbaseViewModels<CoinbaseViewModel>()
    private val viewModel by coinbaseViewModels<CoinbaseBuyDashViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()
    private lateinit var fragment: EnterAmountFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            fragment = EnterAmountFragment.newInstance(
                isMaxButtonVisible = false,
                showCurrencySelector = false
            )
            val headerBinding = KeyboardHeaderViewBinding.inflate(layoutInflater, null, false)
            fragment.setViewDetails(getString(R.string.button_continue), headerBinding.root)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            rate?.let {
                binding.toolbarSubtitle.text = getString(
                    R.string.exchange_rate_template,
                    Coin.COIN.toPlainString(),
                    rate.fiat.toFormattedString()
                )
            }
        }

        amountViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            lifecycleScope.launch {
                val validated = AdaptiveDialog.withProgress(getString(R.string.loading), requireActivity()) {
                    validate(pair.first, retryWithDeposit = false)
                }

                if (validated) {
                    safeNavigate(CoinbaseBuyDashFragmentDirections.buyDashToOrderReview())
                }
            }
        }

        binding.authLimitBanner.root.setOnClickListener {
            sharedViewModel.logEvent(AnalyticsConstants.Coinbase.BUY_AUTH_LIMIT)
            AdaptiveDialog.custom(R.layout.dialog_withdrawal_limit_info).show(requireActivity())
        }

        sharedViewModel.uiState.observe(viewLifecycleOwner) {
            if (it.isSessionExpired) {
                findNavController().popBackStack()
            } else {
                fragment.handleNetworkState(it.isNetworkAvailable)
            }
        }
    }

    private suspend fun validate(dashAmount: Coin, retryWithDeposit: Boolean): Boolean {
        val isMoreThanLimit = sharedViewModel.isInputGreaterThanLimit(dashAmount)
        binding.authLimitBanner.root.isVisible = isMoreThanLimit

        if (isMoreThanLimit) {
            return false
        }

        return when (viewModel.validateBuyDash(dashAmount, retryWithDeposit)) {
            CoinbaseErrorType.NONE -> true
            CoinbaseErrorType.INSUFFICIENT_BALANCE -> {
                if (shouldRetryWithDeposit()) {
                    validate(dashAmount, retryWithDeposit = true)
                } else {
                    false
                }
            }

            CoinbaseErrorType.NO_BANK_ACCOUNT -> {
                showNoPaymentMethodsError()
                false
            }
            else -> false
        }
    }

    private suspend fun shouldRetryWithDeposit(): Boolean {
        return AdaptiveDialog.create(
            R.drawable.ic_warning,
            getString(R.string.you_dont_have_enough_balance),
            getString(R.string.coinbase_use_bank_account),
            getString(R.string.cancel),
            getString(R.string.confirm)
        ).showAsync(requireActivity()) ?: false
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

    private fun openCoinbaseWebsite() {
        val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        defaultBrowser.data = Uri.parse(getString(R.string.coinbase_website))
        startActivity(defaultBrowser)
    }
}