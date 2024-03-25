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
package org.dash.wallet.integrations.maya.ui

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
import androidx.fragment.app.commit
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
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.databinding.FragmentMayaConvertCryptoBinding
import org.dash.wallet.integrations.maya.model.Account
import org.dash.wallet.integrations.maya.model.AccountDataUIModel
import org.dash.wallet.integrations.maya.model.Balance
import org.dash.wallet.integrations.maya.model.getCoinBaseExchangeRateConversion
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewFragment
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewViewModel
import org.dash.wallet.integrations.maya.ui.convert_currency.model.ServiceWallet
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapRequest
import org.dash.wallet.integrations.maya.ui.convert_currency.model.SwapValueErrorType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@AndroidEntryPoint
class MayaConvertCryptoFragment : Fragment(R.layout.fragment_maya_convert_crypto) {
    private val binding by viewBinding(FragmentMayaConvertCryptoBinding::bind)
    private val viewModel by viewModels<MayaConvertCryptoViewModel>()
    private val convertViewModel by mayaViewModels<ConvertViewViewModel>()
    private val mayaViewModel by viewModels<MayaViewModel>()
    private val args by navArgs<MayaConvertCryptoFragmentArgs>()

    private var loadingDialog: AdaptiveDialog? = null
    private var selectedCoinBaseAccount: AccountDataUIModel? = null
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(8).optionalDecimals()

    private lateinit var fragment: ConvertViewFragment

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val poolInfo = mayaViewModel.getPoolInfo(args.currency)
        val dashPoolInfo = mayaViewModel.getPoolInfo("DASH")

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.convertView.isSellSwapEnabled = true
        convertViewModel.setOnSwapDashFromToCryptoClicked(true)

        if (savedInstanceState == null) {
            fragment = ConvertViewFragment.newInstance()
            fragment.setViewDetails(getString(R.string.button_continue), null)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            fragment.handleNetworkState(hasInternet)
            binding.convertView.isEnabled = hasInternet
        }

//        viewModel.showLoading.observe(
//            viewLifecycleOwner
//        ) {
//            if (it) {
//                showProgress(R.string.loading)
//            } else {
//                dismissProgress()
//            }
//        }

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
                MayaConvertCryptoFragmentDirections
                    .mayaConvertCryptoFragmentToMayaConversionPreviewFragment(
                        it,
                        convertViewModel.destinationCurrency!!,
                        viewModel.getUpdatedPaymentIntent(
                            convertViewModel.enteredConvertDashAmount.value!!,
                            Address.fromBase58(
                                null,
                                mayaViewModel.inboundAddresses.find { inboundAddress ->
                                    inboundAddress.chain == "DASH"
                                }!!.address
                            )
                        )!!
                    )
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
                getString(R.string.button_close)
            ).show(requireActivity())
        }

        convertViewModel.userDashAccountEmptyError.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.dont_have_any_dash),
                "",
                getString(R.string.button_close)
            ).show(requireActivity())
        }

        convertViewModel.setSelectedCryptoCurrency(
            AccountDataUIModel(
                Account(
                    UUID.nameUUIDFromBytes(args.currency.toByteArray()),
                    args.currency,
                    args.currency,
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

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            binding.convertView.exchangeRate = it?.let { ExchangeRate(Coin.COIN, it.fiat) }
            setConvertViewInput()
        }

        convertViewModel.enteredAmount.observe(viewLifecycleOwner) { amount ->
            // convertViewModel.setAmount(amount, convertViewModel.selectedPickerCurrencyCode)
        }

        convertViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner) { amount ->
            val hasAmount = !amount.isZero
            binding.youWillReceiveLabel.isVisible = hasAmount
            binding.youWillReceiveValue.isVisible = hasAmount
            binding.convertView.dashInput = amount
        }

        convertViewModel.enteredConvertFiatAmount.observe(viewLifecycleOwner) { amount ->
            val hasAmount = !amount.isZero
            binding.youWillReceiveLabel.isVisible = hasAmount
            binding.youWillReceiveValue.isVisible = hasAmount
            binding.convertView.fiatInput = amount
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
//            binding.convertView.dashInput = it
        }

        convertViewModel.validSwapValue.observe(viewLifecycleOwner) {
            binding.limitDesc.isGone = true
            binding.authLimitBanner.root.isGone = true
            setGuidelinePercent(true)
        }

        mayaViewModel.updateInboundAddresses()
    }

    private fun proceedWithSwap(request: SwapRequest, checkSendingConditions: Boolean = true) {
        if (request.fiatAmount == null && request.amount != null) {
            showSwapValueErrorView(SwapValueErrorType.ExchangeRateMissing)
            return
        }

        val swapValueErrorType = convertViewModel.checkEnteredAmountValue(checkSendingConditions)

        lifecycleScope.launch {
            if (swapValueErrorType == SwapValueErrorType.NOError) {
                if (!request.dashToCrypto && convertViewModel.dashToCrypto.value == true) {
                    if (viewModel.getLastBalance() < (request.amount ?: Coin.ZERO)) {
                        showNoAssetsError()
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
    }

    private fun setGuidelinePercent(isErrorHidden: Boolean) {
        val guideLine = binding.amountViewGuide
        val params = guideLine.layoutParams as ConstraintLayout.LayoutParams
        if (isErrorHidden) {
            params.guidePercent = 0.15f // 45% // range: 0 <-> 1
        } else {
            params.guidePercent = 0.22f
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
        convertViewModel.selectedCryptoCurrencyAccount.value?.let { it ->
            val accountData = it.coinbaseAccount
            val currency = accountData.currency.lowercase()
            val iconUrl = if (accountData.currency.isNotEmpty()) {
                GenericUtils.getCoinIcon(currency)
            } else {
                null
            }

            val address = args.paymentIntent.outputs?.first().let { output ->
                val memoChunk = output?.script?.chunks?.get(1)!!
                var memo = String(memoChunk.data!!)
                val index = memo.indexOfLast { ch -> ch == ':' }
                memo = memo.substring(index + 1)
                memo
            }
            binding.convertView.input = ServiceWallet(
                it.coinbaseAccount.name,
                address,
                it.coinbaseAccount.availableBalance.value,
                it.coinbaseAccount.currency,
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
