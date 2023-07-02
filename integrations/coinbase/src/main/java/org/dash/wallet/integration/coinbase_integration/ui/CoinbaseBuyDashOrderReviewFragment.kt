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
import android.os.CountDownTimer
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.payment_method_picker.CardUtils
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashOrderReviewBinding
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseResultDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseBuyDashOrderReviewViewModel

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CoinbaseBuyDashOrderReviewFragment : Fragment(R.layout.fragment_coinbase_buy_dash_order_review) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashOrderReviewBinding::bind)
    private val viewModel by viewModels<CoinbaseBuyDashOrderReviewViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()
    private lateinit var selectedPaymentMethodId: String
    private var loadingDialog: AdaptiveDialog? = null
    private var isRetrying = false
    private var newBuyOrderId: String? = null

    private val countDownTimer by lazy {
        object : CountDownTimer(10000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                binding.confirmBtn.text = getString(R.string.confirm_sec, (millisUntilFinished / 1000).toString())
                binding.retryIcon.visibility = View.GONE
                setConfirmBtnStyle(org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_Blue, org.dash.wallet.common.R.color.dash_white)
            }

            override fun onFinish() {
                binding.confirmBtn.text = getString(R.string.retry)
                binding.retryIcon.visibility = View.VISIBLE
                isRetrying = true
                setConfirmBtnStyle(org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_LightGrey, org.dash.wallet.common.R.color.dash_blue)
            }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_ANDROID_BACK)
            findNavController().popBackStack()
        }

        binding.toolbar.setNavigationOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_TOP_BACK)
            findNavController().popBackStack()
        }

        binding.cancelBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_CANCEL)
            val dialog = AdaptiveDialog.simple(
                getString(R.string.cancel_transaction),
                getString(R.string.no_keep_it),
                getString(R.string.yes_cancel)
            )
            dialog.isCancelable = false
            dialog.show(requireActivity()) { result ->
                if (result == true) {
                    viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_CANCEL_YES)
                    findNavController().popBackStack()
                } else {
                    viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_CANCEL_NO)
                }
            }
        }

        arguments?.let {
            CoinbaseBuyDashOrderReviewFragmentArgs.fromBundle(it).paymentMethod.apply {
                binding.contentOrderReview.paymentMethodName.text = this.name
                val cardIcon = if (this.paymentMethodType == PaymentMethodType.Card) {
                    CardUtils.getCardIcon(this.account)
                } else {
                    null
                }
                binding.contentOrderReview.paymentMethodName.isVisible = cardIcon == null
                binding.contentOrderReview.paymentMethodIcon.setImageResource(cardIcon ?: 0)
                binding.contentOrderReview.account.text = this.account
                selectedPaymentMethodId = this.paymentMethodId
            }

            CoinbaseBuyDashOrderReviewFragmentArgs.fromBundle(it).placeBuyOrderUIModel.apply {
                updateOrderReviewUI()
            }
        }

        binding.confirmBtnContainer.setOnClickListener {
            countDownTimer.cancel()
            if (isRetrying) {
                getNewBuyOrder()
                isRetrying = false
            } else {
                newBuyOrderId?.let { buyOrderId -> viewModel.commitBuyOrder(buyOrderId) }
            }
        }


        viewModel.commitBuyOrderSuccessState.observe(viewLifecycleOwner) { params ->
            safeNavigate(
                CoinbaseBuyDashOrderReviewFragmentDirections.coinbaseBuyDashOrderReviewToTwoFaCode(
                    CoinbaseTransactionParams(params, TransactionType.BuyDash)
                )
            )
        }
        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            if (showLoading) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }


        viewModel.commitBuyOrderFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(it)
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_FEE_INFO)
            safeNavigate(CoinbaseBuyDashOrderReviewFragmentDirections.orderReviewToFeeInfo())
        }

        viewModel.placeBuyOrderFailedCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.something_wrong_title),
                getString(R.string.retry_later_message),
                getString(R.string.close)
            ).show(requireActivity())
        }

        viewModel.placeBuyOrder.observe(viewLifecycleOwner) {
            it.updateOrderReviewUI()
            countDownTimer.start()
        }

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            setNetworkState(hasInternet)
        }
        observeNavigationCallBack()
    }

    private fun PlaceBuyOrderUIModel.updateOrderReviewUI() {
        newBuyOrderId = this.buyOrderId
        binding.contentReviewBuyOrderDashAmount.dashAmount.text = this.dashAmount
        binding.contentReviewBuyOrderDashAmount.message.text =
            getString(R.string.you_will_receive_dash_on_your_dash_wallet, this.dashAmount)
        binding.contentOrderReview.purchaseAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                this.purchaseAmount,
                GenericUtils.currencySymbol(this.purchaseCurrency)
            )
        binding.contentOrderReview.coinbaseFeeAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                this.coinBaseFeeAmount,
                GenericUtils.currencySymbol(this.coinbaseFeeCurrency)
            )
        binding.contentOrderReview.totalAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                this.totalAmount,
                GenericUtils.currencySymbol(this.totalCurrency)
            )
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

    private fun showBuyOrderDialog(responseMessage: String) {
        val transactionStateDialog = CoinBaseResultDialog.newInstance(CoinBaseResultDialog.Type.PURCHASE_ERROR, responseMessage).apply {
            this.onCoinBaseResultDialogButtonsClickListener = object : CoinBaseResultDialog.CoinBaseResultDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseResultDialog.Type) {
                    when (type) {
                        CoinBaseResultDialog.Type.PURCHASE_ERROR -> {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_ERROR_CLOSE)
                            dismiss()
                            findNavController().popBackStack()
                        }
                        else -> {}
                    }
                }
            }
        }
        transactionStateDialog.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    override fun onResume() {
        super.onResume()
        countDownTimer.start()
        viewModel.monitorNetworkStateChange()
    }

    override fun onPause() {
        countDownTimer.cancel()
        super.onPause()
    }

    private fun setNetworkState(hasInternet: Boolean) {
        binding.networkStatusStub.isVisible = !hasInternet
        binding.previewOfflineGroup.isVisible = hasInternet
    }

    private fun setConfirmBtnStyle(@StyleRes buttonStyle: Int, @ColorRes colorRes: Int) {
        binding.confirmBtnContainer.background = resources.getRoundedBackground(buttonStyle)
        binding.confirmBtn.setTextColor(resources.getColor(colorRes))
    }

    private fun observeNavigationCallBack() {
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("resume_review")
            ?.observe(viewLifecycleOwner) { isOrderReviewResumed ->
                if (isOrderReviewResumed) {
                    getNewBuyOrder()
                }
            }
    }

    private fun getNewBuyOrder() {
        viewModel.onRefreshOrderClicked(
            amountViewModel.onContinueEvent.value?.second,
            selectedPaymentMethodId
        )
    }
}
