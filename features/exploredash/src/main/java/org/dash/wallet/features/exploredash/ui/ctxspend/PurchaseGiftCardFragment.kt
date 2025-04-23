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

package org.dash.wallet.features.exploredash.ui.ctxspend

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentPurchaseCtxspendGiftCardBinding
import org.dash.wallet.features.exploredash.ui.ctxspend.dialogs.PurchaseGiftCardConfirmDialog
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants.DEFAULT_DISCOUNT_AS_DOUBLE
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import java.util.Currency
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.MyTheme

fun min(a: Coin, b: Coin?): Coin {
    return if (b == null || a < b) a else b
}

@AndroidEntryPoint
class PurchaseGiftCardFragment : Fragment(R.layout.fragment_purchase_ctxspend_gift_card) {
    private val binding by viewBinding(FragmentPurchaseCtxspendGiftCardBinding::bind)
    private var enterAmountFragment: EnterAmountFragment? = null
    private val viewModel by exploreViewModels<CTXSpendViewModel>()
    private val exploreViewModel by exploreViewModels<ExploreViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener { findNavController().popBackStack() }
        
        setPaymentHeader()

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
                    setMerchantEnabled()
                    
                    // Check if merchant has fixed denominations
                    if (merchant.fixedDenomination) {
                        setupMerchantDenominations()
                    } else {
                        setupEnterAmountFragment()
                    }
                    
                    setCardPurchaseLimits()
                    setDiscountHint()
                }
            }
        }

        if (savedInstanceState == null && !viewModel.giftCardMerchant.fixedDenomination) {
            setupEnterAmountFragment()
        }

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            viewModel.giftCardPaymentValue = it.second
            PurchaseGiftCardConfirmDialog().show(requireActivity())
        }

        enterAmountViewModel.amount.observe(viewLifecycleOwner) { showCardPurchaseLimits() }

        viewModel.usdExchangeRate.observe(viewLifecycleOwner) { rate ->
            viewModel.balance.value?.let { balance -> updateBalanceLabel(balance, rate) }
            setCardPurchaseLimits()
            setDiscountHint()
            enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
            enterAmountViewModel.setMaxAmount(
                min(
                    viewModel.maxCardPurchaseCoin,
                    viewModel.balanceWithDiscount
                )
            )
        }

        viewModel.isNetworkAvailable.observe(viewLifecycleOwner) { isConnected ->
            enterAmountFragment?.handleNetworkState(isConnected)
        }
    }
    
    private fun setupEnterAmountFragment() {
        val fragment = EnterAmountFragment.newInstance(
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
        
        binding.enterAmountFragmentPlaceholder.isVisible = true
        binding.composeContainer.isVisible = false
    }
    
    private fun setupMerchantDenominations() {
        binding.enterAmountFragmentPlaceholder.isVisible = false
        binding.composeContainer.isVisible = true

        binding.composeContainer.setContent {
            DenominationsBottomContainer()
        }
    }

    /** if the merchant is not active, then close this fragment */
    private fun setMerchantEnabled() {
        if (viewModel.giftCardMerchant.active == false) {
            findNavController().popBackStack()
        }
    }

    private fun setCardPurchaseLimits() {
        viewModel.refreshMinMaxCardPurchaseValues()
        enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
        enterAmountViewModel.setMaxAmount(min(viewModel.maxCardPurchaseCoin, viewModel.balanceWithDiscount))
        showCardPurchaseLimits()
    }

    private fun showCardPurchaseLimits() {
        // For fixed denomination merchants, we don't need to show limits
        if (viewModel.giftCardMerchant.fixedDenomination) {
            binding.minValue.isVisible = false
            binding.maxValue.isVisible = false
            return
        }
        
        val purchaseAmount = enterAmountViewModel.amount.value
        purchaseAmount?.let {
            val balance = viewModel.balanceWithDiscount
            balance ?.let {
                if (purchaseAmount.isGreaterThan(balance)) {
                    showBalanceError(purchaseAmount.isGreaterThan(balance))
                    return
                }
            }
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
            showBalanceError(purchaseAmount.isGreaterThan(viewModel.balance.value))
        }

        binding.minValue.isVisible = false
        binding.maxValue.isVisible = false
        setDiscountHint()
    }

    private fun setDiscountHint() {
        val merchant = viewModel.giftCardMerchant
        val savingsFraction = merchant.savingsFraction

        if (savingsFraction != DEFAULT_DISCOUNT_AS_DOUBLE) {
            if (merchant.fixedDenomination && viewModel.selectedDenomination.value != null) {
                // For fixed denomination merchants, use the selected denomination to show discount
                val fiat = Fiat.valueOf(Constants.USD_CURRENCY, viewModel.selectedDenomination.value!!.toLong())
                val selectedRate = viewModel.usdExchangeRate.value
                if (selectedRate != null) {
                    val myRate = ExchangeRate(selectedRate.fiat)
                    val discountedAmount = viewModel.getDiscountedAmount(
                        myRate.fiatToCoin(fiat),
                        savingsFraction
                    )
                    
                    binding.discountValue.text = getString(
                        R.string.purchase_gift_card_discount_hint,
                        fiat.toFormattedString(),
                        discountedAmount?.toFormattedStringRoundUp() ?: "",
                        GenericUtils.formatPercent(savingsFraction)
                    )
                    binding.discountValue.setTextColor(resources.getColor(R.color.content_primary))
                    binding.discountValue.isVisible = true
                } else {
                    hideDiscountHint()
                }
            } else {
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
                                savingsFraction
                            )?.toFormattedStringRoundUp() ?: "",
                            GenericUtils.formatPercent(savingsFraction)
                        )
                    binding.discountValue.setTextColor(resources.getColor(R.color.content_primary))
                    binding.discountValue.isVisible = true
                } else {
                    hideDiscountHint()
                }
            }
        } else {
            hideDiscountHint()
        }
    }

    private fun showBalanceError(show: Boolean) {
        if (show) {
            binding.discountValue.text = getString(R.string.insufficient_money_msg)
            binding.discountValue.setTextColor(resources.getColor(R.color.error_red))
            binding.discountValue.isVisible = true
            binding.minValue.isVisible = false
            binding.maxValue.isVisible = false
        } else {
            setDiscountHint()
        }
    }

    private fun hideDiscountHint() {
        binding.discountValue.isVisible = false
    }

    private fun setPaymentHeader() {
        binding.paymentHeaderView.setTitle(getString(R.string.explore_option_buy))
        binding.paymentHeaderView.setPreposition(getString(R.string.purchase_gift_card_at))
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

    @Composable
    private fun DenominationsBottomContainer() {
        Box(
            modifier = Modifier.background(
                color = MyTheme.Colors.backgroundSecondary,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                )
            )
        ) {
            val selectedDenomination = viewModel.selectedDenomination.collectAsStateWithLifecycle()
            MerchantDenominations(
                modifier = Modifier.padding(20.dp),
                denominations = viewModel.giftCardMerchant.denominations,
                currency = Currency.getInstance(Constants.USD_CURRENCY),
                selectedDenomination = selectedDenomination.value,
                onDenominationSelected = { denomination ->
                    viewModel.selectDenomination(denomination)
                    val fiat = Fiat.valueOf(Constants.USD_CURRENCY, denomination.toLong())
                    viewModel.giftCardPaymentValue = fiat
                },
                onContinue = {
                    viewModel.selectedDenomination.value?.let { denomination ->
                        val fiat = Fiat.valueOf(Constants.USD_CURRENCY, denomination.toLong())
                        viewModel.giftCardPaymentValue = fiat
                        PurchaseGiftCardConfirmDialog().show(requireActivity())
                    }
                }
            )
        }
    }
}
