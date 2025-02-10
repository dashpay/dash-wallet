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
package org.dash.wallet.integrations.coinbase.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ExtraActionDialog
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.coinbase.R
import org.dash.wallet.integrations.coinbase.databinding.FragmentCoinbaseOrderReviewBinding
import org.dash.wallet.integrations.coinbase.model.*
import org.dash.wallet.integrations.coinbase.ui.dialogs.CoinBaseResultDialog
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseBuyDashViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseBuyUIState
import org.dash.wallet.integrations.coinbase.viewmodels.CoinbaseViewModel
import org.dash.wallet.integrations.coinbase.viewmodels.coinbaseViewModels

@AndroidEntryPoint
class CoinbaseOrderReviewFragment : Fragment(R.layout.fragment_coinbase_order_review) {
    private val binding by viewBinding(FragmentCoinbaseOrderReviewBinding::bind)
    private val viewModel by coinbaseViewModels<CoinbaseBuyDashViewModel>()
    private val sharedViewModel by coinbaseViewModels<CoinbaseViewModel>()
    private val dashFormat = MonetaryFormat().withLocale(
        GenericUtils.getDeviceLocale()
    ).noCode().minDecimals(6).optionalDecimals()
    private var onBackPressedCallback: OnBackPressedCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            findNavController().popBackStack()
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.cancelBtn.setOnClickListener {
            val dialog = AdaptiveDialog.simple(
                getString(R.string.cancel_transaction),
                getString(R.string.no_keep_it),
                getString(R.string.yes_cancel)
            )
            dialog.isCancelable = false
            dialog.show(requireActivity()) { result ->
                if (result == true) {
                    findNavController().popBackStack()
                }
            }
        }

        binding.confirmBtnContainer.setOnClickListener {
            lifecycleScope.launch {
                if (tryBuyDash()) {
                    val params = viewModel.getTransferDashParams()
                    val twoFaParams = CoinbaseTransactionParams(params, TransactionType.BuyDash)
                    safeNavigate(CoinbaseOrderReviewFragmentDirections.coinbaseBuyDashOrderReviewToTwoFaCode(twoFaParams))
                }
            }
        }

        binding.contentOrderReview.coinbaseFeeInfoContainer.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_QUOTE_FEE_INFO)
            ExtraActionDialog.create(
                R.drawable.ic_info_blue,
                getString(R.string.fees_in_crypto_purchase),
                getString(R.string.coinbase_fee_info_msg_content),
                negativeButtonText = getString(android.R.string.ok),
                extraMessage =  getString(R.string.learn_more)
            ).show(
                requireActivity(),
                onResult = { },
                onExtraMessageAction = {
                    requireActivity().openCustomTab(CoinbaseConstants.FEE_INFO_URL)
                }
            )
        }

        sharedViewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state.isSessionExpired) {
                findNavController().popBackStack(R.id.coinbaseServicesFragment, false)
            } else {
                setNetworkState(state.isNetworkAvailable)
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            updateOrderReviewUI(state)
        }
    }

    private fun updateOrderReviewUI(state: CoinbaseBuyUIState) {
        binding.contentReviewBuyOrderDashAmount.dashAmount.text = dashFormat.format(state.dashAmount)
        binding.contentReviewBuyOrderDashAmount.message.text =
            getString(R.string.you_will_receive_dash_on_your_dash_wallet, dashFormat.format(state.dashAmount))

        if (state.order != null && state.fee != null) {
            binding.contentOrderReview.purchaseAmount.text = state.order.toFormattedString()
            binding.contentOrderReview.coinbaseFeeAmount.text = state.fee.toFormattedString()
            binding.contentOrderReview.totalAmount.text = state.order.add(state.fee).toFormattedString()
        }

        if (state.paymentMethod != null) {
            val (name, account) = splitNameAndAccount(state.paymentMethod.name, state.paymentMethod.paymentMethodType)
            binding.contentOrderReview.paymentMethodName.text = name
            binding.contentOrderReview.account.text = account
        }
    }

    private fun showBuyOrderDialog(responseMessage: String) {
        val transactionStateDialog = CoinBaseResultDialog.newInstance(CoinBaseResultDialog.Type.PURCHASE_ERROR, responseMessage).apply {
            this.onCoinBaseResultDialogButtonsClickListener = object : CoinBaseResultDialog.CoinBaseResultDialogButtonsClickListener {
                override fun onPositiveButtonClick(type: CoinBaseResultDialog.Type) {
                    when (type) {
                        CoinBaseResultDialog.Type.PURCHASE_ERROR -> {
                            viewModel.logEvent(AnalyticsConstants.Coinbase.BUY_ERROR_CLOSE)
                            dismiss()
                            findNavController().popBackStack()
                        }
                        else -> {}
                    }
                }
            }
        }
        transactionStateDialog.showNow(parentFragmentManager, "CoinBaseBuyDashDialog")
    }

    private fun setNetworkState(hasInternet: Boolean) {
        binding.networkStatusStub.isVisible = !hasInternet
        binding.previewOfflineGroup.isVisible = hasInternet
    }

    private suspend fun tryBuyDash(): Boolean {
        try {
            AdaptiveDialog.withProgress(getString(R.string.loading), requireActivity()) {
                viewModel.buyDash()
            }
        } catch (ex: Exception) {
            showBuyOrderDialog(ex.message ?: getString(R.string.retry_later_message))
            return false
        }

        return true
    }

    private fun splitNameAndAccount(nameAccount: String?, type: PaymentMethodType): Pair<String, String> {
        nameAccount?.let {
            val match = when (type) {
                PaymentMethodType.BankAccount, PaymentMethodType.Card, PaymentMethodType.PayPal -> {
                    "(\\d+)?\\s?[a-z]?\\*+".toRegex().find(nameAccount)
                }
                PaymentMethodType.Fiat -> {
                    "\\(.*\\)".toRegex().find(nameAccount)
                }
                else -> null
            }

            return match?.range?.first?.let { index ->
                val name = nameAccount.substring(0, index).trim(' ', '-', ',', ':')
                val account = nameAccount.substring(index, nameAccount.length).trim()
                return Pair(name, account)
            } ?: Pair(nameAccount, "")
        }

        return Pair("", "")
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback?.remove()
    }
}
