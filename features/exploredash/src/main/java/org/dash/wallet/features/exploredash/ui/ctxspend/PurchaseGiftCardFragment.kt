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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentPurchaseCtxspendGiftCardBinding
import org.dash.wallet.features.exploredash.ui.ctxspend.dialogs.PurchaseGiftCardConfirmDialog
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants.DEFAULT_DISCOUNT_AS_DOUBLE
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import java.text.NumberFormat
import java.util.Currency

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
    private val fixedAmountFormat = NumberFormat.getCurrencyInstance().apply {
        this.currency = Currency.getInstance(Constants.USD_CURRENCY)
        minimumFractionDigits = 0
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            enterAmountViewModel.clearSavedState()
            findNavController().popBackStack()
        }

        setPaymentHeader()

        // Try to restore from saved state first
        val savedMerchantId = viewModel.getSavedMerchantId()
        val currentMerchant = exploreViewModel.selectedItem.value

        viewLifecycleOwner.lifecycleScope.launch {
            if (savedMerchantId != null && currentMerchant == null) {
                // restore state if saved merchant exists
                if (!setupMerchant(savedMerchantId)) {
                    findNavController().popBackStack()
                }
            } else if (currentMerchant is Merchant && currentMerchant.merchantId != null &&
                !currentMerchant.source.isNullOrEmpty()
            ) {
                setupMerchant(currentMerchant)
            } else {
                // No valid merchant available
                findNavController().popBackStack()
            }
        }

        viewModel.isFixedDenomination.observe(viewLifecycleOwner) { isFixed ->
            if (isFixed == null) {
                // Not resolved yet
                return@observe
            }

            if (isFixed) {
                setupMerchantDenominations()
            } else {
                setupEnterAmountFragment()
                setCardPurchaseLimits()
            }
        }

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            PurchaseGiftCardConfirmDialog().show(requireActivity())
        }

        enterAmountViewModel.fiatAmount.observe(viewLifecycleOwner) {
            viewModel.giftCardMerchant?.let { merchant ->
                if (!merchant.fixedDenomination) {
                    showCardPurchaseLimits()
                    viewModel.setGiftCardPaymentValue(it)
                }
            }
        }

        viewModel.giftCardPaymentValue.observe(viewLifecycleOwner) {
            setDiscountHint()
        }

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

        viewLifecycleOwner.observeOnDestroy {
            viewModel.resetSelectedDenomination()
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

        fragment.setViewDetails(continueText = getString(R.string.button_continue))

        childFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .add(R.id.enter_amount_fragment_placeholder, fragment)
            .commitNow()
        enterAmountFragment = fragment

        binding.enterAmountFragmentPlaceholder.isVisible = true
        binding.composeContainer.isVisible = false
        binding.fixedDenomText.isVisible = false
    }

    private fun setupMerchantDenominations() {
        binding.enterAmountFragmentPlaceholder.isVisible = false
        binding.composeContainer.isVisible = true
        binding.fixedDenomText.isVisible = true
        binding.fixedDenomText.text = fixedAmountFormat.format(0)

        binding.composeContainer.setContent {
            DenominationsBottomContainer()
        }
    }

    /** if the merchant is not active, then close this fragment */
    private fun setMerchantEnabled(): Boolean {
        if (viewModel.giftCardMerchant?.active != true) {
            findNavController().popBackStack()
            return false
        }

        return true
    }

    private fun setCardPurchaseLimits() {
        viewModel.refreshMinMaxCardPurchaseValues()
        enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
        enterAmountViewModel.setMaxAmount(min(viewModel.maxCardPurchaseCoin, viewModel.balanceWithDiscount))
        showCardPurchaseLimits()
    }

    private fun showCardPurchaseLimits() {
        if (view == null || !isAdded) {
            return
        }

        viewModel.giftCardMerchant?.let { merchant ->
            if (merchant.fixedDenomination) {
                return
            }
        } ?: return

        val amountFiat = enterAmountViewModel.fiatAmount.value
        amountFiat?.let {
            if (!viewModel.withinLimits(amountFiat)) {
                binding.minValue.text =
                    getString(R.string.purchase_gift_card_min, viewModel.minCardPurchaseFiat.toFormattedString())
                binding.maxValue.text =
                    getString(R.string.purchase_gift_card_max, viewModel.maxCardPurchaseFiat.toFormattedString())
                binding.minValue.isVisible = true
                binding.maxValue.isVisible = true
                binding.discountValue.isVisible = false
                return
            }
            val amountDash = enterAmountViewModel.amount.value
            showBalanceError(amountDash?.isGreaterThan(viewModel.balance.value) == true)
        }

        binding.minValue.isVisible = false
        binding.maxValue.isVisible = false
        setDiscountHint()
    }

    private fun setDiscountHint() {
        if (view == null || !isAdded) {
            return
        }

        val merchant = viewModel.giftCardMerchant ?: run {
            binding.discountValue.isVisible = false
            return
        }
        val savingsFraction = merchant.savingsFraction

        if (savingsFraction == DEFAULT_DISCOUNT_AS_DOUBLE ||
            viewModel.giftCardPaymentValue.value.isZero
        ) {
            binding.discountValue.isVisible = false
            return
        }

        binding.discountValue.isVisible = true
        val selectedRate = viewModel.usdExchangeRate.value

        if (selectedRate == null) {
            binding.discountValue.setTextColor(resources.getColor(R.color.error_red, null))
            binding.discountValue.text = getString(R.string.exchange_rate_not_found)
            return
        }

        if (exceedsBalance()) {
            showBalanceError(true)
            return
        }

        if (!viewModel.withinLimits(viewModel.giftCardPaymentValue.value)) {
            binding.discountValue.isVisible = false
            return
        }

        binding.discountValue.setTextColor(resources.getColor(R.color.content_primary, null))
        val purchaseAmount = viewModel.giftCardPaymentValue.value
        val discountedAmount = purchaseAmount.discountBy(savingsFraction)

        binding.discountValue.text = getString(
            R.string.purchase_gift_card_discount_hint,
            purchaseAmount.toFormattedString(),
            discountedAmount.toFormattedStringRoundUp(),
            GenericUtils.formatPercent(savingsFraction)
        )
    }

    private fun showBalanceError(show: Boolean) {
        if (view == null || !isAdded) {
            return
        }

        if (show) {
            binding.discountValue.text = getString(R.string.insufficient_money_msg)
            binding.discountValue.setTextColor(resources.getColor(R.color.error_red, null))
            binding.discountValue.isVisible = true
            binding.minValue.isVisible = false
            binding.maxValue.isVisible = false
        } else {
            setDiscountHint()
        }
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

    private suspend fun setupMerchant(merchantId: String): Boolean {
        val merchant = viewModel.getMerchantById(merchantId)
        return if (merchant != null) {
            setupMerchant(merchant)
            true
        } else {
            false
        }
    }

    private suspend fun setupMerchant(merchant: Merchant) {
        viewModel.giftCardMerchant = merchant
        binding.paymentHeaderView.setSubtitle(merchant.name.orEmpty())
        binding.paymentHeaderView.setPaymentAddressViewIcon(
            merchant.logoLocation,
            R.drawable.ic_image_placeholder
        )
        viewModel.setIsFixedDenomination(merchant.fixedDenomination)
        viewModel.updateMerchantDetails(merchant)

        if (setMerchantEnabled()) {
            viewModel.setIsFixedDenomination(merchant.fixedDenomination)
            setCardPurchaseLimits()
        }
    }

    private fun exceedsBalance(): Boolean {
        val rate = viewModel.usdExchangeRate.value

        if (rate == null) {
            return false
        }

        val balanceWithDiscount = viewModel.balanceWithDiscount

        if (balanceWithDiscount == null) {
            return false
        }

        val myRate = ExchangeRate(rate.fiat)
        val amountDash = myRate.fiatToCoin(viewModel.giftCardPaymentValue.value)

        return amountDash.isGreaterThan(balanceWithDiscount)
    }

    @Composable
    private fun DenominationsBottomContainer() {
        val merchant = viewModel.giftCardMerchant ?: return

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
            val selectedDenomination = viewModel.giftCardPaymentValue.collectAsStateWithLifecycle()
            MerchantDenominations(
                modifier = Modifier.padding(20.dp),
                denominations = merchant.denominations,
                currency = Currency.getInstance(Constants.USD_CURRENCY),
                selectedDenomination = selectedDenomination.value.toBigDecimal().toInt(),
                canContinue = !exceedsBalance(),
                onDenominationSelected = { denomination ->
                    val fiat = Fiat.parseFiat(Constants.USD_CURRENCY, denomination.toString())
                    viewModel.setGiftCardPaymentValue(fiat)
                    binding.fixedDenomText.text = fixedAmountFormat.format(denomination)
                    setDiscountHint()
                },
                onContinue = {
                    PurchaseGiftCardConfirmDialog().show(requireActivity())
                }
            )
        }
    }
}
