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
import androidx.fragment.app.Fragment
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.TransferDashFragmentBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import org.dash.wallet.integration.coinbase_integration.model.getBalanceWithCoinbaseExchangeRate
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.model.BaseServiceWallet
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterAmountToTransferViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.TransferDashViewModel

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner){ findNavController().popBackStack() }
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

        enterAmountToTransferViewModel.enteredConvertDashAmount.observe(viewLifecycleOwner){
            //TODO handle case balance is empty
        }
        enterAmountToTransferViewModel.localCurrencyExchangeRate.observe(viewLifecycleOwner){ rate ->
            binding.transferView.exchangeRate = ExchangeRate(Coin.COIN, rate.fiat)
        }

        enterAmountToTransferViewModel.onContinueTransferEvent.observe(viewLifecycleOwner){
            if (binding.transferView.walletToCoinbase){
                binding.authLimitBanner.root.isVisible = false
                binding.dashWalletLimitBanner.isVisible =
                    transferDashViewModel.isInputGreaterThanWalletBalance(
                        enterAmountToTransferViewModel.coinbaseExchangeRateAppliedOnInput,
                        enterAmountToTransferViewModel.coinbaseExchangeRateAppliedOnWalletBalance
                    )

                binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    guidePercent = if (binding.dashWalletLimitBanner.isVisible) 0.13f else 0.09f
                }

                if (! binding.dashWalletLimitBanner.isVisible){
                    // continue
                }
                Toast.makeText(requireActivity(), "transfer from wallet to coinbase", Toast.LENGTH_SHORT).show()
                // wallet -> coinbase
                //transferDashViewModel.transferDashToCoinbase()
            } else {
                binding.dashWalletLimitBanner.isVisible = false
                binding.authLimitBanner.root.isVisible = transferDashViewModel.isInputGreaterThanCoinbaseLimit(it.second)
                binding.topGuideLine.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    guidePercent = if (binding.authLimitBanner.root.isVisible) 0.15f else 0.09f
                }

                if (!binding.authLimitBanner.root.isVisible){
                    // continue
                }
                Toast.makeText(requireActivity(), "transfer from coinbase to wallet", Toast.LENGTH_SHORT).show()

                // coinbase -> wallet
                //transferDashViewModel.transferDashToWallet()
            }
        }

        enterAmountToTransferViewModel.userAccountOnCoinbaseState.observe(viewLifecycleOwner){
            binding.transferView.balanceOnCoinbase = BaseServiceWallet(
                it.coinBaseUserAccountData.balance?.amount,
                it.coinBaseUserAccountData.balance?.currency,
                it.getBalanceWithCoinbaseExchangeRate(enterAmountToTransferViewModel.localCurrencyExchangeRate.value!!))
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

    private fun setLoadingState(showLoading: Boolean) {
        if (loadingDialog != null && loadingDialog?.isAdded == true){
            loadingDialog?.dismissAllowingStateLoss()
            if (showLoading){
                loadingDialog = FancyAlertDialog.newProgress(R.string.loading)
                loadingDialog?.show(parentFragmentManager, "progress")
            }
        }
    }

}