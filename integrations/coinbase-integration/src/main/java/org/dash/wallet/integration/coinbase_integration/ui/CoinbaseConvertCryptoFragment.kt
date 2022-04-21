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
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.livedata.EventObserver
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.MIN_SWAP_DASH_AMOUNT_VALUE
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseConvertCryptoBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import org.dash.wallet.integration.coinbase_integration.model.getCoinBaseExchangeRateConversion
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.ConvertViewFragment
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.ServiceWallet
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.SwapValueErrorType
import org.dash.wallet.integration.coinbase_integration.ui.dialogs.crypto_wallets.CryptoWalletsDialog
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseConvertCryptoViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.ConvertViewViewModel
import java.math.RoundingMode


@ExperimentalCoroutinesApi
@AndroidEntryPoint
class CoinbaseConvertCryptoFragment : Fragment(R.layout.fragment_coinbase_convert_crypto) {
    private val binding by viewBinding(FragmentCoinbaseConvertCryptoBinding::bind)
    private val viewModel by viewModels<CoinbaseConvertCryptoViewModel>()
    private var loadingDialog: FancyAlertDialog? = null
    private val convertViewModel by activityViewModels<ConvertViewViewModel>()
    private var selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel? = null
    private var cryptoWalletsDialog: CryptoWalletsDialog? = null
    private val dashFormat = MonetaryFormat().withLocale(GenericUtils.getDeviceLocale())
        .noCode().minDecimals(8).optionalDecimals()

    private lateinit var fragment: ConvertViewFragment
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (savedInstanceState == null) {
            fragment = ConvertViewFragment.newInstance()
            fragment.setViewDetails(getString(R.string.get_quote), null)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
                // addToBackStack(null)
            }
        }

        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { hasInternet ->
            fragment.handleNetworkState(hasInternet)
            cryptoWalletsDialog?.handleNetworkState(hasInternet)
        }

        viewModel.showLoading.observe(
            viewLifecycleOwner
        ) {
            if (it) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = getString(
                R.string.exchange_rate_template,
                Coin.COIN.toPlainString(),
                GenericUtils.fiatToString(rate.fiat)
            )
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

        convertViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            val swapValueErrorType = convertViewModel.checkEnteredAmountValue()
            if (swapValueErrorType == SwapValueErrorType.NOError) {
                if (!pair.first && convertViewModel.dashToCrypto.value == true) {
                    pair.second?.first?.let { fait ->
                        if ((viewModel.userPreference.lastCoinbaseBalance?.toDouble() ?: 0.0) <fait.toPlainString().toDouble()) {
                            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                                R.string.we_didnt_find_any_assets,
                                image = R.drawable.ic_info_red,
                                positiveButtonText = R.string.buy_crypto_on_coinbase,
                                negativeButtonText = R.string.close
                            )
                            safeNavigate(
                                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                                    placeBuyOrderError
                                )
                            )
                        }
                    }
                } else {
                    selectedCoinBaseAccount?.let {
                        pair.second?.first?.let { fait ->
                            viewModel.swapTrade(fait, it, pair.first)
                        }
                    }
                }
            } else {
                showSwapValueErrorView(swapValueErrorType)
            }
        }

        viewModel.swapTradeOrder.observe(
            viewLifecycleOwner,
            EventObserver {
                safeNavigate(
                    CoinbaseConvertCryptoFragmentDirections
                        .coinbaseConvertCryptoFragmentTocoinbaseConversionPreviewFragment(it)
                )
            }
        )


        viewModel.swapTradeFailedCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.error,
                it,
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }

        viewModel.userAccountError.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.we_didnt_find_any_assets,
                getString(R.string.you_dont_own_any_crypto),
                R.drawable.ic_info_red,
                R.string.buy_crypto_on_coinbase,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }

        convertViewModel.userDashAccountEmptyError.observe(viewLifecycleOwner) {
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

        binding.convertView.setOnCurrencyChooserClicked {
            viewModel.getUserWalletAccounts(binding.convertView.dashToCrypto)
        }

        binding.convertView.setOnSwapClicked {
            convertViewModel.setOnSwapDashFromToCryptoClicked(it)
        }


        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) {
            binding.convertView.exchangeRate = ExchangeRate(Coin.COIN, it.fiat)
            setConvertViewInput()
        }

        viewModel.userAccountsWithBalance.observe(
            viewLifecycleOwner,
            EventObserver {
                it.sortedBy { item -> item.coinBaseUserAccountData.currency?.code }.let { list ->
                    parentFragmentManager.let { fragmentManager ->

                        cryptoWalletsDialog = CryptoWalletsDialog(
                            list,
                            convertViewModel.selectedLocalCurrencyCode
                        ) { index, dialog ->
                            convertViewModel.setSelectedCryptoCurrency(list[index])

                            setConvertViewInput()
                            dialog.dismiss()
                        }
                        if (this.cryptoWalletsDialog?.isVisible == false) {

                            viewModel.isDeviceConnectedToInternet.value?.let { hasInternet ->
                                cryptoWalletsDialog?.handleNetworkState(hasInternet)
                            }

                            cryptoWalletsDialog?.show(fragmentManager, "payment_method")
                        }
                    }
                }
            }
        )

        convertViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner) { balance ->
            val hasBalance = !balance.isZero
            binding.youWillReceiveLabel.isVisible = hasBalance
            binding.youWillReceiveValue.isVisible = hasBalance
            if (hasBalance) {
                binding.youWillReceiveValue.text = context?.getString(
                    R.string.you_will_receive_dash,
                    dashFormat.format(balance).toString()
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
            setGuidelinePercent(true)
        }

        monitorNetworkChanges()
    }

    private fun setGuidelinePercent(isErrorHidden: Boolean) {
        val guideLine = binding.amountViewGuide
        val params = guideLine.layoutParams as ConstraintLayout.LayoutParams
        if (isErrorHidden) {
            params.guidePercent = 0.08f // 45% // range: 0 <-> 1
        } else {
            params.guidePercent = 0.12f
        }
        guideLine.layoutParams = params
    }

    private fun showSwapValueErrorView(swapValueErrorType: SwapValueErrorType) {
        binding.limitDesc.isGone = swapValueErrorType == SwapValueErrorType.NOError
        setGuidelinePercent(binding.limitDesc.isGone)
        when (swapValueErrorType) {
            SwapValueErrorType.LessThanMin -> setMinAmountErrorMessage()
            SwapValueErrorType.MoreThanMax -> setMaxAmountError()
            SwapValueErrorType.UnAuthorizedValue ->

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
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setMaxAmountError() {
        if (convertViewModel.dashToCrypto.value == true) {
            viewModel.dashWalletBalance.value?.let { dash ->
                convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                    val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                    val fiatAmount = GenericUtils.fiatToString(currencyRate.coinToFiat(dash))

                    binding.limitDesc.text = "${
                    getString(
                        R.string.entered_amount_is_too_high
                    )
                    } $fiatAmount"
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

                val cleanedValue =
                    (
                        MIN_SWAP_DASH_AMOUNT_VALUE.toBigDecimal() /
                            currencyToDashExchangeRate.toBigDecimal()
                        )
                val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

                val currencyRate = ExchangeRate(Coin.COIN, rate.fiat)
                val fiatAmount = Fiat.parseFiat(currencyRate.fiat.currencyCode, bd.toString())
                binding.limitDesc.text = "${getString(
                    R.string.entered_amount_is_too_low
                )} ${GenericUtils.fiatToString(fiatAmount)}"
            }
        }
    }

    private fun setConvertViewInput() {
        convertViewModel.selectedCryptoCurrencyAccount.value?.let {
            val iconUrl =
                if (it.coinBaseUserAccountData.balance?.currency.isNullOrEmpty()
                    .not()
                ) {
                    GenericUtils.getCoinIcon(it.coinBaseUserAccountData.balance?.currency?.lowercase())
                } else {
                    null
                }

            convertViewModel.selectedLocalExchangeRate.value?.let { rate ->
                binding.convertView.input = ServiceWallet(
                    it.coinBaseUserAccountData.currency?.name ?: "",
                    getString(R.string.coinbase),
                    it.coinBaseUserAccountData.balance?.amount ?: "",
                    it.coinBaseUserAccountData.balance?.currency ?: "",
                    it.getCoinBaseExchangeRateConversion(rate).first,
                    iconUrl
                )
            }
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
        loadingDialog = FancyAlertDialog.newProgress(messageResId, 0)
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        convertViewModel.clear()
    }

    private fun monitorNetworkChanges() {
        lifecycleScope.launchWhenResumed {
            viewModel.monitorNetworkStateChange()
        }
    }
}
