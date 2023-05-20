/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dashdirect

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.dashdirect.dialogs.PurchaseGiftCardConfirmDialog
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants.DEFAULT_DISCOUNT
import org.dash.wallet.features.exploredash.utils.exploreViewModels

@AndroidEntryPoint
class PurchaseGiftCardFragment : Fragment(R.layout.fragment_purchase_gift_card) {
    private val binding by viewBinding(FragmentPurchaseGiftCardBinding::bind)
    private var enterAmountFragment: EnterAmountFragment? = null
    private val viewModel by exploreViewModels<DashDirectViewModel>()
    private val exploreViewModel by exploreViewModels<ExploreViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener { findNavController().popBackStack() }

        if (savedInstanceState == null) {
            val fragment =
                EnterAmountFragment.newInstance(
                    dashToFiat = false,
                    showCurrencySelector = false,
                    isMaxButtonVisible = false,
                    isCurrencyOptionsPickerVisible = false,
                    showAmountResultContainer = false,
                    faitCurrencyCode = Constants.USD_CURRENCY
                )

            fragment.setViewDetails(getString(R.string.button_next))

            childFragmentManager
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.enter_amount_fragment_placeholder, fragment)
                .commitNow()
            enterAmountFragment = fragment
        }
        setPaymentHeader()

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            viewModel.giftCardPaymentValue = it.second
            PurchaseGiftCardConfirmDialog().show(requireActivity())
        }

        exploreViewModel.selectedItem.value?.let { merchant ->
            if (merchant is Merchant && merchant.merchantId != null && !merchant.source.isNullOrEmpty()) {
                viewModel.giftCardMerchant = merchant
                binding.paymentHeaderView.setSubtitle(merchant.name.orEmpty())
                binding.paymentHeaderView.setPaymentAddressViewIcon(
                    merchant.logoLocation,
                    R.drawable.ic_image_placeholder
                )

                lifecycleScope.launch {
                    viewModel.updateMerchantDetails(merchant)
                    setCardPurchaseLimits()
                    setDiscountHint()
                }
            }
        }

        enterAmountViewModel.amount.observe(viewLifecycleOwner) { showCardPurchaseLimits() }

        viewModel.usdExchangeRate.observe(viewLifecycleOwner) { rate ->
            viewModel.balance.value?.let { balance -> updateBalanceLabel(balance, rate) }
            setCardPurchaseLimits()
            setDiscountHint()
            enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
            enterAmountViewModel.setMaxAmount(viewModel.maxCardPurchaseCoin)
        }

        viewModel.isNetworkAvailable.observe(viewLifecycleOwner) { isConnected ->
            enterAmountFragment?.handleNetworkState(isConnected)
        }
    }

    private fun setCardPurchaseLimits() {
        viewModel.refreshMinMaxCardPurchaseValues()
        enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
        enterAmountViewModel.setMaxAmount(viewModel.maxCardPurchaseCoin)
        showCardPurchaseLimits()
    }

    private fun showCardPurchaseLimits() {
        val purchaseAmount = enterAmountViewModel.amount.value
        purchaseAmount?.let {
            if (purchaseAmount.isLessThan(viewModel.minCardPurchaseCoin) ||
                purchaseAmount.isGreaterThan(viewModel.maxCardPurchaseCoin)
            ) {
                binding.minValue.text =
                    getString(R.string.purchase_gift_card_min, viewModel.minCardPurchaseFiat.toFormattedString())
                binding.maxValue.text =
                    getString(R.string.purchase_gift_card_max, viewModel.maxCardPurchaseFiat.toFormattedString())
                binding.minValue.isVisible = true
                binding.maxValue.isVisible = true
                hideDiscountHint()
                return
            }
        }

        binding.minValue.isVisible = false
        binding.maxValue.isVisible = false
        setDiscountHint()
    }

    private fun setDiscountHint() {
        val merchant = viewModel.giftCardMerchant
        val savingsPercentage = merchant.savingsPercentage ?: DEFAULT_DISCOUNT

        if (savingsPercentage != DEFAULT_DISCOUNT) {
            val purchaseAmount = enterAmountViewModel.amount.value
            if (purchaseAmount != null && purchaseAmount != Coin.ZERO) {
                val rate = enterAmountViewModel.selectedExchangeRate.value
                val myRate = ExchangeRate(rate!!.fiat)
                binding.discountValue.text =
                    getString(
                        R.string.purchase_gift_card_discount_hint,
                        myRate.coinToFiat(purchaseAmount).toFormattedString(),
                        viewModel.getDiscountedAmount(
                            purchaseAmount,
                            savingsPercentage
                        )?.toFormattedStringRoundUp() ?: "",
                        GenericUtils.formatPercent(savingsPercentage)
                    )
                binding.discountValue.isVisible = true
            } else {
                hideDiscountHint()
            }
        } else {
            hideDiscountHint()
        }
    }

    private fun hideDiscountHint() {
        binding.discountValue.isVisible = false
    }

    private fun setPaymentHeader() {
        binding.paymentHeaderView.setTitle(getString(R.string.explore_option_buy))
        binding.paymentHeaderView.setProposition(getString(R.string.purchase_gift_card_at))
        binding.paymentHeaderView.setOnShowHideBalanceClicked {
            binding.paymentHeaderView.triggerRevealBalance()
            viewModel.balance.value?.let { balance ->
                updateBalanceLabel(balance, viewModel.usdExchangeRate.value)
            }
        }

        viewModel.balance.observe(viewLifecycleOwner) { balance ->
            updateBalanceLabel(balance, viewModel.usdExchangeRate.value)
        }
    }

    private fun updateBalanceLabel(balance: Coin, rate: org.dash.wallet.common.data.entity.ExchangeRate?) {
        val exchangeRate = rate?.let { ExchangeRate(Coin.COIN, it.fiat) }
        var balanceText = viewModel.dashFormat.format(balance).toString()
        exchangeRate?.let { balanceText += " ~ ${exchangeRate.coinToFiat(balance).toFormattedString()}" }
        binding.paymentHeaderView.setBalanceValue(balanceText)
    }
}
