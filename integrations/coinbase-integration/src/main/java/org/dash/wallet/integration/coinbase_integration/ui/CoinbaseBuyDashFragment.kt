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
import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethod
import org.dash.wallet.common.ui.payment_method_picker.PaymentMethodType
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseBuyDashBinding
import org.dash.wallet.integration.coinbase_integration.databinding.KeyboardHeaderViewBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinbasePaymentMethod
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseServicesViewModel

@AndroidEntryPoint
class CoinbaseBuyDashFragment: Fragment(R.layout.fragment_coinbase_buy_dash) {
    private val binding by viewBinding(FragmentCoinbaseBuyDashBinding::bind)
    private val viewModel by viewModels<CoinbaseServicesViewModel>()
    private val amountViewModel by activityViewModels<EnterAmountViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
                isMaxButtonVisible = false
            )
            val headerBinding = KeyboardHeaderViewBinding.inflate(layoutInflater, null, false)
            fragment.setViewDetails(getString(R.string.button_continue), headerBinding.root)

            childFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }
        }

        setupPaymentMethodPayment()
        amountViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            Log.i("COINBASELOG", "fiat: ${pair.second}, coin: ${pair.first}")
        }
    }

    private fun setupPaymentMethodPayment() {
        val coinbasePaymentMethods = listOf(
            CoinbasePaymentMethod(
                id = "239d7b3fsd76-ke23-5de7-8185-3657d7b526e",
                type = "fiat_account",
                name = "Cash (USD)",
                currency= "USD",
                allowBuy = false,
                allowSell = true
            ),
            CoinbasePaymentMethod(
                id = "127b4d76-a1a0-5de7-8185-3657d7b526e",
                type = "secure3d_card",
                name = "Debit card 5318****1234",
                currency = "USD",
                allowBuy = true,
                allowSell = false
            ),
            CoinbasePaymentMethod(
                id = "83562370-3e5c-51db-87da-752af5ab9559",
                type = "ach_bank_account",
                name = "Bank of America - Busi... ********2891",
                currency = "USD",
                allowBuy = true,
                allowSell = true
            ),
            CoinbasePaymentMethod(
                id = "0000621110-4sdf-51db-87da-752af5ab9559",
                type = "worldpay_card",
                name = "3056*********5904",
                currency = "USD",
                allowBuy = true,
                allowSell = false
            ),
            CoinbasePaymentMethod(
                id = "9d7382-a1a0-5de7-8185-3657d7b526e",
                type = "worldpay_card",
                name = "Credit card 4191*********8722",
                currency = "USD",
                allowBuy = true,
                allowSell = false
            ),
            CoinbasePaymentMethod(
                id = "832iwi-n13-5de7-8185-9283dnfskf",
                type = "paypal_account",
                name = "PayPal - q***a@gmail.com",
                currency = "USD",
                allowBuy = true,
                allowSell = false
            )
        )
        val paymentMethods = coinbasePaymentMethods
            .filter { it.allowBuy }
            .map {
                val type = paymentMethodTypeFromCoinbaseType(it.type)
                val nameAccountPair = splitNameAndAccount(it.name)
                PaymentMethod(
                    nameAccountPair.first,
                    nameAccountPair.second,
                    "", // set "Checking" to get "****1234 • Checking" in subtitle
                    paymentMethodType = type
                )
            }
        binding.paymentMethodPicker.paymentMethods = paymentMethods
    }

    private fun splitNameAndAccount(nameAccount: String?): Pair<String, String> {
        nameAccount?.let {
            val match = "(\\d+)?\\s?[a-z]?\\*+".toRegex().find(nameAccount)
            match?.range?.first?.let { index ->
                val name = nameAccount.substring(0, index).trim(' ', '-', ',', ':')
                val account = nameAccount.substring(index, nameAccount.length).trim()
                return Pair(name, account)
            }
        }

        return Pair("", "")
    }

    private fun paymentMethodTypeFromCoinbaseType(type: String): PaymentMethodType {
        return when (type) {
            "fiat_account" -> PaymentMethodType.Fiat
            "secure3d_card", "worldpay_card", "credit_card", "debit_card" -> PaymentMethodType.Card
            "ach_bank_account", "sepa_bank_account",
            "ideal_bank_account", "eft_bank_account", "interac" -> PaymentMethodType.BankAccount
            "bank_wire" -> PaymentMethodType.WireTransfer
            "paypal_account" -> PaymentMethodType.PayPal
            else -> PaymentMethodType.Unknown
        }
    }
}