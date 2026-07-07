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

import android.annotation.SuppressLint
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
import androidx.core.view.isGone
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
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.BuildConfig
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentMayaConversionPreviewBinding
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.dash.wallet.integrations.maya.ui.convert_currency.model.MayaTransactionParams
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog
import java.math.BigDecimal
import java.math.RoundingMode

@AndroidEntryPoint
class MayaConversionPreviewFragment : Fragment(R.layout.fragment_maya_conversion_preview) {
    companion object {
        // How long a fetched quote is treated as valid before the user must refresh it.
        // TBC: the actual quote validity per network hasn't been confirmed yet, so both
        // use a conservative 10 seconds until real numbers are provided.
        private const val MAYA_QUOTE_EXPIRY_MS = 10_000L
        private const val NEAR_QUOTE_EXPIRY_MS = 10_000L
    }

    private val binding by viewBinding(FragmentMayaConversionPreviewBinding::bind)
    private val viewModel by viewModels<MayaConversionPreviewViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private lateinit var mayaCurrencyMapper: MayaCurrencyMapper
    private var isRefreshing = false
    private var transactionStateDialog: MayaResultDialog? = null
    private var newSwapOrderId: String? = null
    private var onBackPressedCallback: OnBackPressedCallback? = null
    private var networkStatusView: View? = null

    private var countDownTimer: CountDownTimer? = null

    /**
     * (Re)starts the quote-expiry countdown. The timer only tracks how long the quote
     * stays valid — nothing is sent automatically; the label spells that out so the
     * ticking clock isn't mistaken for an auto-send. When it runs out the Confirm
     * button turns into Refresh (see [setRefreshStatus]).
     */
    private fun startQuoteExpiryCountdown() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(quoteExpiryMillis(), 1000) {

            override fun onTick(millisUntilFinished: Long) {
                // Round up so the countdown reads 10, 9, 8… (the first tick fires
                // a few ms in, which would otherwise show 9 immediately).
                val secondsLeft = (millisUntilFinished + 999) / 1000
                binding.quoteExpiryLabel.text = getString(R.string.maya_quote_expires_in, secondsLeft.toString())
                binding.confirmBtn.text = getString(R.string.button_confirm)
                binding.confirmProgress.isGone = true
                binding.retryIcon.visibility = View.GONE
                setConfirmBtnStyle(
                    org.dash.wallet.common.R.style.PrimaryButtonTheme_Large_Blue,
                    org.dash.wallet.common.R.color.dash_white
                )
            }

            override fun onFinish() {
                setRefreshStatus()
            }
        }.start()
    }

    private fun quoteExpiryMillis(): Long {
        return if (viewModel.swapTradeUIModel.routeName?.contains("NEAR", ignoreCase = true) == true) {
            NEAR_QUOTE_EXPIRY_MS
        } else {
            MAYA_QUOTE_EXPIRY_MS
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackNavigation()
        mayaCurrencyMapper = MayaCurrencyMapper(requireContext())

        // Slippage disclosure: the estimate can move by up to the default slippage tolerance
        // before the swap settles. Driven by the same constant the quote requests use.
        binding.contentOrderReview.slippageNotice.text = getString(
            R.string.maya_slippage_notice,
            SwapKitConstants.DEFAULT_SLIPPAGE_PERCENT
        )

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
            countDownTimer?.cancel()
            if (isRefreshing) {
                binding.confirmProgress.indeterminateTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    R.color.dash_blue
                )
                getNewCommitOrder()
                isRefreshing = false
            } else {
                binding.confirmProgress.indeterminateTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    org.dash.wallet.common.R.color.dash_white
                )
                newSwapOrderId?.let { orderId ->
                    viewModel.swapTradeUIModel.let {
                        viewModel.commitSwapTrade(orderId)
                    }
                }
            }
        }

        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            binding.cancelBtn.isEnabled = !showLoading
            binding.confirmProgress.isGone = !showLoading
            binding.retryIcon.isGone = showLoading || !isRefreshing
            binding.confirmBtnContainer.isEnabled = !showLoading
            binding.confirmBtnContainer.alpha = if (showLoading) 0.6f else 1.0f
        }

        viewModel.commitSwapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.CONVERSION_ERROR, it)
        }

        viewModel.sellSwapSuccessState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.CONVERSION_SUCCESS)
        }

        binding.contentOrderReview.mayaFeeInfoContainer.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_FEE_INFO)
            safeNavigate(MayaConversionPreviewFragmentDirections.mayaOrderReviewToFeeInfo())
        }

        viewModel.swapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.SWAP_ERROR, it)
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            newSwapOrderId = it.swapTradeId
            viewModel.swapTradeUIModel = it
            startQuoteExpiryCountdown()
            it.updateConversionPreviewUI()
        }

        viewModel.commitSwapTradeSuccessState.observe(viewLifecycleOwner) { params ->
            val walletName = if (viewModel.swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY) {
                mayaCurrencyMapper.getCurrencyName(viewModel.swapTradeUIModel.inputCurrency)
            } else {
                viewModel.swapTradeUIModel.outputCurrencyName
            }
            safeNavigate(
                MayaConversionPreviewFragmentDirections.mayaOrderPreviewToOrderExecution(
                    MayaTransactionParams(
                        params,
                        TransactionType.SellSwap,
                        walletName,
                        viewModel.swapTradeUIModel.routeName
                    )
                )
            )
        }

        observeNavigationCallBack()

        viewModel.onInsufficientMoneyCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.insufficient_money_title),
                getString(R.string.insufficient_money_msg),
                getString(R.string.button_close)
            ).show(requireActivity())
        }
    }

    /**
     * Map the raw provider string from [SwapTradeUIModel.routeName] (e.g. "maya-default",
     * "MAYACHAIN_STREAMING", "NEAR", or a comma-joined list) to the user-facing route label
     * shown in the currency picker. Mirrors the MAYA-then-NEAR classification in
     * [org.dash.wallet.integrations.maya.swapkit.SwapKitApiAggregator]; falls back to the raw
     * value for anything unrecognised.
     */
    private fun prettyRouteName(routeName: String?): String {
        val raw = routeName?.trim().orEmpty()
        return when {
            raw.isEmpty() -> getString(R.string.maya_route_label_maya)
            raw.contains("MAYA", ignoreCase = true) -> getString(R.string.maya_route_label_maya)
            raw.contains("NEAR", ignoreCase = true) -> getString(R.string.maya_route_label_near)
            else -> raw
        }
    }

    private fun setNetworkState(hasInternet: Boolean) {
        if (!hasInternet) {
            if (networkStatusView == null) {
                networkStatusView = binding.previewNetworkStatusStub.inflate()
            }
            networkStatusView?.isVisible = true
        } else {
            networkStatusView?.isVisible = false
        }
        binding.previewOfflineGroup.isVisible = hasInternet
    }

    @SuppressLint("SetTextI18n")
    private fun SwapTradeUIModel.updateConversionPreviewUI() {
        newSwapOrderId = this.swapTradeId

        binding.contentOrderReview.inputAccountTitle.text = this.amount.dashCode
        binding.contentOrderReview.convertOutputTitle.text = this.amount.cryptoCode

        binding.contentOrderReview.inputAccountSubtitle.text = mayaCurrencyMapper.getCurrencyName(this.amount.dashCode)
        binding.contentOrderReview.convertOutputSubtitle.text = mayaCurrencyMapper.getCurrencyName(
            this.amount.cryptoCode
        )

        binding.contentOrderReview.inputAccountHintLabel.setText(R.string.from_dash_wallet_on_this_device)
        binding.contentOrderReview.outputAccountHintLabel.text = getString(
            R.string.to_external_address,
            this.destinationAddress
        )

        val isCurrencyCodeFirst = GenericUtils.isCurrencySymbolFirst()
        val inputCurrencySymbol = GenericUtils.currencySymbol(this.inputCurrency)
        val inputAmount = this.amount.dash.setScale(8, RoundingMode.HALF_UP)

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.inputAccount,
            inputAmount,
            inputCurrencySymbol,
            isCurrencyCodeFirst,
            true,
            false
        )

        val outputAmount = this.expectedOutputAmount.setScale(8, RoundingMode.HALF_UP)
        val outputCurrency = this.amount.cryptoCode

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.outputAccount,
            outputAmount,
            outputCurrency,
            isCurrencyCodeFirst,
            false,
            false
        )

        // The swap fee is charged in DASH on top of the amount being sold — it never
        // changes how much of the receiving currency arrives (that's the "To" row above,
        // straight from the quote). If the user typed the amount denominated in the
        // receiving crypto, showing purchase ± fee in that crypto would misstate what
        // they receive, so the purchase/fee/total breakdown falls back to DASH.
        val breakdownType = if (this.amount.anchoredType == CurrencyInputType.Crypto) {
            CurrencyInputType.Dash
        } else {
            this.amount.anchoredType
        }
        val breakdownIsFiat = breakdownType == CurrencyInputType.Fiat
        val breakdownIsDash = breakdownType == CurrencyInputType.Dash
        val currencySymbol = GenericUtils.currencySymbol(
            if (breakdownIsFiat) this.amount.fiatCode else this.amount.dashCode
        )
        val digits = if (breakdownIsFiat) {
            GenericUtils.getCurrencyDigits()
        } else {
            8
        }
        val purchaseAmount = if (this.maximum) {
            (this.amount.getValue(breakdownType) - this.feeAmount.getValue(breakdownType))
        } else {
            this.amount.getValue(breakdownType)
        }.setScale(digits, RoundingMode.HALF_UP)

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.purchaseAmount,
            purchaseAmount,
            currencySymbol,
            isCurrencyCodeFirst,
            breakdownIsDash,
            breakdownIsFiat
        )

        val feeAmount = this.feeAmount.getValue(breakdownType).setScale(digits, RoundingMode.HALF_UP)

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.mayaFeeAmount,
            feeAmount,
            currencySymbol,
            isCurrencyCodeFirst,
            breakdownIsDash,
            breakdownIsFiat
        )

        val totalAmount = if (this.maximum) {
            this.amount.getValue(breakdownType)
        } else {
            (this.amount.getValue(breakdownType) + this.feeAmount.getValue(breakdownType)).setScale(
                digits,
                RoundingMode.HALF_UP
            )
        }

        setValueWithCurrencyCodeOrSymbol(
            binding.contentOrderReview.totalAmount,
            totalAmount,
            currencySymbol,
            isCurrencyCodeFirst,
            breakdownIsDash,
            breakdownIsFiat
        )
        binding.contentOrderReview.inputAccountIcon
            .load(GenericUtils.getCoinIcon(this.inputCurrency.lowercase(), SwapKitConstants.DASH_ASSET)) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(org.dash.wallet.common.R.drawable.ic_default_flag)
                transformations(CircleCropTransformation())
            }

        binding.contentOrderReview.convertOutputIcon
            .load(GenericUtils.getCoinIcon(this.outputCurrency.lowercase(), this.outputAsset)) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(org.dash.wallet.common.R.drawable.ic_default_flag)
                transformations(CircleCropTransformation())
            }

        binding.contentOrderReview.networkValue.text = prettyRouteName(this.routeName)

        // Route diagnostics are dev-only; hidden in release builds.
        if (BuildConfig.DEBUG) {
            val routeName = this.routeName
            val routes = this.availableRoutes
            binding.contentOrderReview.orderInfo.isVisible = true
            binding.contentOrderReview.orderInfo.text = """
                selected: $routeName

                all: $routes
            """.trimIndent()
        } else {
            binding.contentOrderReview.orderInfo.isVisible = false
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
                } else {
                    spannableString = SpannableString("$valueString  ")
                    val len = spannableString.length
                    spannableString.setSpan(it, len - 1, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        }
        textView.text = spannableString
    }

    private fun showBuyOrderDialog(type: MayaResultType, responseMessage: String? = null) {
        if (transactionStateDialog?.dialog?.isShowing == true) {
            transactionStateDialog?.dismissAllowingStateLoss()
        }

        transactionStateDialog = MayaResultDialog.newInstance(
            type,
            responseMessage,
            viewModel.swapTradeUIModel.inputCurrency,
            destinationCurrency = viewModel.swapTradeUIModel.outputCurrency
        ).apply {
            this.onMayaResultDialogButtonsClickListener =
                object : MayaResultDialog.MayaBaseResultDialogButtonsClickListener {
                    override fun onPositiveButtonClick(type: MayaResultType) {
                        when (type) {
                            MayaResultType.CONVERSION_ERROR -> {
                                // viewModel.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_RETRY)
                                dismiss()
                                findNavController().popBackStack()
                            }
                            MayaResultType.SWAP_ERROR -> {
                                // viewModel.logEvent(AnalyticsConstants.Maya.CONVERT_ERROR_RETRY)
                                dismiss()
                                findNavController().popBackStack()
                                findNavController().popBackStack()
                            }
                            MayaResultType.CONVERSION_SUCCESS -> {
                                // viewModel.logEvent(AnalyticsConstants.Maya.CONVERT_SUCCESS_CLOSE)
                                dismiss()
                                val navController = findNavController()
                                val home = navController.graph.startDestinationId
                                navController.popBackStack(home, false)
                            }
                            else -> {}
                        }
                    }

                    override fun onNegativeButtonClick(type: MayaResultType) {
                        viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_ERROR_CLOSE)
                    }
                }
        }
        transactionStateDialog?.showNow(parentFragmentManager, "MayaResultDialog")
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.isFirstTime) {
            viewModel.isFirstTime = false
            startQuoteExpiryCountdown()
        } else {
            setRefreshStatus()
        }
    }

    override fun onPause() {
        countDownTimer?.cancel()
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

    private fun setRefreshStatus() {
        binding.quoteExpiryLabel.text = getString(R.string.maya_quote_expired)
        binding.confirmBtn.text = getString(R.string.button_refresh)
        binding.confirmProgress.isGone = true
        binding.retryIcon.visibility = View.VISIBLE
        isRefreshing = true
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
