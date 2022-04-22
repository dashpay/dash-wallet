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
import android.util.TypedValue
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.livedata.EventObserver
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.DASH_CURRENCY
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (savedInstanceState == null) {
            val fragment = ConvertViewFragment.newInstance()
            fragment.setViewDetails(getString(R.string.get_quote), null)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
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

        convertViewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) { account ->
            selectedCoinBaseAccount = account
        }

        convertViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            val swapValueErrorType = convertViewModel.checkEnteredAmountValue()
            if (swapValueErrorType == SwapValueErrorType.NOError) {// dash -> coinbase
                if (!pair.first && selectedCoinBaseAccount?.coinBaseUserAccountData?.currency?.code != DASH_CURRENCY) {
                    //coinbase -> wallet
                    selectedCoinBaseAccount?.let {
                        pair.second?.first?.let { fait ->
                            viewModel.swapTrade(fait, it, pair.first)
                        }
                    }
                } else {
                    // wallet -> coinbase
                    pair.second?.second?.let { coin ->
                        selectedCoinBaseAccount?.let {
                            pair.second?.first?.let { fait ->
                                viewModel.sellDashToCoinBase(coin, fait, it)
                            }
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


        viewModel.getUserAccountAddressFailedCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.error,
                getString(R.string.error),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }


        viewModel.onInsufficientMoneyCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.insufficient_money_title,
                getString(R.string.insufficient_money_msg),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }

        viewModel.onFailure.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.send_coins_error_msg,
                getString(R.string.insufficient_money_msg),
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            safeNavigate(
                CoinbaseServicesFragmentDirections.coinbaseServicesToError(
                    placeBuyOrderError
                )
            )
        }

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
        }
    }

    private fun showSwapValueErrorView(swapValueErrorType: SwapValueErrorType) {
        binding.limitDesc.isGone = swapValueErrorType == SwapValueErrorType.NOError
        when (swapValueErrorType) {
            SwapValueErrorType.LessThanMin -> binding.limitDesc.setText(R.string.entered_amount_is_too_low)
            SwapValueErrorType.MoreThanMax -> binding.limitDesc.setText(R.string.entered_amount_is_too_high)
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
}
