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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.payment_method_picker.CardUtils
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashOrderReviewBinding
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseBuyDashDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseBuyDashOrderReviewViewModel

@AndroidEntryPoint
class CoinbaseBuyDashOrderReviewFragment : Fragment(R.layout.fragment_coinbase_buy_dash_order_review) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashOrderReviewBinding::bind)
    private val viewModel by viewModels<CoinbaseBuyDashOrderReviewViewModel>()

    private var loadingDialog: FancyAlertDialog? = null
    private var isRetrying =false
    private var transactionStateDialog: CoinBaseBuyDashDialog? = null

    private val countDownTimer by lazy {   object : CountDownTimer(10000, 1000) {

        override fun onTick(millisUntilFinished: Long) {
            binding.confirmBtn.text = getString(R.string.confirm_sec, (millisUntilFinished / 1000).toString())
            binding.retryIcon.visibility = View.GONE
        }

        override fun onFinish() {
            binding.confirmBtn.text = getString(R.string.retry)
            binding.retryIcon.visibility = View.VISIBLE
            isRetrying =true
        }
     }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.cancelBtn.setOnClickListener {
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
            }

            CoinbaseBuyDashOrderReviewFragmentArgs.fromBundle(it).placeBuyOrderUIModel.apply {

                binding.contentReviewBuyOrderDashAmount.dashAmount.text = this.dashAmount
                binding.contentReviewBuyOrderDashAmount.message.text = getString(R.string.you_will_receive_dash_on_your_dash_wallet, this.dashAmount)
                binding.contentOrderReview.purchaseAmount.text =
                    getString(R.string.fiat_balance_with_currency, this.purchaseAmount, GenericUtils.currencySymbol(this.purchaseCurrency))
                binding.contentOrderReview.coinbaseFeeAmount.text =
                    getString(R.string.fiat_balance_with_currency, this.coinBaseFeeAmount, GenericUtils.currencySymbol(this.coinbaseFeeCurrency))
                binding.contentOrderReview.totalAmount.text =
                    getString(R.string.fiat_balance_with_currency, this.totalAmount, GenericUtils.currencySymbol(this.totalCurrency))

                binding.confirmBtnContainer.setOnClickListener {
                    countDownTimer.cancel()
                    if (isRetrying) {
                        countDownTimer.start()
                        isRetrying = false
                   } else {
                        viewModel.commitBuyOrder(this.buyOrderId)
                   }
                }
            }
        }

        countDownTimer.start()

        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            if (showLoading) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }


        viewModel.commitBuyOrderFailedCallback.observe(viewLifecycleOwner){
            showBuyOrderDialog(CoinBaseBuyDashDialog.Type.PURCHASE_ERROR)
        }


        viewModel.transactionCompleted.observe(viewLifecycleOwner){ isTransactionCompleted ->
            showBuyOrderDialog(if (isTransactionCompleted)
                CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS else CoinBaseBuyDashDialog.Type.TRANSFER_ERROR)
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            safeNavigate(CoinbaseBuyDashOrderReviewFragmentDirections.orderReviewToFeeInfo())
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

    private fun showBuyOrderDialog(
        type: CoinBaseBuyDashDialog.Type
    ) {
        if (transactionStateDialog?.dialog?.isShowing == true)
            transactionStateDialog?.dismissAllowingStateLoss()
        transactionStateDialog = CoinBaseBuyDashDialog.newInstance(type).apply {
            this.onCoinBaseBuyDashDialogButtonsClickListener = object : CoinBaseBuyDashDialog.CoinBaseBuyDashDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseBuyDashDialog.Type) {
                    when (type) {
                        CoinBaseBuyDashDialog.Type.PURCHASE_ERROR -> {
                            dismiss()
                        }
                        CoinBaseBuyDashDialog.Type.TRANSFER_ERROR -> {
                            viewModel.retry()
                        }
                        CoinBaseBuyDashDialog.Type.TRANSFER_SUCCESS -> {
                            dismiss()
                            requireActivity().finish()
                        }
                    }
                }
            }
        }
        transactionStateDialog?.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countDownTimer.cancel()
    }
}
