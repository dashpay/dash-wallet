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
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.services.ConfirmTransactionService
import org.dash.wallet.common.services.SecurityModel
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.ui.*
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.VALUE_ZERO
import org.dash.wallet.integration.coinbase_integration.databinding.TransferDashFragmentBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.BaseServiceWallet
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.CoinBaseBuyDashDialog
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
    private var loadingDialog: FancyAlertDialog? = null
    @Inject lateinit var securityModel: SecurityModel
    @Inject lateinit var confirmTransactionLauncher: ConfirmTransactionService
    @Inject lateinit var transactionDetails: SendPaymentService
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

        parentFragmentManager.beginTransaction()
            .replace(org.dash.wallet.common.R.id.network_status_container, NetworkUnavailableFragment.newInstance())
            .commit()

        transferDashViewModel.observeLoadingState.observe(viewLifecycleOwner){
            setLoadingState(it)
        }

        binding.transferView.setOnTransferDirectionBtnClicked {
            enterAmountToTransferViewModel.setOnTransferDirectionListener(binding.transferView.walletToCoinbase)
        }

        transferDashViewModel.dashBalanceInWalletState.observe(viewLifecycleOwner){
            binding.transferView.inputInDash = it
        }

        enterAmountToTransferViewModel.localCurrencyExchangeRate.observe(viewLifecycleOwner){ rate ->
            binding.transferView.exchangeRate = ExchangeRate(Coin.COIN, rate.fiat)
        }

        enterAmountToTransferViewModel.onContinueTransferEvent.observe(viewLifecycleOwner){
            dashValue = it.second
            if (binding.transferView.walletToCoinbase){
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

                if (!binding.dashWalletLimitBanner.isVisible && transferDashViewModel.isUserAuthorized()){
                    lifecycleScope.launch {
                        securityModel.requestPinCode(requireActivity())?.let {
                            transferDashViewModel.createAddressForAccount()
                        }
                    }
                }
            } else {
                binding.dashWalletLimitBanner.isVisible = false
                binding.authLimitBanner.root.isVisible = transferDashViewModel.isInputGreaterThanCoinbaseWithdrawalLimit(it.first)
                binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    guidePercent = if (binding.authLimitBanner.root.isVisible) 0.15f else 0.09f
                }

                if (!binding.authLimitBanner.root.isVisible){
                    transferDashViewModel.reviewTransfer(dashValue.toPlainString())
                }
            }
        }

        transferDashViewModel.userAccountOnCoinbaseState.observe(viewLifecycleOwner){
            enterAmountToTransferViewModel.coinbaseExchangeRate = it

            val fiatVal = it.coinBaseUserAccountData.balance?.amount?.let { amount ->
                enterAmountToTransferViewModel.getCoinbaseBalanceInFiatFormat(amount)
            } ?: VALUE_ZERO
            binding.transferView.balanceOnCoinbase = BaseServiceWallet(
                it.coinBaseUserAccountData.balance?.amount ?: VALUE_ZERO,
                fiatVal)
        }

        enterAmountToTransferViewModel.dashWalletEmptyCallback.observe(viewLifecycleOwner){
            val dashAccountEmptyError = CoinbaseGenericErrorUIModel(
                title = R.string.dont_have_any_dash,
                image = R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    dashAccountEmptyError
                )
            )
        }

        enterAmountToTransferViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner){
            val dashInStr = dashFormat.optionalDecimals(0,6).format(it.second)
            val amountFiat = dashFormat.format(it.first).toString()
            val fiatSymbol = GenericUtils.currencySymbol(it.first.currencyCode)

            val formatDashValue = "$dashInStr $DASH_CURRENCY"

            val formatFiatValue = if (GenericUtils.isCurrencyFirst(it.first)) {
                "$fiatSymbol $amountFiat"
            } else {
                "$amountFiat $fiatSymbol"
            }

            binding.amountReceived.text = getString(R.string.amount_to_transfer, formatDashValue, Constants.PREFIX_ALMOST_EQUAL_TO, formatFiatValue)
            binding.amountReceived.isVisible = enterAmountToTransferViewModel.hasBalance
        }

        enterAmountToTransferViewModel.removeBannerCallback.observe(viewLifecycleOwner){
            hideBanners()
        }

        enterAmountToTransferViewModel.transferDirectionState.observe(viewLifecycleOwner){
            binding.transferView.walletToCoinbase = it
            hideBanners()
        }

        binding.authLimitBanner.warningLimitInfo.setOnClickListener {
            AdaptiveDialog.custom(
                R.layout.dialog_withdrawal_limit_info,
                null,
                getString(R.string.set_auth_limit),
                getString(R.string.change_withdrawal_limit),
                "",
                getString(R.string.got_it)
            ).show(requireActivity()) { }
        }

        transferDashViewModel.observeCoinbaseAddressState.observe(viewLifecycleOwner){ address ->
            val fiatVal = enterAmountToTransferViewModel.getFiat(dashValue.toPlainString())
            val amountFiat = dashFormat.format(fiatVal).toString()
            val fiatSymbol = GenericUtils.currencySymbol(fiatVal.currencyCode)

            lifecycleScope.launch {
                val details = transactionDetails.estimateNetworkFee(transferDashViewModel.dashAddress, dashValue)
                val amountStr = details.amountToSend.toPlainString()

                val isTransactionConfirmed = confirmTransactionLauncher.showTransactionDetailsPreview(
                    requireActivity(), address, amountStr, amountFiat, fiatSymbol, details.fee,
                    details.totalAmount, null, null, null)
                if (isTransactionConfirmed){
                    transferDashViewModel.sendDash(dashValue)
                }
            }
        }

        transferDashViewModel.onAddressCreationFailedCallback.observe(viewLifecycleOwner){
            val addressCreationError = CoinbaseGenericErrorUIModel(
                R.string.error,
                getString(R.string.address_creation_failed),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(CoinbaseServicesFragmentDirections.coinbaseServicesToError(addressCreationError))
        }

        transferDashViewModel.observeSendDashToCoinbaseState.observe(viewLifecycleOwner){
            setTransactionState(it)
        }

        transferDashViewModel.onBuildTransactionParamsCallback.observe(viewLifecycleOwner){
            safeNavigate(TransferDashFragmentDirections.transferDashToTwoFaCode(it))
        }

        monitorNetworkChanges()

        transferDashViewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner){ hasInternet ->
            setInternetAccessState(hasInternet)
        }

        transferDashViewModel.onFetchUserDataOnCoinbaseFailedCallback.observe(viewLifecycleOwner){
            val failure = CoinbaseGenericErrorUIModel(
                R.string.coinbase_dash_wallet_error_title,
                getString(R.string.coinbase_dash_wallet_error_message),
                R.drawable.ic_info_red,
                R.string.CreateـDashـAccount,
                R.string.close
            )
            safeNavigate(CoinbaseServicesFragmentDirections.coinbaseServicesToError(failure))
        }
    }

    private fun handleBackButtonPress(){
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){ findNavController().popBackStack() }
    }

    private fun setInternetAccessState(hasInternet: Boolean) {
        binding.networkStatusContainer.isVisible = !hasInternet
        binding.transferView.isDeviceConnectedToInternet = hasInternet
        enterAmountToTransferViewModel.keyboardStateCallback.value = hasInternet
    }

    private fun setTransactionState(responseState: SendDashResponseState) {
        val pair: Pair<CoinBaseBuyDashDialog.Type, String?> = when(responseState){
            is SendDashResponseState.SuccessState -> {
                Pair(if (responseState.isTransactionPending) CoinBaseBuyDashDialog.Type.TRANSFER_DASH_SUCCESS else CoinBaseBuyDashDialog.Type.TRANSFER_DASH_ERROR, null)
            }
            is SendDashResponseState.FailureState -> Pair(CoinBaseBuyDashDialog.Type.TRANSFER_DASH_ERROR, responseState.failureMessage)
            is SendDashResponseState.InsufficientMoneyState -> Pair(CoinBaseBuyDashDialog.Type.TRANSFER_DASH_ERROR, getString(R.string.insufficient_money_to_transfer))
            else -> Pair(CoinBaseBuyDashDialog.Type.TRANSFER_DASH_ERROR, null)
        }

        val transactionStateDialog = CoinBaseBuyDashDialog.newInstance(pair.first, pair.second).apply {
            onCoinBaseBuyDashDialogButtonsClickListener = object : CoinBaseBuyDashDialog.CoinBaseBuyDashDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseBuyDashDialog.Type) {
                    when(type){
                        CoinBaseBuyDashDialog.Type.TRANSFER_DASH_SUCCESS -> {
                            dismiss()
                            requireActivity().setResult(Constants.RESULT_CODE_GO_HOME)
                            requireActivity().finish()
                        }
                        else -> {
                            dismiss()
                            findNavController().popBackStack()
                        }
                    }
                }
            }
        }

        transactionStateDialog.showNow(parentFragmentManager, "TransactionStateDialog")
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
        loadingDialog = FancyAlertDialog.newProgress(R.string.loading, 0)
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun hideBanners(){
        binding.dashWalletLimitBanner.isVisible = false
        binding.authLimitBanner.root.isVisible = false
        binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
            guidePercent = 0.09f
        }
    }

    private fun monitorNetworkChanges(){
        lifecycleScope.launchWhenResumed {
            transferDashViewModel.monitorNetworkStateChange()
        }
    }
}