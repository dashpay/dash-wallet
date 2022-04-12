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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import coil.load
import coil.size.Scale
import coil.transform.CircleCropTransformation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.Constants
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseConversionPreviewBinding
import org.dash.wallet.integration.coinbase_integration.model.*
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseBuyDashDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseConversionPreviewViewModel

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CoinbaseConversionPreviewFragment : Fragment(R.layout.fragment_coinbase_conversion_preview) {
    private val binding by viewBinding(FragmentCoinbaseConversionPreviewBinding::bind)
    private val viewModel by viewModels<CoinbaseConversionPreviewViewModel>()
    private lateinit var swapTradeUIModel: SwapTradeUIModel
    private var loadingDialog: FancyAlertDialog? = null
    private var isRetrying = false
    private var transactionStateDialog: CoinBaseBuyDashDialog? = null
    private var newSwapOrderId: String? = null


    private val countDownTimer by lazy {
        object : CountDownTimer(10000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                binding.confirmBtn.text = getString(R.string.confirm_sec, (millisUntilFinished / 1000).toString())
                binding.retryIcon.visibility = View.GONE
            }

            override fun onFinish() {
                binding.confirmBtn.text = getString(R.string.retry)
                binding.retryIcon.visibility = View.VISIBLE
                isRetrying = true
            }
        }
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.cancelBtn.setOnClickListener {
            safeNavigate(CoinbaseConversionPreviewFragmentDirections.confirmCancelBuyDashTransaction())
        }

        arguments?.let {

            CoinbaseConversionPreviewFragmentArgs.fromBundle(it).swapModel.apply {
                updateConversionPreviewUI()
                swapTradeUIModel = this
            }
        }

        binding.confirmBtnContainer.setOnClickListener {
            countDownTimer.cancel()
            if (isRetrying) {
                getNewCommitOrder()
                isRetrying = false
            } else {
                newSwapOrderId?.let { orderId ->
                    swapTradeUIModel.let {
                        viewModel.commitSwapTrade(orderId, it.inputCurrency, it.inputAmount)
                    }
                }
            }
        }

        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            if (showLoading) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }

        viewModel.commitSwapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(CoinBaseBuyDashDialog.Type.CONVERSION_ERROR, it)
        }

        viewModel.sellSwapSuccessState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(CoinBaseBuyDashDialog.Type.CONVERSION_SUCCESS)
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            safeNavigate(CoinbaseConversionPreviewFragmentDirections.orderReviewToFeeInfo())
        }

        viewModel.swapTradeFailureState.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.something_wrong_title,
                getString(R.string.retry_later_message),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(CoinbaseConversionPreviewFragmentDirections.coinbaseServicesToError(placeBuyOrderError))
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            newSwapOrderId = it.swapTradeId
            countDownTimer.start()
        }

        viewModel.commitSwapTradeSuccessState.observe(viewLifecycleOwner) { params ->
            safeNavigate(
                CoinbaseConversionPreviewFragmentDirections.conversionPreviewToTwoFaCode(
                    CoinbaseTransactionParams(params, TransactionType.BuySwap)
                )
            )
        }
        observeNavigationCallBack()

        viewModel.getUserAccountAddressFailedCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.error,
                getString(R.string.error),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }


        viewModel.onInsufficientMoneyCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.insufficient_money_title,
                getString(R.string.insufficient_money_msg),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }

        viewModel.onFailure.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.send_coins_error_msg,
                getString(R.string.insufficient_money_msg),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }
    }

    private fun SwapTradeUIModel.updateConversionPreviewUI() {
        newSwapOrderId = this.swapTradeId

        binding.contentOrderReview.outputAccount.text = getString(
            R.string.fiat_balance_with_currency,
            this.outputAmount,
            GenericUtils.currencySymbol(this.outputCurrency)
        )
        binding.contentOrderReview.inputAccountTitle.text = this.inputCurrencyName
        binding.contentOrderReview.convertOutputTitle.text = this.outputCurrencyName

        binding.contentOrderReview.inputAccountSubtitle.text = this.inputCurrency
        binding.contentOrderReview.convertOutputSubtitle.text = this.outputCurrency

        if (this.inputCurrency == DASH_CURRENCY) {
            binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_dash_wallet_on_this_device)
            binding.contentOrderReview.outputAccountHintLabel.setText(R.string.to_your_coinbase_account)
        } else {
            binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_your_coinbase_account)
            binding.contentOrderReview.outputAccountHintLabel.setText(R.string.to_dash_wallet_on_this_device)
        }

        binding.contentOrderReview.inputAccount.text = getString(
            R.string.fiat_balance_with_currency,
            this.inputAmount,
            GenericUtils.currencySymbol(this.inputCurrency)
        )

        binding.contentOrderReview.purchaseAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                this.displayInputAmount,
                GenericUtils.currencySymbol(this.displayInputCurrency)
            )

        binding.contentOrderReview.coinbaseFeeAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                this.feeAmount,
                GenericUtils.currencySymbol(this.feeCurrency)
            )

        binding.contentOrderReview.totalAmount.text =
            getString(
                R.string.fiat_balance_with_currency,
                (this.displayInputAmount.toBigDecimal() + this.feeAmount.toBigDecimal()).toPlainString(),
                GenericUtils.currencySymbol(this.displayInputCurrency)
            )

        binding.contentOrderReview.inputAccountIcon
            .load(GenericUtils.getCoinIcon(this.inputCurrency.lowercase())) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(org.dash.wallet.common.R.drawable.ic_default_flag)
                transformations(CircleCropTransformation())
            }

        binding.contentOrderReview.convertOutputIcon
            .load(GenericUtils.getCoinIcon(this.outputCurrency.lowercase())) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(org.dash.wallet.common.R.drawable.ic_default_flag)
                transformations(CircleCropTransformation())
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

    private fun showBuyOrderDialog(type: CoinBaseBuyDashDialog.Type, responseMessage: String? = null) {
        if (transactionStateDialog?.dialog?.isShowing == true)
            transactionStateDialog?.dismissAllowingStateLoss()
        transactionStateDialog = CoinBaseBuyDashDialog.newInstance(type, responseMessage).apply {
            this.onCoinBaseBuyDashDialogButtonsClickListener = object : CoinBaseBuyDashDialog.CoinBaseBuyDashDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseBuyDashDialog.Type) {
                    when (type) {
                        CoinBaseBuyDashDialog.Type.CONVERSION_ERROR -> {
                            dismiss()
                            findNavController().popBackStack()
                        }
                        CoinBaseBuyDashDialog.Type.CONVERSION_SUCCESS -> {
                            dismiss()
                            requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                            requireActivity().finish()
                        }
                        else -> {}
                    }
                }
            }
        }
        transactionStateDialog?.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    override fun onResume() {
        super.onResume()
        countDownTimer.start()
    }

    override fun onPause() {
        countDownTimer.cancel()
        super.onPause()
    }

    private fun observeNavigationCallBack() {
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("resume_review")
            ?.observe(viewLifecycleOwner) { isConversionReviewResumed ->
                if (isConversionReviewResumed) {
                    getNewCommitOrder()
                }
            }
    }

    private fun getNewCommitOrder() {
        viewModel.onRefreshOrderClicked(swapTradeUIModel)
    }
}
