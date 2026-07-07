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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.Account
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Balance
import org.dash.wallet.integrations.maya.model.CurrencyInputType
import org.dash.wallet.integrations.maya.model.getCoinBaseExchangeRateConversion
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewViewModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapRequest
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapValueErrorType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.UUID

/**
 * Maya sell "Convert Dash to <crypto>" enter-amount screen (Figma node 24021:10970).
 *
 * Hosts the Compose [MayaConvertCryptoScreen] while keeping the original amount anchoring,
 * conversion and formatting logic from [ConvertViewViewModel] (previously driven by the
 * view-based ConvertViewFragment/ConverterView pair).
 */
@AndroidEntryPoint
class MayaConvertCryptoFragment : Fragment() {
    private val viewModel by viewModels<MayaConvertCryptoViewModel>()
    private val convertViewModel by mayaViewModels<ConvertViewViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private val args by navArgs<MayaConvertCryptoFragmentArgs>()

    private var selectedCoinBaseAccount: AccountDataUIModel? = null

    private var uiState by mutableStateOf(MayaConvertCryptoUIState())

    private val decimalSeparator =
        DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator

    // Amount-entry state ported from the old ConvertViewFragment keyboard listener.
    private var maxAmountSelected: Boolean = false
    private var canContinue: Boolean = false

    // Hard gate on Get quote independent of the entered value — set false when the wallet has no
    // DASH to convert, so the button stays disabled no matter what the user types.
    private var inputEnabled: Boolean = true
    private var currencyOptions: List<String> = emptyList()
    private var pickedCurrencyIndex: Int = 0
    private val pickedCurrencyOption: String
        get() = currencyOptions.getOrNull(pickedCurrencyIndex) ?: ""

    // Last crypto amount pair (value, currency code) used for the receive-amount line.
    private var lastCryptoAmount: Pair<String, String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setupState()
        setupObservers()

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MayaConvertCryptoScreen(
                    state = uiState,
                    onBackClick = {
                        convertViewModel.reset()
                        findNavController().popBackStack()
                    },
                    onMaxClick = ::onMaxClick,
                    onCurrencySelected = ::onCurrencySelected,
                    onKeyInput = ::onKeyInput,
                    onContinueClick = {
                        if (!uiState.isProcessing) {
                            convertViewModel.continueSwap(pickedCurrencyOption)
                        }
                    }
                )
            }
        }
    }

    private fun setupState() {
        val poolInfo = mayaViewModel.getPoolInfo(args.currency)
        val dashPoolInfo = mayaViewModel.getPoolInfo(Constants.DASH_CURRENCY)
        val currencyMapper = MayaCurrencyMapper(requireContext())

        // Tokens are qualified with their host network ("USDT (Ethereum)"); native L1 coins
        // (BTC.BTC, …) show just the code.
        val network = MayaCurrencyList.networkName(args.asset)
        val displayCode = if (network != null) "${args.currency} ($network)" else args.currency

        uiState = uiState.copy(
            // "Convert DASH to <code>" per design, e.g. "Convert DASH to USDT (Ethereum)".
            title = getString(R.string.maya_address_input_title, displayCode),
            toCurrencyName = currencyMapper.getCurrencyName(args.currency),
            toAddress = getArgAddress(),
            toIconUrls = GenericUtils.getCoinIconUrls(args.currency.lowercase(), args.asset)
        )

        convertViewModel.setOnSwapDashFromToCryptoClicked(true)
        convertViewModel.destinationAddress = getArgAddress()

        convertViewModel.setSelectedCryptoCurrency(
            AccountDataUIModel(
                Account(
                    UUID.nameUUIDFromBytes(args.currency.toByteArray()),
                    args.currency,
                    args.currency,
                    args.asset,
                    Balance("0", args.currency),
                    true,
                    true,
                    "Wallet",
                    true
                ),
                BigDecimal.ONE.setScale(16, RoundingMode.HALF_UP) /
                    (poolInfo?.assetPriceFiat?.toBigDecimal() ?: BigDecimal.ONE),
                BigDecimal.ONE.setScale(16, RoundingMode.HALF_UP) /
                    (dashPoolInfo?.assetPriceFiat?.toBigDecimal() ?: BigDecimal.ONE),
                BigDecimal.ONE.setScale(16, RoundingMode.HALF_UP)
            )
        )

        convertViewModel.destinationCurrency = args.currency
        viewModel.paymentIntent = args.paymentIntent
        convertViewModel.setSelectedAsset(args.asset)
    }

    private fun setupObservers() {
        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            uiState = uiState.copy(isOnline = hasInternet == true)
        }

        convertViewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) { account ->
            selectedCoinBaseAccount = account
            maxAmountSelected = false
            resetViewSelection(account)
        }

        convertViewModel.dashToCrypto.observe(viewLifecycleOwner) {
            convertViewModel.selectedCryptoCurrencyAccount.value?.let { account ->
                resetViewSelection(account)
            }
        }

        convertViewModel.onContinueEvent.observe(viewLifecycleOwner) { request ->
            proceedWithSwap(request)
        }

        // While the quote is being fetched, block all amount input so a late key press
        // can't alter the value carried to the preview.
        viewModel.showLoading.observe(viewLifecycleOwner) { loading ->
            uiState = uiState.copy(isProcessing = loading == true)
            updateContinueEnabled()
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) { swapTrade ->
            lifecycleScope.launch {
                // The quote succeeded: the ViewModel just dropped showLoading, but this handler
                // still refreshes inbound addresses and builds the payment intent before
                // navigating. Keep Get quote disabled for that whole stretch (it happens in the
                // same frame as the showLoading=false observer, so the button never flashes
                // enabled); re-enable only on the error paths that keep the user on this screen.
                uiState = uiState.copy(isProcessing = true)
                updateContinueEnabled()
                fun failToRetry() {
                    uiState = uiState.copy(isProcessing = false)
                    updateContinueEnabled()
                }

                val dashInbound = try {
                    mayaViewModel.refreshInboundAddresses()
                    mayaViewModel.isTradingActive()
                } catch (e: Exception) {
                    failToRetry()
                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(R.string.error),
                        getString(R.string.something_wrong_title),
                        getString(R.string.button_close)
                    ).show(requireActivity())
                    return@launch
                }

                if (!dashInbound) {
                    failToRetry()
                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(R.string.error),
                        getString(R.string.maya_error_trading_halted, "DASH"),
                        getString(R.string.button_close)
                    ).show(requireActivity())
                    return@launch
                }

                val paymentIntent = try {
                    viewModel.getUpdatedPaymentIntent(
                        convertViewModel.enteredConvertDashAmount.value!!,
                        Address.fromBase58(viewModel.networkParameters, swapTrade.vaultAddress)
                    )
                } catch (e: Exception) {
                    failToRetry()
                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(R.string.error),
                        getString(R.string.something_wrong_title),
                        getString(R.string.button_close)
                    ).show(requireActivity())
                    return@launch
                } ?: run {
                    failToRetry()
                    return@launch
                }

                safeNavigate(
                    MayaConvertCryptoFragmentDirections
                        .mayaConvertCryptoFragmentToMayaConversionPreviewFragment(
                            swapTrade,
                            convertViewModel.destinationCurrency!!,
                            paymentIntent
                        )
                )
            }
        }

        viewModel.swapTradeFailedCallback.observe(viewLifecycleOwner) {
            // An amount-too-low error (SwapKit's `noRoutesFound`, Maya's "amount too low")
            // shouldn't pop a modal — surface it in the same inline red error the local
            // min-amount check uses, so the user can simply raise the amount and retry without
            // dismissing a dialog. The active backend's aggregator classifies and localizes the
            // error (see SwapProvider): Maya's amount-too-low keeps its "below the allowed
            // minimum" copy, while SwapKit's noRoutesFound — which can also mean the route is
            // briefly unavailable — gets the same neutral no-route message the DEX buy screens show.
            if (!it.isNullOrBlank() && viewModel.isAmountTooLowError(it)) {
                uiState = uiState.copy(errorMessage = getString(viewModel.errorMessageRes(it)))
                return@observe
            }

            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                getString(viewModel.errorMessageRes(it), args.currency),
                getString(R.string.button_close)
            ).show(requireActivity())
        }

        convertViewModel.userDashAccountEmptyError.observe(viewLifecycleOwner) {
            // No DASH to convert: surface it as a toast (not a blocking dialog) and keep Get quote
            // disabled so the user can't proceed regardless of what they type.
            Toast.makeText(requireContext(), R.string.dont_have_any_dash, Toast.LENGTH_LONG).show()
            inputEnabled = false
            updateContinueEnabled()
        }

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            updateBalanceDisplay()
        }

        convertViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner) {
            updateReceiveAmount()
        }

        convertViewModel.enteredConvertFiatAmount.observe(viewLifecycleOwner) {
            updateReceiveAmount()
        }

        convertViewModel.enteredConvertCryptoAmount.observe(viewLifecycleOwner) { amount ->
            lastCryptoAmount = amount
            updateReceiveAmount()
        }

        viewModel.dashWalletBalance.observe(viewLifecycleOwner) {
            updateBalanceDisplay()
        }

        convertViewModel.validSwapValue.observe(viewLifecycleOwner) {
            uiState = uiState.copy(errorMessage = null)
        }
    }

    // ── Amount display (ported from ConvertViewFragment / ConverterView) ──────────

    /**
     * Re-derives the picker options and the displayed amount from the anchored value.
     * Options are anchored in the fixed order DASH / fiat / crypto for the sell direction.
     */
    private fun resetViewSelection(account: AccountDataUIModel?) {
        account?.coinbaseAccount?.currency?.let { currencyCode ->
            currencyOptions = if (convertViewModel.dashToCrypto.value == true) {
                listOf(Constants.DASH_CURRENCY, convertViewModel.selectedLocalCurrencyCode, currencyCode)
            } else {
                listOf(currencyCode, convertViewModel.selectedLocalCurrencyCode, Constants.DASH_CURRENCY)
            }
            convertViewModel.enteredConvertAmount = GenericUtils.toLocalizedString(
                convertViewModel.amount.anchoredValue,
                convertViewModel.amount.anchoredType != CurrencyInputType.Fiat,
                convertViewModel.amount.anchoredCurrencyCode
            )
            convertViewModel.selectedPickerCurrencyCode = convertViewModel.amount.anchoredCurrencyCode
            pickedCurrencyIndex = when (convertViewModel.amount.anchoredType) {
                CurrencyInputType.Dash -> 0
                CurrencyInputType.Fiat -> 1
                CurrencyInputType.Crypto -> 2
            }
            uiState = uiState.copy(
                currencyOptions = currencyOptions,
                selectedCurrencyIndex = pickedCurrencyIndex
            )
            applyNewValue(convertViewModel.enteredConvertAmount, pickedCurrencyOption, isLocalized = true)
        }
    }

    private fun onCurrencySelected(index: Int) {
        if (uiState.isProcessing) return
        pickedCurrencyIndex = index
        uiState = uiState.copy(selectedCurrencyIndex = index)
        val option = pickedCurrencyOption
        setAmountValue(option)
        convertViewModel.selectedPickerCurrencyCode = option
    }

    private fun setAmountValue(option: String) {
        val value = convertViewModel.getAmountValue(option)
        convertViewModel.amount.setAnchoredType(option)
        val display = formatAmountForDisplay(option, value, isLocalized = false, isEditing = false)
        convertViewModel.enteredConvertAmount = display
        uiState = uiState.copy(displayAmount = display)
    }

    private fun onMaxClick() {
        if (uiState.isProcessing) return
        convertViewModel.selectedCryptoCurrencyAccount.value?.let { userAccountData ->
            convertViewModel.getMaxAmount()?.let { maxAmount ->
                val cryptoCurrency = userAccountData.coinbaseAccount.currency

                if (convertViewModel.selectedPickerCurrencyCode == cryptoCurrency) {
                    applyNewValue(
                        maxAmount.crypto.toString(),
                        convertViewModel.selectedPickerCurrencyCode,
                        isLocalized = false
                    )
                } else {
                    val cleanedValue =
                        if (convertViewModel.selectedPickerCurrencyCode ==
                            convertViewModel.selectedLocalCurrencyCode
                        ) {
                            maxAmount.fiat
                        } else {
                            maxAmount.dash
                        }.toString()

                    applyNewValue(cleanedValue, convertViewModel.selectedPickerCurrencyCode, isLocalized = false)
                }

                maxAmountSelected = true
            }
        }
    }

    // ── Keypad input (ported from the old NumericKeyboardView listener) ───────────

    private fun onKeyInput(key: String) {
        if (uiState.isProcessing || !uiState.isOnline) return
        when (key) {
            "back" -> onBackspace(longClick = false)
            "back_long" -> onBackspace(longClick = true)
            "." -> onDecimalSeparatorKey()
            else -> key.toIntOrNull()?.let { onDigit(it) }
        }
    }

    /** The raw value currently displayed; empty when the display shows the "0" placeholder. */
    private fun currentValue(): StringBuilder {
        val value = StringBuilder()
        if (uiState.displayAmount != "0") {
            value.append(uiState.displayAmount)
        }
        return value
    }

    private fun onDigit(number: Int) {
        val value = currentValue()
        val isFraction = value.toString().indexOf(decimalSeparator) > -1

        if (isFraction) {
            val lengthOfDecimalPart = value.toString().length - value.toString().indexOf(decimalSeparator)
            val decimalsThreshold =
                if (convertViewModel.selectedLocalCurrencyCode == pickedCurrencyOption) {
                    GenericUtils.getCurrencyDigits()
                } else {
                    8
                }

            if (lengthOfDecimalPart > decimalsThreshold) {
                return
            }
        }

        if (!maxAmountSelected) {
            try {
                appendIfValidAfter(value, number.toString())
                applyNewValue(value.toString(), pickedCurrencyOption, isLocalized = true, isEditing = true)
            } catch (x: Exception) {
                value.deleteCharAt(value.length - 1)
                applyNewValue(value.toString(), pickedCurrencyOption, isLocalized = true, isEditing = true)
            }
        }
    }

    private fun appendIfValidAfter(value: StringBuilder, number: String) {
        try {
            value.append(number)
            val formattedValue = GenericUtils.formatFiatWithoutComma(value.toString())
            Coin.parseCoin(formattedValue)
        } catch (e: Exception) {
            value.deleteCharAt(value.length - 1)
        }
    }

    private fun onBackspace(longClick: Boolean) {
        val value = currentValue()
        if (longClick || maxAmountSelected) {
            value.clear()
        } else if (value.isNotEmpty()) {
            value.deleteCharAt(value.length - 1)
            convertViewModel.resetSwapValueError()
        }
        applyNewValue(value.toString(), pickedCurrencyOption, isLocalized = true, isEditing = true)
        maxAmountSelected = false
    }

    private fun onDecimalSeparatorKey() {
        if (maxAmountSelected) {
            return
        }
        val value = currentValue()
        if (value.indexOf(decimalSeparator.toString()) == -1) {
            if (value.isEmpty()) {
                value.append("0")
            }
            value.append(decimalSeparator)
        }
        applyNewValue(value.toString(), pickedCurrencyOption, isLocalized = true, isEditing = true)
    }

    private fun applyNewValue(value: String, currencyCode: String, isLocalized: Boolean, isEditing: Boolean = false) {
        val newValue = value.ifEmpty { "0" }
        convertViewModel.setEnteredAmount(newValue, isLocalized)

        val display = formatAmountForDisplay(currencyCode, newValue, isLocalized, isEditing)
        convertViewModel.enteredConvertAmount = display
        uiState = uiState.copy(displayAmount = display)

        val isNonZero = newValue.isNotEmpty() &&
            (newValue.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        convertViewModel.updateAmounts()
        canContinue = isNonZero
        updateContinueEnabled()
    }

    /**
     * Formats the amount for display in the picked currency — same rules the old
     * ConvertViewFragment.setAmountViewInfo applied: raw string while typing; otherwise
     * [ConvertViewViewModel.cryptoFormat] for DASH/crypto and [ConvertViewViewModel.fiatFormat]
     * (at the fiat's digit count) for fiat.
     */
    private fun formatAmountForDisplay(
        currencyCode: String,
        value: String,
        isLocalized: Boolean,
        isEditing: Boolean
    ): String {
        if (isEditing) {
            return value
        }
        val amountBG = GenericUtils.toScaledBigDecimal(value, isLocalized)
        return when (currencyCode) {
            Constants.DASH_CURRENCY -> convertViewModel.cryptoFormat.format(amountBG)
            convertViewModel.selectedLocalCurrencyCode -> {
                val digits = GenericUtils.getCurrencyDigits()
                convertViewModel.fiatFormat.format(amountBG.setScale(digits, RoundingMode.HALF_UP))
            }
            else -> convertViewModel.cryptoFormat.format(amountBG)
        }
    }

    private fun updateContinueEnabled() {
        uiState = uiState.copy(
            continueEnabled = canContinue && inputEnabled && !uiState.isProcessing
        )
    }

    // ── Derived display blocks ────────────────────────────────────────────────────

    /** Dash wallet balance row of the direction card: "Balance: 0.05 (Dash logo)" + fiat equivalent. */
    private fun updateBalanceDisplay() {
        val balance = viewModel.dashWalletBalance.value ?: Coin.ZERO
        val rate = convertViewModel.selectedLocalExchangeRate.value
        val fiatBalance = rate?.let {
            ExchangeRate(Coin.COIN, it.fiat).coinToFiat(balance).toFormattedString()
        }
        uiState = uiState.copy(
            dashBalance = GenericUtils.dashFormat.format(balance).toString(),
            fiatBalance = fiatBalance
        )
    }

    /**
     * "Receive amount / ~ 0.0053 BTC / using NEAR network" block. Shown once a non-zero DASH
     * amount has been entered; the route line mirrors the currency picker's route label and is
     * hidden when the selected asset's route isn't a single known provider.
     */
    private fun updateReceiveAmount() {
        val cryptoAmount = lastCryptoAmount
        val hasAmount = convertViewModel.enteredConvertDashAmount.value?.isZero == false &&
            cryptoAmount != null && cryptoAmount.second.isNotEmpty()

        if (!hasAmount || cryptoAmount == null) {
            uiState = uiState.copy(receiveAmount = null, networkLabel = null)
            return
        }

        val receiveAmount = Constants.PREFIX_ALMOST_EQUAL_TO + getString(
            R.string.fiat_balance_with_currency,
            cryptoAmount.first,
            GenericUtils.currencySymbol(cryptoAmount.second)
        )
        val routeResId = mayaViewModel.getRouteLabelResId(args.asset)
        val networkLabel = routeResId?.let {
            getString(R.string.maya_receive_using_network, getString(it))
        }
        uiState = uiState.copy(receiveAmount = receiveAmount, networkLabel = networkLabel)
    }

    // ── Swap flow (unchanged from the view-based implementation) ─────────────────

    private fun proceedWithSwap(request: SwapRequest, checkSendingConditions: Boolean = true) {
        if (request.cryptoAmount == null && request.amount != null) {
            showSwapValueErrorView(SwapValueErrorType.ExchangeRateMissing)
            return
        }

        val swapValueErrorType = convertViewModel.checkEnteredAmountValue(checkSendingConditions)
        lifecycleScope.launch {
            if (swapValueErrorType == SwapValueErrorType.NOError) {
                if (!request.dashToCrypto && convertViewModel.dashToCrypto.value == true) {
                    if (viewModel.getLastBalance() < (request.dashAmount ?: Coin.ZERO)) {
                        showNoAssetsError()
                    }
                } else {
                    if (request.amount != null && viewModel.isInputGreaterThanLimit(request.dashAmount)) {
                        showSwapValueErrorView(SwapValueErrorType.UnAuthorizedValue)
                    } else {
                        selectedCoinBaseAccount?.let {
                            viewModel.swapTrade(request, it, request.dashToCrypto)
                        }
                    }
                }
            } else {
                showSwapValueErrorView(swapValueErrorType)
            }
        }
    }

    private fun showSwapValueErrorView(swapValueErrorType: SwapValueErrorType) {
        val errorMessage = when (swapValueErrorType) {
            SwapValueErrorType.LessThanMin -> minAmountErrorMessage()
            SwapValueErrorType.MoreThanMax -> maxAmountErrorMessage()
            SwapValueErrorType.NotEnoughBalance -> getString(R.string.you_dont_have_enough_balance)
            SwapValueErrorType.UnAuthorizedValue -> getString(R.string.auth_limit_description)
            SwapValueErrorType.ExchangeRateMissing -> getString(R.string.exchange_rate_not_found)
            SwapValueErrorType.SendingConditionsUnmet -> {
                showMinimumBalanceWarning()
                null
            }
            else -> null
        }
        uiState = uiState.copy(errorMessage = errorMessage)
    }

    private fun showMinimumBalanceWarning() {
        MinimumBalanceDialog().show(requireActivity()) { isOkToContinue ->
            val request = convertViewModel.onContinueEvent.value

            if (isOkToContinue == true && request != null) {
                proceedWithSwap(request, false)
            }
        }
    }

    private fun maxAmountErrorMessage(): String? {
        if (convertViewModel.dashToCrypto.value == true) {
            viewModel.dashWalletBalance.value?.let { dash ->
                convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                    val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                    val fiatAmount = currencyRate.coinToFiat(dash).toFormattedString()
                    return "${getString(R.string.entered_amount_is_too_high)} $fiatAmount"
                }
            }
        } else {
            convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                selectedCoinBaseAccount?.getCoinBaseExchangeRateConversion(rate)?.first?.let {
                    return "${getString(R.string.entered_amount_is_too_high)} $it"
                }
            }
        }
        return getString(R.string.entered_amount_is_too_high)
    }

    private fun minAmountErrorMessage(): String? {
        convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
            selectedCoinBaseAccount?.currencyToDashExchangeRate?.let { _ ->
                val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                val fiatAmount = Fiat.parseFiat(
                    currencyRate.fiat.currencyCode,
                    convertViewModel.minAllowedSwapAmount
                )
                return "${getString(R.string.entered_amount_is_too_low)} ${fiatAmount.toFormattedString()}"
            }
        }
        return getString(R.string.entered_amount_is_too_low)
    }

    private fun getArgAddress(): String {
        return args.paymentIntent.outputs?.first().let { output ->
            val memoChunk = output?.script?.chunks?.get(1)!!
            var memo = String(memoChunk.data!!)
            val index = memo.indexOfLast { ch -> ch == ':' }
            memo = memo.substring(index + 1)
            memo
        }
    }

    private fun showNoAssetsError() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.we_didnt_find_any_assets),
            getString(R.string.you_dont_own_any_crypto),
            getString(R.string.button_close),
            getString(R.string.buy_crypto_on_coinbase)
        ).show(requireActivity()) { buyOnCoinbase ->
            if (buyOnCoinbase == true) {
                viewModel.logEvent(AnalyticsConstants.Coinbase.CONVERT_BUY_ON_COINBASE)
                openCoinbaseWebsite()
            }
        }
    }

    private fun openCoinbaseWebsite() {
        val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        defaultBrowser.data = Uri.parse(getString(R.string.coinbase_website))
        startActivity(defaultBrowser)
    }

    override fun onDestroy() {
        super.onDestroy()
        convertViewModel.clear()
    }
}