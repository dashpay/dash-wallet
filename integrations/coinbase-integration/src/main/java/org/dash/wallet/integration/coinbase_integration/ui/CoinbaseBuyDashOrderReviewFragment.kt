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
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.Constants
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.NetworkUnavailableFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.getRoundedBackground
import org.dash.wallet.common.ui.payment_method_picker.CardUtils
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashOrderReviewBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import org.dash.wallet.integration.coinbase_integration.model.PlaceBuyOrderUIModel
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseBuyDashDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseBuyDashOrderReviewViewModel
import javax.inject.Inject

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CoinbaseBuyDashOrderReviewFragment : Fragment(R.layout.fragment_coinbase_buy_dash_order_review) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashOrderReviewBinding::bind)
    private val viewModel by viewModels<CoinbaseBuyDashOrderReviewViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()
    private lateinit var selectedPaymentMethodId: String
    private var loadingDialog: FancyAlertDialog? = null
    private var isRetrying = false
    private var transactionStateDialog: CoinBaseBuyDashDialog? = null
    private var newBuyOrderId: String? = null
    @Inject
    lateinit var analyticsService: FirebaseAnalyticsServiceImpl
    private val countDownTimer by lazy {   object : CountDownTimer(10000, 1000) {

        override fun onTick(millisUntilFinished: Long) {
            binding.confirmBtn.text = getString(R.string.confirm_sec, (millisUntilFinished / 1000).toString())
            binding.retryIcon.visibility = View.GONE
            setConfirmBtnStyle(org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_Blue, org.dash.wallet.common.R.color.dash_white)
        }

        override fun onFinish() {
            binding.confirmBtn.text = getString(R.string.retry)
            binding.retryIcon.visibility = View.VISIBLE
            isRetrying =true
            setConfirmBtnStyle(org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_TransparentBlue, org.dash.wallet.common.R.color.dash_blue)
        }
     }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){
            analyticsService.logEvent(AnalyticsConstants.Coinbase.BOTTOM_BACK_TO_ENTER_AMOUNT, bundleOf())
            findNavController().popBackStack()
        }

        binding.toolbar.setNavigationOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.TOP_BACK_TO_ENTER_AMOUNT, bundleOf())
            findNavController().popBackStack()
        }

        binding.cancelBtn.setOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.CANCEL_DASH_PURCHASE, bundleOf())
            safeNavigate(CoinbaseBuyDashOrderReviewFragmentDirections.confirmCancelBuyDashTransaction())
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
            analyticsService.logEvent(AnalyticsConstants.Coinbase.CONFIRM_DASH_PURCHASE, bundleOf())
            countDownTimer.cancel()
            if (isRetrying) {
                viewModel.onRefreshOrderClicked(amountViewModel.onContinueEvent.value?.second,
                    selectedPaymentMethodId)
                isRetrying = false
            } else {
                newBuyOrderId?.let { buyOrderId -> viewModel.commitBuyOrder(buyOrderId) }
            }
        }

        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            if (showLoading) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }


        viewModel.commitBuyOrderFailedCallback.observe(viewLifecycleOwner){
            showBuyOrderDialog(CoinBaseBuyDashDialog.Type.PURCHASE_ERROR, null)
        }


        viewModel.transactionCompleted.observe(viewLifecycleOwner){ transactionStatus ->
            showBuyOrderDialog(if (transactionStatus.isTransactionSuccessful)
                CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS else CoinBaseBuyDashDialog.Type.TRANSFER_ERROR, transactionStatus.responseMessage)
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            analyticsService.logEvent(AnalyticsConstants.Coinbase.FEE_INFO, bundleOf())
            safeNavigate(CoinbaseBuyDashOrderReviewFragmentDirections.orderReviewToFeeInfo())
        }

        viewModel.placeBuyOrderFailedCallback.observe(viewLifecycleOwner){
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.something_wrong_title,
                getString(R.string.retry_later_message),
                R.drawable.ic_info_red,
                negativeButtonText= R.string.close
            )
            safeNavigate(CoinbaseBuyDashOrderReviewFragmentDirections.coinbaseBuyDashOrderReviewToError(placeBuyOrderError))
        }

        viewModel.placeBuyOrder.observe(viewLifecycleOwner){
            it.updateOrderReviewUI()
            countDownTimer.start()
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.network_status_container, NetworkUnavailableFragment.newInstance())
            .commit()

        amountViewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner){ hasInternet ->
            setNetworkState(hasInternet)
        }
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
        loadingDialog = FancyAlertDialog.newProgress(messageResId, 0)
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    private fun showBuyOrderDialog(type: CoinBaseBuyDashDialog.Type, responseMessage: String?) {
        if (transactionStateDialog?.dialog?.isShowing == true)
            transactionStateDialog?.dismissAllowingStateLoss()
        transactionStateDialog = CoinBaseBuyDashDialog.newInstance(type, responseMessage).apply {
            this.onCoinBaseBuyDashDialogButtonsClickListener = object : CoinBaseBuyDashDialog.CoinBaseBuyDashDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseBuyDashDialog.Type) {
                    when (type) {
                        CoinBaseBuyDashDialog.Type.PURCHASE_ERROR -> {
                            dismiss()
                            findNavController().popBackStack()
                        }
                        CoinBaseBuyDashDialog.Type.TRANSFER_ERROR -> {
                            viewModel.retry()
                        }
                        CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS -> {
                            dismiss()
                            requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                            requireActivity().finish()
                        }
                    }
                }
            }
        }
        transactionStateDialog?.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    override fun onResume() {
        super.onResume()
        countDownTimer.start()
        amountViewModel.monitorNetworkStateChange()
    }

    override fun onPause() {
        countDownTimer.cancel()
        super.onPause()
    }

    private fun setNetworkState(hasInternet: Boolean){
        binding.networkStatusContainer.isVisible = !hasInternet
        binding.previewOfflineGroup.isVisible = hasInternet
    }

    private fun setConfirmBtnStyle(@StyleRes buttonStyle: Int, @ColorRes colorRes: Int) {
        binding.confirmBtnContainer.background = resources.getRoundedBackground(buttonStyle)
        binding.confirmBtn.setTextColor(resources.getColor(colorRes))
    }
}
