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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.BuildConfig
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.SwapTradeUIModel
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.swapkit.SwapKitConstants
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewViewModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.MayaTransactionParams
import org.dash.wallet.integrations.maya.ui.dialogs.MayaResultDialog
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

/**
 * Maya order-preview screen (Figma node 24021:11223). Hosts the Compose
 * [MayaConversionPreviewScreen] while keeping the original behavior: the quote-expiry
 * countdown (shown in the Confirm button, turning it into Refresh on expiry), the cancel
 * confirmation dialog, order commit + result dialogs, the fee-info screen and the
 * offline state.
 */
@AndroidEntryPoint
class MayaConversionPreviewFragment : Fragment() {
    companion object {
        // How long a fetched quote is treated as valid before the user must refresh it.
        // TBC: the actual quote validity per network hasn't been confirmed yet, so both
        // use a conservative 10 seconds until real numbers are provided.
        private const val MAYA_QUOTE_EXPIRY_MS = 10_000L
        private const val NEAR_QUOTE_EXPIRY_MS = 10_000L
    }

    private val viewModel by viewModels<MayaConversionPreviewViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private val convertViewModel by mayaViewModels<ConvertViewViewModel>()

    @Inject
    lateinit var lockScreenBroadcaster: LockScreenBroadcaster

    private lateinit var mayaCurrencyMapper: MayaCurrencyMapper
    private var isRefreshing = false
    private var transactionStateDialog: MayaResultDialog? = null
    private var newSwapOrderId: String? = null
    private var onBackPressedCallback: OnBackPressedCallback? = null

    private var uiState by mutableStateOf(MayaConversionPreviewUIState())

    private var countDownTimer: CountDownTimer? = null

    /**
     * Starts the quote-expiry countdown for a freshly-fetched quote, stamping the fetch time in
     * the ViewModel's SavedStateHandle so the remaining validity survives process death.
     * The countdown is surfaced as "Confirm (Xs)" in the button label; the timer only tracks how
     * long the quote stays valid — nothing is sent automatically. When it runs out the Confirm
     * button turns into Refresh (see [setRefreshStatus]).
     */
    private fun startQuoteExpiryCountdown() {
        viewModel.quoteCreatedAt = System.currentTimeMillis()
        startCountdown(quoteExpiryMillis())
    }

    /** Runs the countdown for [durationMs] — the full window for a new quote, or what's left of it. */
    private fun startCountdown(durationMs: Long) {
        isRefreshing = false
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(durationMs, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                // Round up so the countdown reads 10, 9, 8… (the first tick fires
                // a few ms in, which would otherwise show 9 immediately).
                val secondsLeft = (millisUntilFinished + 999) / 1000
                uiState = uiState.copy(quoteSecondsLeft = secondsLeft)
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    MayaConversionPreviewScreen(
                        state = uiState,
                        onBackClick = {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_TOP_BACK)
                            findNavController().popBackStack()
                        },
                        onCancelClick = ::onCancelClick,
                        onConfirmClick = ::onConfirmClick,
                        onFeeInfoClick = {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_QUOTE_FEE_INFO)
                            safeNavigate(MayaConversionPreviewFragmentDirections.mayaOrderReviewToFeeInfo())
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackNavigation()
        mayaCurrencyMapper = MayaCurrencyMapper(requireContext())

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            uiState = uiState.copy(isOnline = hasInternet == true)
        }

        // Prefer the order persisted in the ViewModel's SavedStateHandle — it may be a refreshed
        // quote that replaced the original one — and fall back to the nav argument on a fresh
        // first launch. Covers the OS destroying and recreating this screen.
        val savedOrder = viewModel.savedSwapTradeUIModel
        val order = savedOrder ?: arguments?.let {
            MayaConversionPreviewFragmentArgs.fromBundle(it).swapModel
        }
        order?.apply {
            viewModel.swapTradeUIModel = this
            updateConversionPreviewUI()
        }

        viewModel.showLoading.observe(viewLifecycleOwner) { showLoading ->
            uiState = uiState.copy(isLoading = showLoading == true)
        }

        viewModel.commitSwapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.CONVERSION_ERROR, it)
        }

        viewModel.sellSwapSuccessState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.CONVERSION_SUCCESS)
        }

        viewModel.swapTradeFailureState.observe(viewLifecycleOwner) {
            showBuyOrderDialog(MayaResultType.SWAP_ERROR, it)
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            newSwapOrderId = it.swapTradeId
            viewModel.swapTradeUIModel = it
            // The fetch time was stamped by the ViewModel when the quote arrived, so the sticky
            // re-delivery of this LiveData after a configuration change resumes the remaining
            // validity window instead of restarting a full countdown.
            resumeQuoteCountdown()
            it.updateConversionPreviewUI()
        }

        viewModel.commitSwapTradeSuccessState.observe(viewLifecycleOwner) { params ->
            val walletName = if (viewModel.swapTradeUIModel.inputCurrency == Constants.DASH_CURRENCY) {
                mayaCurrencyMapper.getCurrencyName(viewModel.swapTradeUIModel.inputCurrency)
            } else {
                viewModel.swapTradeUIModel.outputCurrencyName
            }
            val transactionParams = MayaTransactionParams(
                params,
                TransactionType.SellSwap,
                walletName,
                viewModel.swapTradeUIModel.routeName
            )
            // Remember the result until the user acknowledges it: the lock screen auto-dismisses
            // the result sheet, and this lets us re-show it once the lock screen goes away.
            convertViewModel.pendingConversionResult = transactionParams
            safeNavigate(
                MayaConversionPreviewFragmentDirections.mayaOrderPreviewToOrderExecution(transactionParams)
            )
        }

        // Re-show the result sheet after an unlock if it's still pending — the lock screen
        // dismissed it (dialogs are torn down on lock), or the OS killed and restored the app
        // while it was locked.
        lockScreenBroadcaster.deactivatingLockScreen.observe(viewLifecycleOwner) {
            val pending = convertViewModel.pendingConversionResult
            if (pending != null &&
                findNavController().currentDestination?.id == R.id.mayaConversionPreviewFragment
            ) {
                safeNavigate(
                    MayaConversionPreviewFragmentDirections.mayaOrderPreviewToOrderExecution(pending)
                )
            }
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

    private fun onCancelClick() {
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

    private fun onConfirmClick() {
        countDownTimer?.cancel()
        if (isRefreshing) {
            getNewCommitOrder()
            isRefreshing = false
        } else {
            newSwapOrderId?.let { orderId ->
                viewModel.swapTradeUIModel.let {
                    viewModel.commitSwapTrade(orderId)
                }
            }
        }
    }

    /**
     * Map the raw provider string from [SwapTradeUIModel.routeName] (e.g. "maya-default",
     * "MAYACHAIN_STREAMING", "NEAR", or a comma-joined list) to the user-facing route label
     * shown in the Network row. Mirrors the MAYA-then-NEAR classification in
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

    /** Rebuilds the order card content from this quote — the Compose counterpart of the old view binding. */
    private fun SwapTradeUIModel.updateConversionPreviewUI() {
        newSwapOrderId = this.swapTradeId

        val isCurrencySymbolFirst = GenericUtils.isCurrencySymbolFirst()
        val inputAmount = this.amount.dash.setScale(8, RoundingMode.HALF_UP)
        val fiatDigits = GenericUtils.getCurrencyDigits()
        val fiatValue = this.amount.fiat.setScale(fiatDigits, RoundingMode.HALF_UP)
        val fiatSymbol = GenericUtils.currencySymbol(this.amount.fiatCode)

        val outputAmount = this.expectedOutputAmount.setScale(8, RoundingMode.HALF_UP)
        val outputCurrencyCode = this.amount.cryptoCode
        val toName = mayaCurrencyMapper.getCurrencyName(outputCurrencyCode)

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
        val digits = if (breakdownIsFiat) GenericUtils.getCurrencyDigits() else 8

        val purchaseAmount = if (this.maximum) {
            (this.amount.getValue(breakdownType) - this.feeAmount.getValue(breakdownType))
        } else {
            this.amount.getValue(breakdownType)
        }.setScale(digits, RoundingMode.HALF_UP)

        val feeAmount = this.feeAmount.getValue(breakdownType).setScale(digits, RoundingMode.HALF_UP)

        val totalAmount = if (this.maximum) {
            this.amount.getValue(breakdownType)
        } else {
            (this.amount.getValue(breakdownType) + this.feeAmount.getValue(breakdownType)).setScale(
                digits,
                RoundingMode.HALF_UP
            )
        }

        fun breakdownDisplay(value: BigDecimal): AmountDisplay = if (breakdownIsDash) {
            AmountDisplay(GenericUtils.toLocalizedString(value, true, currencySymbol), isDash = true)
        } else {
            formatWithCode(value, currencySymbol, isFiat = true, symbolFirst = isCurrencySymbolFirst)
        }

        uiState = uiState.copy(
            fromName = mayaCurrencyMapper.getCurrencyName(this.amount.dashCode),
            fromCode = this.amount.dashCode,
            fromIconUrl = GenericUtils.getCoinIcon(this.inputCurrency.lowercase(), SwapKitConstants.DASH_ASSET),
            fromDashAmount = GenericUtils.toLocalizedString(inputAmount, true, this.amount.dashCode),
            fromFiatAmount = formatWithCode(
                fiatValue,
                fiatSymbol,
                isFiat = true,
                symbolFirst = isCurrencySymbolFirst
            ).text,
            toName = toName,
            toCode = outputCurrencyCode,
            toIconUrl = GenericUtils.getCoinIcon(this.outputCurrency.lowercase(), this.outputAsset),
            toAmount = formatWithCode(
                outputAmount,
                outputCurrencyCode,
                isFiat = false,
                symbolFirst = isCurrencySymbolFirst
            ).text,
            addressLabel = getString(R.string.maya_address_input_hint, toName),
            address = this.destinationAddress.orEmpty(),
            purchaseAmount = breakdownDisplay(purchaseAmount),
            feeAmount = breakdownDisplay(feeAmount),
            totalAmount = breakdownDisplay(totalAmount),
            symbolFirst = isCurrencySymbolFirst,
            networkName = prettyRouteName(this.routeName),
            // Slippage disclosure: the estimate can move by up to the default slippage tolerance
            // before the swap settles. Driven by the same constant the quote requests use.
            slippageNotice = getString(R.string.maya_slippage_notice, SwapKitConstants.DEFAULT_SLIPPAGE_PERCENT),
            // Route diagnostics are dev-only; hidden in release builds.
            debugRouteInfo = if (BuildConfig.DEBUG) {
                "selected: ${this.routeName}\n\nall: ${this.availableRoutes}"
            } else {
                null
            }
        )
    }

    /** "0.0053 BTC" / "BTC 0.0053" (or "$ 1.00") depending on the locale's symbol position. */
    private fun formatWithCode(
        value: BigDecimal,
        currencyCode: String,
        isFiat: Boolean,
        symbolFirst: Boolean
    ): AmountDisplay {
        val valueString = GenericUtils.toLocalizedString(value, !isFiat, currencyCode)
        return AmountDisplay(
            getString(
                R.string.fiat_balance_with_currency,
                if (symbolFirst) currencyCode else valueString,
                if (symbolFirst) valueString else currencyCode
            )
        )
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
        if (viewModel.quoteCreatedAt == null) {
            // First display of this quote: start the full countdown (and stamp the fetch time).
            startQuoteExpiryCountdown()
        } else {
            // Coming back to the screen (pause/resume, config change or process death): resume
            // whatever is left of the quote's validity window, or mark it expired so the
            // Refresh button shows.
            resumeQuoteCountdown()
        }
    }

    /** Resumes what is left of the current quote's validity window, or shows Refresh if it's gone. */
    private fun resumeQuoteCountdown() {
        val createdAt = viewModel.quoteCreatedAt ?: return
        val remaining = quoteExpiryMillis() - (System.currentTimeMillis() - createdAt)
        if (remaining > 0) {
            startCountdown(remaining)
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

    /** Quote expired: the Confirm button becomes Refresh until a new quote is fetched. */
    private fun setRefreshStatus() {
        isRefreshing = true
        uiState = uiState.copy(quoteSecondsLeft = null)
    }

    private fun setupBackNavigation() {
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
