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

package org.dash.wallet.features.exploredash.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.OnHideBalanceClickedListener
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.dialogs.PurchaseGiftCardConfirmDialog
import org.slf4j.LoggerFactory

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class PurchaseGiftCardFragment : Fragment(R.layout.fragment_purchase_gift_card) {
    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardFragment::class.java)
    }

    private val binding by viewBinding(FragmentPurchaseGiftCardBinding::bind)
    private val viewModel: PurchaseGiftCardViewModel by activityViewModels()
    private var enterAmountFragment: EnterAmountFragment? = null
    private val exploreViewModel: ExploreViewModel by navGraphViewModels(R.id.explore_dash) { defaultViewModelProviderFactory }
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    var selectedMerchent: Merchant? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (savedInstanceState == null) {
            val fragment = EnterAmountFragment.newInstance(
                dashToFiat = false,
                showCurrencySelector = false,
                isMaxButtonVisible = false,
                isCurrencyOptionsPickerVisible = false,
                hideAmountResultContainer = true,
                faitCurrencyCode = if (viewModel.isUserSettingFaitIsNotUSD)Constants.USD_CURRENCY else null
            )

            fragment.setViewDetails(getString(R.string.button_next))

            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.enter_amount_fragment_placeholder, fragment)
                .commitNow()
            enterAmountFragment = fragment
        }
        setPaymentHeader()

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            selectedMerchent?.let { selectedMerchant ->
                exploreViewModel.purchaseGiftCardData = (Pair(it, selectedMerchant))
                PurchaseGiftCardConfirmDialog().show(requireActivity())
            }
        }

        exploreViewModel.selectedItem.value?.let { merchant ->
            if (merchant is Merchant && merchant.merchantId != null && !merchant.source.isNullOrEmpty()) {
                this.selectedMerchent = merchant
                binding.paymentHeaderView.setPaymentAddressViewSubtitle(merchant.name.orEmpty())
                binding.paymentHeaderView.setPaymentAddressViewIcon(merchant.logoLocation)
            }
        }

        // TODO
        val merchantMinimumCardPurchase = 10.00
        val merchantMaximumCardPurchase = 500.00

        val minFaitValue = Fiat.parseFiat(
            Constants.USD_CURRENCY,
            merchantMinimumCardPurchase.toString()
        )

        val maxFaitValue = Fiat.parseFiat(
            Constants.USD_CURRENCY,
            merchantMaximumCardPurchase.toString()
        )

        binding.minValue.text = getString(
            R.string.purchase_gift_card_min,
            GenericUtils.fiatToString(minFaitValue)
        )
        binding.maxValue.text = getString(
            R.string.purchase_gift_card_max,
            GenericUtils.fiatToString(maxFaitValue)
        )

        viewModel.usdExchangeRate.observe(viewLifecycleOwner) { rate ->

            val exchangeRate = ExchangeRate(Coin.COIN, rate.fiat)
            if (viewModel.isUserSettingFaitIsNotUSD) {
                viewModel.balance.value?.let { balance ->
                    updateBalanceLabel(balance, viewModel.usdExchangeRate.value)
                }
            }

            val minValue = exchangeRate.fiatToCoin(minFaitValue) ?: Coin.ZERO
            val maxValue = exchangeRate.fiatToCoin(maxFaitValue) ?: Coin.ZERO

            enterAmountViewModel.setMinAmount(minValue, true)
            enterAmountViewModel.setMaxAmount(maxValue)
        }
    }

    private fun setPaymentHeader() {
        binding.paymentHeaderView.setPaymentAddressViewTitle(getString(R.string.explore_option_buy))
        binding.paymentHeaderView.setPaymentAddressViewProposition(getString(R.string.purchase_gift_card_at))
        binding.paymentHeaderView.setOnHideBalanceClickedListener(
            onHideBalanceClickedListener = object : OnHideBalanceClickedListener {
                override fun onHideBalanceClicked(view: View) {
                    viewModel.balance.value?.let { balance ->
                        updateBalanceLabel(balance, viewModel.usdExchangeRate.value)
                    }
                }
            }
        )

        viewModel.balance.observe(viewLifecycleOwner) { balance ->
            updateBalanceLabel(balance, viewModel.usdExchangeRate.value)
        }
    }

    private fun updateBalanceLabel(balance: Coin, rate: org.dash.wallet.common.data.ExchangeRate?) {
        val exchangeRate = rate?.let { ExchangeRate(Coin.COIN, it.fiat) }
        var balanceText = viewModel.dashFormat.format(balance).toString()
        exchangeRate?.let { balanceText += " ~ ${GenericUtils.fiatToString(exchangeRate.coinToFiat(balance))}" }
        binding.paymentHeaderView.setBalanceValue(balanceText)
    }
}
