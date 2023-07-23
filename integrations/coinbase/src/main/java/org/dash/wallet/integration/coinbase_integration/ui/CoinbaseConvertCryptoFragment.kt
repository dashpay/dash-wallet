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

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseConvertCryptoBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.model.getCoinBaseExchangeRateConversion
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.ConvertViewFragment
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.*
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.crypto_wallets.CryptoWalletsDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseActivityViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseConvertCryptoViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.ConvertViewViewModel

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CoinbaseConvertCryptoFragment : Fragment(R.layout.fragment_coinbase_convert_crypto) {
    private val binding by viewBinding(FragmentCoinbaseConvertCryptoBinding::bind)
    private val viewModel by viewModels<CoinbaseConvertCryptoViewModel>()
    private var loadingDialog: AdaptiveDialog? = null
    private val convertViewModel by activityViewModels<ConvertViewViewModel>()
    private var cryptoWalletsDialog: CryptoWalletsDialog? = null
    private var selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel? = null
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(8).optionalDecimals()
    private val sharedViewModel: CoinbaseActivityViewModel by activityViewModels()

    private lateinit var fragment: ConvertViewFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.convertView.isSellSwapEnabled = false

        if (savedInstanceState == null) {
            fragment = ConvertViewFragment.newInstance()
            fragment.setViewDetails(getString(R.string.get_quote), null)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            fragment.handleNetworkState(hasInternet)
            binding.convertView.isEnabled = hasInternet
        }

        viewModel.showLoading.observe(
            viewLifecycleOwner
        ) {
            if (it) {
                showProgress(R.string.loading)
            } else {
                dismissProgress()
            }
        }

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) { rate ->
            rate?.let {
                binding.toolbarSubtitle.text = getString(
                    R.string.exchange_rate_template,
                    Coin.COIN.toPlainString(),
                    rate.fiat.toFormattedString()
                )
            }
        }

        convertViewModel.dashToCrypto.value?.let {
            if (it) {
                viewModel.dashWalletBalance.value?.let { dashInput ->
                    binding.convertView.dashInput = dashInput
                }
                binding.convertView.dashToCrypto = it
            }
        }
        convertViewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) { account ->
            selectedCoinBaseAccount = account
        }

        convertViewModel.onContinueEvent.observe(viewLifecycleOwner) { request ->
            proceedWithSwap(request)
        }

        binding.authLimitBanner.warningLimitInfo.setOnClickListener {
            AdaptiveDialog.custom(R.layout.dialog_withdrawal_limit_info).show(requireActivity())
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            safeNavigate(
                CoinbaseConvertCryptoFragmentDirections
                    .coinbaseConvertCryptoFragmentTocoinbaseConversionPreviewFragment(it)
            )
        }


        viewModel.swapTradeFailedCallback.observe(viewLifecycleOwner) {
            val message = if (it.isNullOrBlank()) {
                requireContext().getString(R.string.something_wrong_title)
            } else {
                it
            }

            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                message,
                getString(R.string.close)
            ).show(requireActivity())
        }

        convertViewModel.userDashAccountEmptyError.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.dont_have_any_dash),
                "",
                getString(R.string.close)
            ).show(requireActivity())
        }

        binding.convertView.setOnCurrencyChooserClicked {
            lifecycleScope.launch {
                var list = listOf<CoinBaseUserAccountDataUIModel>()
                val selectedCurrencyCode = convertViewModel.selectedCryptoCurrencyAccount.value
                    ?.coinBaseUserAccountData?.currency?.code ?: convertViewModel.selectedLocalCurrencyCode

                cryptoWalletsDialog = CryptoWalletsDialog(selectedCurrencyCode) { index, dialog ->
                    list.getOrNull(index)?.let {
                        convertViewModel.setSelectedCryptoCurrency(it)
                        setConvertViewInput()
                    }
                    dialog.dismiss()
                }

                if (cryptoWalletsDialog?.isVisible == false) {
                    cryptoWalletsDialog?.show(parentFragmentManager, "payment_method")
                    list = viewModel.getUserWalletAccounts(binding.convertView.dashToCrypto)

                    if (list.isEmpty()) {
                        if (viewModel.isDeviceConnectedToInternet.value == true) {
                            cryptoWalletsDialog?.dismissAllowingStateLoss()
                            showNoAssetsError()
                        }
                    } else {
                        cryptoWalletsDialog?.submitList(list)
                    }
                }
            }
        }

        binding.convertView.setOnSwapClicked {
            convertViewModel.setOnSwapDashFromToCryptoClicked(it)
        }

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            binding.convertView.exchangeRate = it?.let { ExchangeRate(Coin.COIN, it.fiat) }
            setConvertViewInput()
        }

        convertViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner) { amount ->
            val hasAmount = !amount.isZero
            binding.youWillReceiveLabel.isVisible = hasAmount
            binding.youWillReceiveValue.isVisible = hasAmount

            if (hasAmount && !binding.convertView.dashToCrypto) {
                binding.youWillReceiveValue.text = context?.getString(
                    R.string.you_will_receive_dash,
                    dashFormat.format(amount).toString()
                )
            }
        }

        convertViewModel.enteredConvertCryptoAmount.observe(viewLifecycleOwner) { amount ->
            binding.youWillReceiveLabel.isVisible = amount.second.isNotEmpty()
            binding.youWillReceiveValue.isVisible = amount.second.isNotEmpty()

            if (binding.convertView.dashToCrypto) {
                binding.youWillReceiveValue.text = getString(
                    R.string.fiat_balance_with_currency,
                    amount.first,
                    GenericUtils.currencySymbol(amount.second)
                )
            }
        }

        viewModel.dashWalletBalance.observe(
            viewLifecycleOwner
        ) {
            binding.convertView.dashInput = it
        }

        convertViewModel.validSwapValue.observe(viewLifecycleOwner) {
            binding.limitDesc.isGone = true
            binding.authLimitBanner.root.isGone = true
            setGuidelinePercent(true)
        }

        sharedViewModel.baseIdForFaitModelCoinBase.observe(viewLifecycleOwner) { uiState ->
            // New value received
            when (uiState) {
                is BaseIdForFaitDataUIState.Success -> {
                    uiState.baseIdForFaitDataList.let { list ->
                        viewModel.setBaseIdForFaitModelCoinBase(list)
                    }
                }

                is BaseIdForFaitDataUIState.LoadingState ->{
                    if (uiState.isLoading) {
                        showProgress(R.string.loading)
                    } else {
                        dismissProgress()
                    }
                }
                is BaseIdForFaitDataUIState.Error ->{
                    if (uiState.isError) {
                        // Retry in case of error
                        sharedViewModel.getBaseIdForFaitModel()
                    }
                }
            }
        }
    }

    private fun proceedWithSwap(request: SwapRequest, checkSendingConditions: Boolean = true) {
        if (request.fiatAmount == null && request.amount != null) {
            showSwapValueErrorView(SwapValueErrorType.ExchangeRateMissing)
            return
        }

        val swapValueErrorType = convertViewModel.checkEnteredAmountValue(checkSendingConditions)
        if (swapValueErrorType == SwapValueErrorType.NOError) {
            if (!request.dashToCrypto && convertViewModel.dashToCrypto.value == true) {
                lifecycleScope.launch {
                    if (viewModel.getLastBalance() < (request.amount ?: Coin.ZERO)) {
                        showNoAssetsError()
                    }
                }
            } else {
                if (request.amount != null && viewModel.isInputGreaterThanLimit(request.amount)) {
                    showSwapValueErrorView(SwapValueErrorType.UnAuthorizedValue)
                } else {
                    selectedCoinBaseAccount?.let {
                        request.fiatAmount?.let { fait ->
                            viewModel.swapTrade(fait, it, request.dashToCrypto)
                        }
                    }
                }
            }
        } else {
            showSwapValueErrorView(swapValueErrorType)
        }
    }

    private fun setGuidelinePercent(isErrorHidden: Boolean) {
        val guideLine = binding.amountViewGuide
        val params = guideLine.layoutParams as ConstraintLayout.LayoutParams
        if (isErrorHidden) {
            params.guidePercent = 0.08f // 45% // range: 0 <-> 1
        } else {
            params.guidePercent = 0.15f
        }
        guideLine.layoutParams = params
    }

    private fun showSwapValueErrorView(swapValueErrorType: SwapValueErrorType) {
        binding.limitDesc.isGone = swapValueErrorType == SwapValueErrorType.NOError
        binding.authLimitBanner.root.isVisible = swapValueErrorType == SwapValueErrorType.UnAuthorizedValue
        setGuidelinePercent(binding.limitDesc.isGone && binding.authLimitBanner.root.isGone)
        when (swapValueErrorType) {
            SwapValueErrorType.LessThanMin -> setMinAmountErrorMessage()
            SwapValueErrorType.MoreThanMax -> setMaxAmountError()
            SwapValueErrorType.NotEnoughBalance -> setNoEnoughBalanceError()
            SwapValueErrorType.SendingConditionsUnmet -> showMinimumBalanceWarning()
            SwapValueErrorType.ExchangeRateMissing -> showExchangeRateMissing()
            else -> { }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setNoEnoughBalanceError() {
        binding.limitDesc.setText(R.string.you_dont_have_enough_balance)
    }

    private fun showMinimumBalanceWarning() {
        MinimumBalanceDialog().show(requireActivity()) { isOkToContinue ->
            val request = convertViewModel.onContinueEvent.value

            if (isOkToContinue == true && request != null) {
                proceedWithSwap(request, false)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setMaxAmountError() {
        if (convertViewModel.dashToCrypto.value == true) {
            viewModel.dashWalletBalance.value?.let { dash ->
                convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                    val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                    val fiatAmount = currencyRate.coinToFiat(dash).toFormattedString()
                    binding.limitDesc.text = "${getString(R.string.entered_amount_is_too_high)} $fiatAmount"
                }
            }
        } else {
            convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                selectedCoinBaseAccount?.getCoinBaseExchangeRateConversion(rate)?.first?.let {
                    binding.limitDesc.text = "${getString(R.string.entered_amount_is_too_high)} $it"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setMinAmountErrorMessage() {
        convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
            selectedCoinBaseAccount?.currencyToDashExchangeRate?.let { currencyToDashExchangeRate ->
                val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                val fiatAmount = Fiat.parseFiat(currencyRate.fiat.currencyCode, convertViewModel.minAllowedSwapAmount)
                binding.limitDesc.text = "${getString(
                    R.string.entered_amount_is_too_low
                )} ${fiatAmount.toFormattedString()}"
            }
        }
    }

    private fun showExchangeRateMissing() {
        binding.limitDesc.text = getString(R.string.exchange_rate_not_found)
    }

    private fun setConvertViewInput() {
        convertViewModel.selectedCryptoCurrencyAccount.value?.let {
            val accountData = it.coinBaseUserAccountData
            val currency = accountData.balance?.currency?.lowercase()
            val iconUrl = if (!accountData.balance?.currency.isNullOrEmpty() && currency != null) {
                GenericUtils.getCoinIcon(currency)
            } else {
                null
            }

            binding.convertView.input = ServiceWallet(
                it.coinBaseUserAccountData.currency?.name ?: "",
                getString(R.string.coinbase),
                it.coinBaseUserAccountData.balance?.amount ?: "",
                it.coinBaseUserAccountData.balance?.currency ?: "",
                convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                    it.getCoinBaseExchangeRateConversion(rate).first
                } ?: "",
                iconUrl
            )
            setConvertViewTopMargin(convertViewModel.selectedCryptoCurrencyAccount.value == null)
        }
    }

    private fun setConvertViewTopMargin(isInputEmpty: Boolean) {
        val topMargin = if (isInputEmpty) 18f else 106f
        val params = binding.convertView.layoutParams as ConstraintLayout.LayoutParams
        params.topMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            topMargin,
            requireContext().resources.displayMetrics
        ).toInt()
        binding.convertView.layoutParams = params
    }


    private fun showProgress(messageResId: Int) {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = AdaptiveDialog.progress(getString(messageResId))
        loadingDialog?.show(requireActivity())
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    private fun showNoAssetsError() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.we_didnt_find_any_assets),
            getString(R.string.you_dont_own_any_crypto),
            getString(R.string.close),
            getString(R.string.buy_crypto_on_coinbase),
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
