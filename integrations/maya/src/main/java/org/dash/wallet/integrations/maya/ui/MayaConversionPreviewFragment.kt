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

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
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
import org.dash.wallet.common.ui.enter_amount.CenteredImageSpan
import org.dash.wallet.common.ui.setRoundedBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentMayaConversionPreviewBinding
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog
import java.math.BigDecimal
import java.math.RoundingMode

@AndroidEntryPoint
class MayaConversionPreviewFragment : Fragment(R.layout.fragment_maya_conversion_preview) {
    private val binding by viewBinding(FragmentMayaConversionPreviewBinding::bind)
    private val viewModel by viewModels<MayaConversionPreviewViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private lateinit var mayaCurrencyMapper: MayaCurrencyMapper
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
        mayaCurrencyMapper = MayaCurrencyMapper(requireContext())

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
                viewModel.swapTradeUIModel = this
            }
        }

        binding.confirmBtnContainer.setOnClickListener {
            countDownTimer.cancel()
            if (isRetrying) {
                getNewCommitOrder()
                isRetrying = false
            } else {
                newSwapOrderId?.let { orderId ->
                    viewModel.swapTradeUIModel.let {
                        AdaptiveDialog.create(
                            null,
                            "New Maya Order",
                            "This order will send (${it.amount.dash} + ${it.feeAmount.dash.setScale(
                                8,
                                RoundingMode.HALF_UP
                            )}) of ${it.inputCurrency} to ${
                            it.amount.crypto.setScale(8, RoundingMode.HALF_UP)
                            } of ${it.amount.cryptoCode} at ${it.destinationAddress}",
                            getString(R.string.button_okay)
                        ).show(requireActivity())
                        viewModel.commitSwapTrade(orderId)
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

        binding.contentOrderReview.mayaFeeInfoContainer.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_FEE_INFO)
            safeNavigate(MayaConversionPreviewFragmentDirections.mayaOrderReviewToFeeInfo())
        }

        viewModel.swapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultDialog.Type.SWAP_ERROR, it)
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            newSwapOrderId = it.swapTradeId
            countDownTimer.start()
        }

        viewModel.commitSwapTradeSuccessState.observe(viewLifecycleOwner) { params ->
            val walletName = if (viewModel.swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY) {
                mayaCurrencyMapper.getCurrencyName(viewModel.swapTradeUIModel.inputCurrency)
            } else {
                viewModel.swapTradeUIModel.outputCurrencyName
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

        binding.contentOrderReview.inputAccountTitle.text = this.amount.dashCode
        binding.contentOrderReview.convertOutputTitle.text = this.amount.cryptoCode

        binding.contentOrderReview.inputAccountSubtitle.text = mayaCurrencyMapper.getCurrencyName(this.amount.dashCode)
        binding.contentOrderReview.convertOutputSubtitle.text = mayaCurrencyMapper.getCurrencyName(
            this.amount.cryptoCode
        )

//        if (this.inputCurrency == Constants.DASH_CURRENCY) {
        binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_dash_wallet_on_this_device)
        binding.contentOrderReview.outputAccountHintLabel.text = getString(
            R.string.to_external_address,
            this.destinationAddress
        )
//        } else {
//            binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_your_coinbase_account)
//            binding.contentOrderReview.outputAccountHintLabel.setText(R.string.to_dash_wallet_on_this_device)
//        }

        val isCurrencyCodeFirst = GenericUtils.isCurrencySymbolFirst()
        val inputCurrencySymbol = GenericUtils.currencySymbol(this.inputCurrency)
        val inputAmount = this.amount.dash.setScale(8, RoundingMode.HALF_UP)

//        binding.contentOrderReview.inputAccount.text = getString(
//            R.string.fiat_balance_with_currency,
//            if (isCurrencyCodeFirst) inputCurrencySymbol else inputAmount,
//            if (isCurrencyCodeFirst) inputAmount else inputCurrencySymbol
//        )
        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.inputAccount,
            inputAmount,
            inputCurrencySymbol,
            isCurrencyCodeFirst,
            true,
            false
        )

        val outputAmount = this.amount.crypto.setScale(8, RoundingMode.HALF_UP)
        val outputCurrency = this.amount.cryptoCode

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.outputAccount,
            outputAmount,
            outputCurrency,
            isCurrencyCodeFirst,
            false,
            false
        )

        val currencySymbol = GenericUtils.currencySymbol(this.amount.anchoredCurrencyCode)
        val digits = if (this.feeAmount.anchoredType == CurrencyInputType.Fiat) {
            GenericUtils.getCurrencyDigits()
        } else {
            8
        }
        val purchaseAmount = this.amount.anchoredValue.setScale(digits, RoundingMode.HALF_UP)

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.purchaseAmount,
            purchaseAmount,
            currencySymbol,
            isCurrencyCodeFirst,
            amount.anchoredCurrencyCode == Constants.DASH_CURRENCY,
            amount.anchoredType == CurrencyInputType.Fiat
        )

        val feeCurrencySymbol = GenericUtils.currencySymbol(this.feeAmount.anchoredCurrencyCode)
        val feeAmount = this.feeAmount.anchoredValue.setScale(digits, RoundingMode.HALF_UP)

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.mayaFeeAmount,
            feeAmount,
            feeCurrencySymbol,
            isCurrencyCodeFirst,
            amount.anchoredCurrencyCode == Constants.DASH_CURRENCY,
            amount.anchoredType == CurrencyInputType.Fiat
        )

        val totalAmount = (this.amount.anchoredValue + this.feeAmount.anchoredValue).setScale(
            digits,
            RoundingMode.HALF_UP
        )

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.totalAmount,
            totalAmount,
            feeCurrencySymbol,
            isCurrencyCodeFirst,
            amount.anchoredCurrencyCode == Constants.DASH_CURRENCY,
            amount.anchoredType == CurrencyInputType.Fiat
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

    private fun setValueWithCurrencyCodeOrSymbol(
        textView: TextView,
        value: BigDecimal,
        currencyCode: String,
        isCurrencySymbolFirst: Boolean,
        isDash: Boolean,
        isFiat: Boolean,
        iconSize: Int = 12
    ) {
        val context = textView.context
        val scale = resources.displayMetrics.scaledDensity
        val valueString = GenericUtils.toLocalizedString(value, isDash || !isFiat, currencyCode)
        var spannableString = SpannableString(valueString) // Space for the icon

        // show Dash Icon if DASH is the primary currency
        if (isDash) {
            // TODO: adjust for dark mode
            val drawable =
                ContextCompat.getDrawable(context, org.dash.wallet.common.R.drawable.ic_dash_d_black)?.apply {
                    setBounds(0, 0, (iconSize * scale).toInt(), (iconSize * scale).toInt())
                }
            val imageSpan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                drawable?.let { ImageSpan(it, ImageSpan.ALIGN_CENTER) }
            } else {
                drawable?.let { CenteredImageSpan(it, textView.context) }
            }
            imageSpan?.let {
                if (GenericUtils.isCurrencySymbolFirst()) {
                    spannableString = SpannableString("  $valueString")
                    spannableString.setSpan(it, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // spannableString.setSpan(sizeSpan, 1, spannableString.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                } else {
                    spannableString = SpannableString("$valueString  ")
                    val len = spannableString.length
                    spannableString.setSpan(it, len - 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // spannableString.setSpan(sizeSpan, 0, len - 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
            }
        } else {
            spannableString = SpannableString(
                getString(
                    R.string.fiat_balance_with_currency,
                    if (isCurrencySymbolFirst) currencyCode else valueString,
                    if (isCurrencySymbolFirst) valueString else currencyCode
                )
            )
//            val roomLeft = maxTextWidth - textView.paint.measureText("$value")
//            val sizeRelative = if (roomLeft < 0) {
//                val ratio = min(1.0f, (maxTextWidth + roomLeft) / maxTextWidth)
//                if (ratio == Float.NEGATIVE_INFINITY) {
//                    1.0f
//                } else {
//                    ratio
//                }
//            } else {
//                1.0f
//            }
            // log.info("resizing number: {} to {}", text, sizeRelative)
            // val sizeSpan = RelativeSizeSpan(sizeRelative)
            // spannableString.setSpan(sizeSpan, 0, spannableString.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        textView.text = spannableString
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
            dashToCoinbase = viewModel.swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY
        ).apply {
            this.onCoinBaseResultDialogButtonsClickListener =
                object : MayaResultDialog.CoinBaseResultDialogButtonsClickListener {
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
        viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_RETRY)
        viewModel.swapTrade(viewModel.swapTradeUIModel)
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
