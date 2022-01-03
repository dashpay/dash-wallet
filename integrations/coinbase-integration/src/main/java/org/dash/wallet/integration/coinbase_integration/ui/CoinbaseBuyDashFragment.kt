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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.dash.wallet.common.livedata.EventObserver
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashBinding
import org.dash.wallet.integration.coinbase_integration.databinding.KeyboardHeaderViewBinding
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseErrorDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseBuyDashViewModel

@AndroidEntryPoint
@ExperimentalCoroutinesApi
class CoinbaseBuyDashFragment: Fragment(R.layout.fragment_coinbase_buy_dash) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashBinding::bind)
        private val viewModel by viewModels<CoinbaseBuyDashViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()
    private var loadingDialog: FancyAlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
                isMaxButtonVisible = false
            )
            val headerBinding = KeyboardHeaderViewBinding.inflate(layoutInflater, null, false)
            fragment.setViewDetails(getString(R.string.button_continue), headerBinding.root)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        setupPaymentMethodPayment()
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        amountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = getString(
                R.string.exchange_rate_template,
                Coin.COIN.toPlainString(),
                GenericUtils.fiatToString(rate.fiat)
            )
        }

        amountViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            viewModel.onContinueClicked(pair.second,binding.paymentMethodPicker.selectedMethodIndex)
        }

        viewModel.placeBuyOrder.observe(
            viewLifecycleOwner, EventObserver {
                    safeNavigate(CoinbaseBuyDashFragmentDirections.buyDashToOrderReview(
                        binding.paymentMethodPicker.paymentMethods[binding.paymentMethodPicker.selectedMethodIndex],
                        it))
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

        viewModel.placeBuyOrderFailedCallback.observe(viewLifecycleOwner){
            showErrorDialog(
                R.string.error,
                it,
                R.drawable.ic_info_red,
                negativeButtonText= R.string.close
            )
        }
    }

    private fun setupPaymentMethodPayment() {
        viewModel.activePaymentMethods.observe(viewLifecycleOwner){
            binding.paymentMethodPicker.paymentMethods = it
        }

        arguments?.let {
            viewModel.getActivePaymentMethods(CoinbaseBuyDashFragmentArgs.fromBundle(it).paymentMethods)
        }
    }

    private fun showProgress(messageResId: Int) {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = FancyAlertDialog.newProgress(messageResId, 0)
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    private fun showErrorDialog(
        @StringRes title: Int,
        message: String,
        @DrawableRes image: Int,
        @StringRes positiveButtonText: Int?= null,
        @StringRes negativeButtonText: Int
    ) {
        val dialog = CoinBaseErrorDialog.newInstance(title, message, image, positiveButtonText, negativeButtonText)
        dialog.showNow(parentFragmentManager, "error")
    }

}