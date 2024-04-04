/*
 * Copyright 2024 Dash Core Group.
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
package org.dash.wallet.integrations.maya.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import coil.load
import coil.size.Scale
import coil.transform.CircleCropTransformation
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.setRoundedBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentMayaConversionPreviewBinding
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog

@AndroidEntryPoint
class MayaConversionPreviewFragment : Fragment(R.layout.fragment_maya_conversion_preview) {
    private val binding by viewBinding(FragmentMayaConversionPreviewBinding::bind)
    private val viewModel by viewModels<MayaConversionPreviewViewModel>()
    private lateinit var swapTradeUIModel: SwapTradeUIModel
    private var loadingDialog: AdaptiveDialog? = null
    private var isRetrying = false
    private var transactionStateDialog: MayaResultDialog? = null
    private var newSwapOrderId: String? = null
    private var onBackPressedCallback: OnBackPressedCallback? = null

    private val countDownTimer by lazy {
        object : CountDownTimer(10000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                binding.confirmBtn.text = getString(R.string.confirm_sec, (millisUntilFinished / 1000).toString())
                binding.retryIcon.visibility = View.GONE
                setConfirmBtnStyle(
                    org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_Blue,
                    org.dash.wallet.common.R.color.dash_white
                )
            }

            override fun onFinish() {
                setRetryStatus()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackNavigation()

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            setNetworkState(hasInternet)
        }

        binding.cancelBtn.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CANCEL)
            val dialog = AdaptiveDialog.simple(
                getString(R.string.cancel_transaction),
                getString(R.string.no_keep_it),
                getString(R.string.yes_cancel)
            )
            dialog.isCancelable = false
            dialog.show(requireActivity()) { result ->
                if (result == true) {
                    viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CANCEL_YES)
                    findNavController().popBackStack()
                } else {
                    viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_CANCEL_NO)
                }
            }
        }

        arguments?.let {
            MayaConversionPreviewFragmentArgs.fromBundle(it).swapModel.apply {
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
            } else {
                dismissProgress()
            }
        }

        viewModel.commitSwapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultDialog.Type.CONVERSION_ERROR, it)
        }

        viewModel.sellSwapSuccessState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultDialog.Type.CONVERSION_SUCCESS)
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_FEE_INFO)
            safeNavigate(MayaConversionPreviewFragmentDirections.orderReviewToFeeInfo())
        }

        viewModel.swapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultDialog.Type.SWAP_ERROR, it)
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            newSwapOrderId = it.swapTradeId
            countDownTimer.start()
        }

        viewModel.commitSwapTradeSuccessState.observe(viewLifecycleOwner) { params ->
            val walletName = if (swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY) {
                swapTradeUIModel.inputCurrencyName
            } else {
                swapTradeUIModel.outputCurrencyName
            }

//            safeNavigate(
//                MayaConversionPreviewFragmentDirections.conversionPreviewToTwoFaCode(
//                    CoinbaseTransactionParams(params, TransactionType.BuySwap, walletName)
//                )
//            )
        }
        observeNavigationCallBack()

        viewModel.getUserAccountAddressFailedCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                getString(R.string.error),
                getString(R.string.button_close)
            ).show(requireActivity())
        }

        viewModel.onInsufficientMoneyCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.insufficient_money_title),
                getString(R.string.insufficient_money_msg),
                getString(R.string.button_close)
            ).show(requireActivity())
        }

        viewModel.onFailure.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.send_coins_error_msg),
                getString(R.string.insufficient_money_msg),
                getString(R.string.button_close)
            ).show(requireActivity())
        }
    }

    private fun setNetworkState(hasInternet: Boolean) {
        binding.previewNetworkStatusStub.isVisible = !hasInternet
        binding.previewOfflineGroup.isVisible = hasInternet
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

        if (this.inputCurrency == Constants.DASH_CURRENCY) {
            binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_dash_wallet_on_this_device)
            binding.contentOrderReview.outputAccountHintLabel.text = getString(R.string.to_external_address, "dummy-address-goes-here")
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
        loadingDialog = AdaptiveDialog.progress(getString(messageResId))
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    private fun showBuyOrderDialog(type: MayaResultDialog.Type, responseMessage: String? = null) {
        if (transactionStateDialog?.dialog?.isShowing == true) {
            transactionStateDialog?.dismissAllowingStateLoss()
        }

        transactionStateDialog = MayaResultDialog.newInstance(
            type,
            responseMessage,
            dashToCoinbase = swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY
        ).apply {
            this.onCoinBaseResultDialogButtonsClickListener = object : MayaResultDialog.CoinBaseResultDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: MayaResultDialog.Type) {
                    when (type) {
                        MayaResultDialog.Type.CONVERSION_ERROR -> {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_RETRY)
                            dismiss()
                            findNavController().popBackStack()
                        }
                        MayaResultDialog.Type.SWAP_ERROR -> {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_RETRY)
                            dismiss()
                            findNavController().popBackStack()
                            findNavController().popBackStack()
                        }
                        MayaResultDialog.Type.CONVERSION_SUCCESS -> {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_SUCCESS_CLOSE)
                            dismiss()
                            val navController = findNavController()
                            val home = navController.graph.startDestinationId
                            navController.popBackStack(home, false)
                        }
                        else -> {}
                    }
                }

                override fun onNegativeButtonClick(type: MayaResultDialog.Type) {
                    viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_CLOSE)
                }
            }
        }
        transactionStateDialog?.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isFirstTime) {
            viewModel.isFirstTime = false
            countDownTimer.start()
        } else {
            setRetryStatus()
        }
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

    private fun setRetryStatus() {
        binding.confirmBtn.text = getString(R.string.button_retry)
        binding.retryIcon.visibility = View.VISIBLE
        isRetrying = true
        setConfirmBtnStyle(R.style.PrimaryButtonTheme_Large_LightBlue, R.color.dash_blue)
    }

    private fun setConfirmBtnStyle(@StyleRes buttonStyle: Int, @ColorRes colorRes: Int) {
        binding.confirmBtnContainer.setRoundedBackground(buttonStyle)
        binding.confirmBtn.setTextColor(resources.getColor(colorRes))
    }

    private fun setupBackNavigation() {
        binding.toolbar.setNavigationOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_TOP_BACK)
            findNavController().popBackStack()
        }

        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_ANDROID_BACK)
            findNavController().popBackStack()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback?.remove()
    }
}
