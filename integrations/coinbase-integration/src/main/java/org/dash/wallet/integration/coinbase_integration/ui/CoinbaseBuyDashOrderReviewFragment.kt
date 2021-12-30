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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.payment_method_picker.CardUtils
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashOrderReviewBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseBuyDashOrderReviewViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel

class CoinbaseBuyDashOrderReviewFragment: Fragment(R.layout.fragment_coinbase_buy_dash_order_review) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashOrderReviewBinding::bind)
    private val viewModel by viewModels<CoinbaseBuyDashOrderReviewViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        arguments?.let {

            CoinbaseBuyDashOrderReviewFragmentArgs.fromBundle(it).paymentMethod.apply {
                binding.contentOrderReview.paymentMethodName.text = this.name
                val cardIcon = if (this.paymentMethodType == PaymentMethodType.Card) {
                    CardUtils.getCardIcon(this.account)
                } else {
                    null
                }
                binding.contentOrderReview.paymentMethodName.isVisible = cardIcon == null
                binding.contentOrderReview.paymentMethodIcon.setImageResource(cardIcon ?: 0)
                binding.contentOrderReview.account.text = this.account
            }

            CoinbaseBuyDashOrderReviewFragmentArgs.fromBundle(it).placeBuyOrderUIModel.apply {
                binding.contentReviewBuyOrderDashAmount.dashAmount.text = this.dashAmount
                binding.contentReviewBuyOrderDashAmount.message.text = getString(R.string.you_will_receive_dash_on_your_dash_wallet,this.dashAmount)
                binding.contentOrderReview.purchaseAmount.text =
                    getString(R.string.fiat_balance_with_currency,this.purchaseAmount,GenericUtils.currencySymbol(this.purchaseCurrency))
                binding.contentOrderReview.coinbaseFeeAmount.text =
                getString(R.string.fiat_balance_with_currency, this.coinBaseFeeAmount,GenericUtils.currencySymbol( this.coinbaseFeeCurrency))
                binding.contentOrderReview.totalAmount.text =
                getString(R.string.fiat_balance_with_currency,this.totalAmount,GenericUtils.currencySymbol(this.totalCurrency))
            }

        }

    }

}