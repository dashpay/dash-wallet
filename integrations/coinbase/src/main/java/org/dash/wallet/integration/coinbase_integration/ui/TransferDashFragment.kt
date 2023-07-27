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
import android.view.View
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.services.ConfirmTransactionService
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.*
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.util.*
import org.dash.wallet.integration.coinbase_integration.CoinbaseConstants
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.TransferDashFragmentBinding
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.BaseServiceWallet
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.SwapValueErrorType
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseResultDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterAmountToTransferViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.SendDashResponseState
import org.dash.wallet.integration.coinbase_integration.viewmodels.TransferDashViewModel
import javax.inject.Inject

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class TransferDashFragment : Fragment(R.layout.transfer_dash_fragment) {

    companion object {
        fun newInstance() = TransferDashFragment()
    }

    private val enterAmountToTransferViewModel by activityViewModels<EnterAmountToTransferViewModel>()
    private val transferDashViewModel by activityViewModels<TransferDashViewModel>()
    private val binding by viewBinding(TransferDashFragmentBinding::bind)
    private var loadingDialog: AdaptiveDialog? = null
    @Inject lateinit var securityFunctions: AuthenticationManager
    @Inject lateinit var confirmTransactionLauncher: ConfirmTransactionService
    private var dashValue: Coin = Coin.ZERO
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(2).optionalDecimals()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackButtonPress()
        binding.authLimitBanner.warningLimitIcon.isVisible = false
        binding.authLimitBanner.authLimitDesc.updateLayoutParams<ConstraintLayout.LayoutParams> {
            marginStart = 32
        }
        binding.authLimitBanner.authLimitDesc.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = ConstraintLayout.LayoutParams.WRAP_CONTENT
        }

        if (savedInstanceState == null) {
            val fragment = EnterAmountToTransferFragment.newInstance()
            fragment.setViewDetails(getString(R.string.transfer_dash), null)
            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_to_transfer_placeholder, fragment)
            }
        }

        transferDashViewModel.observeLoadingState.observe(viewLifecycleOwner){
            setLoadingState(it)
        }

        binding.transferView.setOnTransferDirectionBtnClicked {
            enterAmountToTransferViewModel.setOnTransferDirectionListener(binding.transferView.walletToCoinbase)
        }

        transferDashViewModel.dashBalanceInWalletState.observe(viewLifecycleOwner){
            binding.transferView.inputInDash = it
        }

        enterAmountToTransferViewModel.localCurrencyExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.transferView.exchangeRate = rate?.let { ExchangeRate(Coin.COIN, rate.fiat) }
        }

        enterAmountToTransferViewModel.onContinueTransferEvent.observe(viewLifecycleOwner){
            lifecycleScope.launch {
                dashValue = it.second
                if (binding.transferView.walletToCoinbase) {
                    val coinInput = it.second
                    val coinBalance = enterAmountToTransferViewModel.dashBalanceInWalletState.value
                    binding.authLimitBanner.root.isVisible = false
                    binding.dashWalletLimitBanner.isVisible =
                        transferDashViewModel.isInputGreaterThanWalletBalance(
                            coinInput,
                            coinBalance
                        )

                    binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                        guidePercent = if (binding.dashWalletLimitBanner.isVisible) 0.13f else 0.09f
                    }

                    if (!binding.dashWalletLimitBanner.isVisible && transferDashViewModel.isUserAuthorized()) {

                        val isEmptyWallet = enterAmountToTransferViewModel.isMaxAmountSelected &&
                                binding.transferView.walletToCoinbase
                        transferDashViewModel.estimateNetworkFee(dashValue, emptyWallet = isEmptyWallet)?.let {
                            securityFunctions.authenticate(requireActivity())?.let {
                                transferDashViewModel.createAddressForAccount()
                            }
                        }
                    }
                } else {
                    binding.dashWalletLimitBanner.isVisible = false
                    val error = transferDashViewModel.checkEnteredAmountValue(it.second)
                    binding.authLimitBanner.root.isVisible = error == SwapValueErrorType.UnAuthorizedValue
                    binding.dashWalletLimitBanner.isVisible = (error == SwapValueErrorType.MoreThanMax
                            || error == SwapValueErrorType.LessThanMin
                            || error == SwapValueErrorType.NotEnoughBalance)

                    if (binding.authLimitBanner.root.isVisible) {
                        binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                            guidePercent = if (binding.authLimitBanner.root.isVisible) 0.15f else 0.09f
                        }

                    } else if (binding.dashWalletLimitBanner.isVisible) {
                        binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                            guidePercent = if (binding.dashWalletLimitBanner.isVisible) 0.15f else 0.09f
                        }
                        when (error) {
                            SwapValueErrorType.LessThanMin -> setMinAmountErrorMessage()
                            SwapValueErrorType.MoreThanMax -> setMaxAmountError()
                            SwapValueErrorType.NotEnoughBalance -> setNoEnoughBalanceError()
                            else -> {}
                        }
                    } else {
                        transferDashViewModel.reviewTransfer(dashValue.toPlainString())
                    }
                }
            }
        }

        transferDashViewModel.sendDashToCoinbaseError.observe(viewLifecycleOwner){
            binding.dashWalletLimitBanner.isVisible = (it.exceptionMessageResource!=null || it.exceptionMessage?.isNotEmpty() == true)

            binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                guidePercent = if (binding.dashWalletLimitBanner.isVisible) 0.13f else 0.09f
            }

            it.exceptionMessageResource?.let{
                binding.dashWalletLimitBanner.text = getString(it)
            }
            it.exceptionMessage?.let{
                binding.dashWalletLimitBanner.text = it
            }
        }

        transferDashViewModel.userAccountOnCoinbaseState.observe(viewLifecycleOwner){
            enterAmountToTransferViewModel.coinbaseExchangeRate = it

            val fiatVal = it.coinBaseUserAccountData.balance?.amount?.let { amount ->
                enterAmountToTransferViewModel.getCoinbaseBalanceInFiatFormat(amount)
            } ?: CoinbaseConstants.VALUE_ZERO
            binding.transferView.balanceOnCoinbase = BaseServiceWallet(
                it.coinBaseUserAccountData.balance?.amount ?: CoinbaseConstants.VALUE_ZERO,
                fiatVal
            )
            // After initial load when coinbase exchange rate loaded
            enterAmountToTransferViewModel.setBalanceForWallet()
        }

        enterAmountToTransferViewModel.dashWalletEmptyCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.dont_have_any_dash),
                "",
                "",
                getString(R.string.close)
            ).show(requireActivity()) { }
        }

        enterAmountToTransferViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner){
            val dashInStr = dashFormat.optionalDecimals(0,6).format(it.second)
            val amountFiat = dashFormat.format(it.first).toString()
            val fiatSymbol = GenericUtils.currencySymbol(it.first.currencyCode)

            val formatDashValue = "$dashInStr ${Constants.DASH_CURRENCY}"

            val formatFiatValue = if (it.first.isCurrencyFirst()) {
                "$fiatSymbol $amountFiat"
            } else {
                "$amountFiat $fiatSymbol"
            }
            // For initial load till coinbase exchange rate loaded
            if(enterAmountToTransferViewModel.coinbaseExchangeRate==null){
                binding.amountReceived.text = getString(
                    R.string.amount_to_transfer_dash,
                    formatDashValue
                )
            }
            enterAmountToTransferViewModel.coinbaseExchangeRate?.let {
                binding.amountReceived.text = getString(
                    R.string.amount_to_transfer,
                    formatDashValue,
                    Constants.PREFIX_ALMOST_EQUAL_TO,
                    formatFiatValue
                )
            }
            binding.amountReceived.isVisible = enterAmountToTransferViewModel.hasBalance
        }

        enterAmountToTransferViewModel.removeBannerCallback.observe(viewLifecycleOwner){
            hideBanners()
        }

        enterAmountToTransferViewModel.transferDirectionState.observe(viewLifecycleOwner){
            binding.transferView.walletToCoinbase = it
            hideBanners()
            setIsSyncing(it == true && enterAmountToTransferViewModel.isBlockchainSynced.value != true)
        }

        enterAmountToTransferViewModel.isBlockchainSynced.observe(viewLifecycleOwner) {
            setIsSyncing(it != true && binding.transferView.walletToCoinbase)
        }

        binding.authLimitBanner.root.setOnClickListener {
            transferDashViewModel.logEvent(AnalyticsConstants.Coinbase.TRANSFER_AUTH_LIMIT)
            AdaptiveDialog.custom(R.layout.dialog_withdrawal_limit_info).show(requireActivity())
        }

        transferDashViewModel.observeCoinbaseAddressState.observe(viewLifecycleOwner) { address ->
            val exchangeRate = enterAmountToTransferViewModel.getExchangeRate()
            val isEmptyWallet = enterAmountToTransferViewModel.isMaxAmountSelected &&
                    binding.transferView.walletToCoinbase

            lifecycleScope.launch {
                val details = transferDashViewModel.estimateNetworkFee(dashValue, emptyWallet = isEmptyWallet)
                details?.amountToSend?.toPlainString()?.let { amountStr ->
                    hideBanners()
                    val isTransactionConfirmed = confirmTransactionLauncher.showTransactionDetailsPreview(
                        requireActivity(),
                        address,
                        amountStr,
                        exchangeRate,
                        details.fee,
                        details.totalAmount
                    )

                    if (isTransactionConfirmed) {
                        transferDashViewModel.logTransfer(enterAmountToTransferViewModel.isFiatSelected)
                        AdaptiveDialog.withProgress(getString(R.string.please_wait_title), requireActivity()) {
                            handleSend(dashValue, isEmptyWallet)
                        }
                    }
                }
            }
        }

        transferDashViewModel.onAddressCreationFailedCallback.observe(viewLifecycleOwner) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                getString(R.string.address_creation_failed),
                getString(R.string.close)
            ).show(requireActivity())
        }

        transferDashViewModel.observeSendDashToCoinbaseState.observe(viewLifecycleOwner){
            setTransactionState(it)
        }

        transferDashViewModel.onBuildTransactionParamsCallback.observe(viewLifecycleOwner){
            transferDashViewModel.logTransfer(enterAmountToTransferViewModel.isFiatSelected)
            safeNavigate(TransferDashFragmentDirections.transferDashToTwoFaCode(it))
        }

        transferDashViewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner){ hasInternet ->
            setInternetAccessState(hasInternet)
        }

        transferDashViewModel.onFetchUserDataOnCoinbaseFailedCallback.observe(viewLifecycleOwner){
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.coinbase_dash_wallet_error_title),
                getString(R.string.coinbase_dash_wallet_error_message),
                getString(R.string.close),
                getString(R.string.create_dash_account)
            ).show(requireActivity()) { result ->
                if (result == true) {
                    val defaultBrowser = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                    defaultBrowser.data = Uri.parse(getString(R.string.coinbase_website))
                    startActivity(defaultBrowser)
                }
            }
        }
    }

    private fun setIsSyncing(isSyncing: Boolean) {
        binding.transferView.setSyncing(isSyncing)
        binding.transferMessage.isVisible = isSyncing
        enterAmountToTransferViewModel.keyboardStateCallback.value = !isSyncing
    }

    private suspend fun handleSend(value: Coin, isEmptyWallet: Boolean): Boolean {
        try {
            transferDashViewModel.sendDash(value, isEmptyWallet, true)
            return true
        } catch (ex: LeftoverBalanceException) {
            val result = MinimumBalanceDialog().showAsync(requireActivity())

            if (result == true) {
                transferDashViewModel.sendDash(value, isEmptyWallet, false)
                return true
            }
        }

        return false
    }

    private fun handleBackButtonPress(){
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){ findNavController().popBackStack() }
    }

    private fun setInternetAccessState(hasInternet: Boolean) {
        binding.networkStatusStub.isVisible = !hasInternet
        binding.transferView.isDeviceConnectedToInternet = hasInternet
        if (!binding.transferView.walletToCoinbase) {
            enterAmountToTransferViewModel.keyboardStateCallback.value = hasInternet
        } else {
            enterAmountToTransferViewModel.keyboardStateCallback.value =
                hasInternet && enterAmountToTransferViewModel.isBlockchainSynced.value == true
        }
    }

    private fun setTransactionState(responseState: SendDashResponseState) {
        val pair: Pair<CoinBaseResultDialog.Type, String?> = when(responseState){
            is SendDashResponseState.SuccessState -> {
                Pair(if (responseState.isTransactionPending) CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS else CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, null)
            }
            is SendDashResponseState.FailureState -> Pair(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, responseState.failureMessage)
            is SendDashResponseState.InsufficientMoneyState -> Pair(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, getString(R.string.insufficient_money_to_transfer))
            else -> Pair(CoinBaseResultDialog.Type.TRANSFER_DASH_ERROR, null)
        }

        val transactionStateDialog = CoinBaseResultDialog.newInstance(pair.first, pair.second, dashToCoinbase = true).apply {
            onCoinBaseResultDialogButtonsClickListener = object : CoinBaseResultDialog.CoinBaseResultDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseResultDialog.Type) {
                    when(type){
                        CoinBaseResultDialog.Type.TRANSFER_DASH_SUCCESS -> {
                            transferDashViewModel.logClose(type)
                            dismiss()
                            requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                            requireActivity().finish()
                        }
                        else -> {
                            transferDashViewModel.logRetry()
                            dismiss()
                            findNavController().popBackStack()
                        }
                    }
                }

                override fun onNegativeButtonClick(type: CoinBaseResultDialog.Type) {
                    transferDashViewModel.logClose(type)
                }
            }
        }

        transactionStateDialog.showNow(parentFragmentManager, "TransactionStateDialog")
    }

    @SuppressLint("SetTextI18n")
    private fun setMinAmountErrorMessage() {
        binding.dashWalletLimitBanner.text = "${getString(
            R.string.entered_amount_is_too_low
        )} ${transferDashViewModel.minFaitAmount.toFormattedString()}"
    }

    @SuppressLint("SetTextI18n")
    private fun setMaxAmountError() {
        val fiatVal =  transferDashViewModel.userAccountOnCoinbaseState.value?.coinBaseUserAccountData?.balance?.amount?.let { amount ->
            enterAmountToTransferViewModel.getCoinbaseBalanceInFiatFormat(amount)
        } ?: CoinbaseConstants.VALUE_ZERO
        binding.dashWalletLimitBanner.text = "${getString(R.string.entered_amount_is_too_high)} $fiatVal"
    }

    @SuppressLint("SetTextI18n")
    private fun setNoEnoughBalanceError() {
        binding.dashWalletLimitBanner.setText(R.string.you_dont_have_enough_balance)
    }
    private fun setLoadingState(showLoading: Boolean) {
        if (showLoading){
            displayProgressDialog()
        } else {
            hideProgressDialog()

        }
    }

    private fun hideProgressDialog() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    private fun displayProgressDialog() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = AdaptiveDialog.progress(getString(R.string.loading))
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun hideBanners(){
        binding.dashWalletLimitBanner.isVisible = false
        binding.authLimitBanner.root.isVisible = false
        binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
            guidePercent = 0.09f
        }
    }
}