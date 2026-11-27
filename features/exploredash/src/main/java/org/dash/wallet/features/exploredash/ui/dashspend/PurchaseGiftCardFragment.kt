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

package org.dash.wallet.features.exploredash.ui.dashspend

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentPurchaseCtxspendGiftCardBinding
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.PurchaseGiftCardConfirmDialog
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants.DEFAULT_DISCOUNT_AS_DOUBLE
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.dash.wallet.features.exploredash.utils.toCardFormattedString
import org.slf4j.LoggerFactory
import java.util.Currency

fun min(a: Coin, b: Coin?): Coin {
    return if (b == null || a < b) a else b
}

fun GiftCardPurchaseBlock.message(context: Context, cardCurrency: String?): String {
    val currency = cardCurrency.orEmpty()
    return when (this) {
        GiftCardPurchaseBlock.CURRENCY_LOADING,
        GiftCardPurchaseBlock.CURRENCY_UNKNOWN -> context.getString(R.string.gift_card_currency_unknown)
        GiftCardPurchaseBlock.RATE_LOADING,
        GiftCardPurchaseBlock.RATE_MISSING -> context.getString(R.string.gift_card_rate_missing, currency)
        GiftCardPurchaseBlock.NO_AMOUNT -> context.getString(R.string.gift_card_error)
        GiftCardPurchaseBlock.AMOUNT_NOT_REPRESENTABLE ->
            context.getString(R.string.gift_card_amount_too_precise, currency)
    }
}

/** Blocks that pass by themselves, or that the disabled Continue button already explains. */
private val GiftCardPurchaseBlock.isSilent: Boolean
    get() = this == GiftCardPurchaseBlock.CURRENCY_LOADING || this == GiftCardPurchaseBlock.RATE_LOADING ||
        this == GiftCardPurchaseBlock.NO_AMOUNT

@AndroidEntryPoint
class PurchaseGiftCardFragment : Fragment(R.layout.fragment_purchase_ctxspend_gift_card) {
    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardFragment::class.java)
    }
    private val binding by viewBinding(FragmentPurchaseCtxspendGiftCardBinding::bind)
    private var enterAmountFragment: EnterAmountFragment? = null
    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val exploreViewModel by exploreViewModels<ExploreViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.titleBar.setNavigationOnClickListener {
            enterAmountViewModel.clearSavedState()
            findNavController().popBackStack()
        }

        setPaymentHeader()

        if (savedInstanceState == null) {
            // nothing chosen on another purchase screen may show up on this one
            viewModel.clearOrderSelection()
        }

        // Try to restore from saved state first
        val savedMerchantId = viewModel.getSavedMerchantId()
        val savedProvider = viewModel.getSavedProvider()
        val currentMerchant = exploreViewModel.selectedItem.value
        val currentProvider = viewModel.selectedProvider

        viewLifecycleOwner.lifecycleScope.launch {
            if (savedMerchantId != null && currentMerchant == null &&
                savedProvider != null && currentProvider == null
            ) {
                // restore state if saved merchant exists
                if (!setupMerchant(savedMerchantId, savedProvider)) {
                    findNavController().popBackStack()
                }
            } else if (currentMerchant is Merchant && currentMerchant.merchantId != null &&
                !currentMerchant.source.isNullOrEmpty() && currentProvider != null
            ) {
                setupMerchant(currentMerchant)
            } else {
                // No valid merchant available
                findNavController().popBackStack()
            }
        }

        viewModel.isFixedDenomination.observe(viewLifecycleOwner) {
            renderPurchaseUi()
        }

        viewModel.cardCurrencyState.observe(viewLifecycleOwner) {
            renderPurchaseUi()
        }

        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) { (_, fiat) ->
            if (fiat.currencyCode == viewModel.cardCurrency && viewModel.purchaseBlock == null) {
                PurchaseGiftCardConfirmDialog().show(requireActivity())
            }
        }

        enterAmountViewModel.fiatAmount.observe(viewLifecycleOwner) {
            viewModel.giftCardMerchant.value?.let { merchant ->
                if (!merchant.fixedDenomination) {
                    if (viewModel.setEnteredAmount(it)) {
                        showCardPurchaseLimits()
                    } else if (!it.isZero) {
                        // The amount screen fell back to another currency. Its digits must not be
                        // re-read as the card's currency, so the entry is rebuilt empty. Posted:
                        // this runs inside the entry fragment's own callback.
                        view?.post { renderPurchaseUi() }
                    } else {
                        viewModel.cardCurrency?.let { code -> enterAmountViewModel.selectedCurrencyCode = code }
                    }
                    updateView()
                }
            }
        }

        // Observe giftCardMerchant StateFlow changes
        viewModel.giftCardMerchant.observe(viewLifecycleOwner) { merchant ->
            merchant?.let {
                // Update UI elements that depend on the merchant
                binding.paymentHeaderView.setSubtitle(it.name.orEmpty())
                binding.paymentHeaderView.setPaymentAddressViewIcon(
                    it.logoLocation,
                    R.drawable.ic_image_placeholder
                )

                // Refresh merchant-dependent values
                renderPurchaseUi()

                // Update discount display
                setDiscountHint()

                // Check if merchant is still active
                setMerchantEnabled()
                updateView()
            }
        }

        viewModel.giftCardOrderInfo.observe(viewLifecycleOwner) {
            setDiscountHint()
        }

        viewModel.usdExchangeRate.observe(viewLifecycleOwner) {
            viewModel.balance.value?.let { balance -> updateBalanceLabel(balance) }
        }

        enterAmountViewModel.selectedExchangeRate.observe(viewLifecycleOwner) {
            val cardCurrency = viewModel.cardCurrency
            if (cardCurrency != null && viewModel.isFixedDenomination.value == false &&
                enterAmountViewModel.selectedCurrencyCode != cardCurrency
            ) {
                // the entry went back to the wallet's currency, with or without a rate for it
                view?.post { renderPurchaseUi() }
            }
            updateEntryVisibility()
        }

        viewModel.cardRateState.observe(viewLifecycleOwner) {
            viewModel.balance.value?.let { balance -> updateBalanceLabel(balance) }
            setCardPurchaseLimits()
            setDiscountHint()
            enterAmountViewModel.setMinAmount(viewModel.minCardPurchaseCoin, true)
            enterAmountViewModel.setMaxAmount(
                min(
                    viewModel.maxCardPurchaseCoin,
                    viewModel.balanceWithDiscount
                )
            )
            updateView()
        }

        viewModel.isNetworkAvailable.observe(viewLifecycleOwner) { isConnected ->
            enterAmountFragment?.handleNetworkState(isConnected)
        }

        viewModel.isBlockchainReplaying.observe(viewLifecycleOwner) {
            updateView()
        }

        viewLifecycleOwner.observeOnDestroy {
            // a rotation keeps the order, which an open confirmation dialog is showing
            if (activity?.isChangingConfigurations != true) {
                viewModel.resetSelectedDenomination()
                enterAmountViewModel.clearSavedState()
                // the entry is shared by the whole activity and must not keep the card's currency
                enterAmountViewModel.resetCurrency()
            }
        }
    }

    /** Builds the amount entry or the denominations once the card currency and the mode are known. */
    private fun renderPurchaseUi() {
        if (view == null || !isAdded) {
            return
        }
        val cardCurrency = viewModel.cardCurrency
        val isFixed = viewModel.isFixedDenomination.value
        if (cardCurrency == null || isFixed == null) {
            showNoPurchaseUi()
            return
        }

        if (isFixed) {
            setupMerchantDenominations(cardCurrency)
        } else {
            setupEnterAmountFragment(cardCurrency)
            setCardPurchaseLimits()
        }
        setDiscountHint()
        updateView()
    }

    /**
     * The amount entry only learns its currency symbol from a rate and shows "$" or the symbol of
     * the wallet's currency until then, so it stays hidden until it holds the card currency's rate.
     */
    private fun updateEntryVisibility() {
        if (view == null || !isAdded) {
            return
        }
        val cardCurrency = viewModel.cardCurrency
        val entryCurrency = enterAmountViewModel.selectedExchangeRate.value?.currencyCode
        binding.enterAmountFragmentPlaceholder.isVisible = cardCurrency != null &&
            viewModel.isFixedDenomination.value == false && entryCurrency == cardCurrency
    }

    private fun showNoPurchaseUi() {
        val isUnknown = viewModel.purchaseBlock == GiftCardPurchaseBlock.CURRENCY_UNKNOWN
        binding.enterAmountFragmentPlaceholder.isVisible = false
        binding.fixedDenomText.isVisible = false
        binding.minValue.isVisible = false
        binding.maxValue.isVisible = false
        binding.composeContainer.isVisible = isUnknown
        if (isUnknown) {
            binding.composeContainer.setContent {
                DashWalletTheme {
                    DashButton(
                        modifier = Modifier.padding(20.dp),
                        text = stringResource(R.string.try_again),
                        style = Style.FilledBlue,
                        size = Size.Large,
                        onClick = { retryMerchant() }
                    )
                }
            }
        }
        setDiscountHint()
    }

    private fun retryMerchant() {
        val merchant = viewModel.giftCardMerchant.value ?: return
        viewLifecycleOwner.lifecycleScope.launch { setupMerchant(merchant) }
    }

    private fun setupEnterAmountFragment(cardCurrency: String) {
        updateEntryVisibility()
        binding.composeContainer.isVisible = false
        binding.fixedDenomText.isVisible = false

        val existing = childFragmentManager
            .findFragmentById(R.id.enter_amount_fragment_placeholder) as? EnterAmountFragment
        val entered = enterAmountViewModel.fiatAmount.value
        // After a rotation the order still holds what the entry shows. After the process died it
        // is empty, and so is an entry left in another currency: both start again.
        val isOrdered = entered == null || entered.isZero ||
            (viewModel.giftCardOrderInfo.value.isNotEmpty() && viewModel.getFirstCardValueAsFiat() == entered)
        if (existing != null && enterAmountViewModel.selectedCurrencyCode == cardCurrency && isOrdered) {
            enterAmountFragment = existing
            return
        }

        // an amount typed for another merchant or currency is cleared, never carried over
        enterAmountViewModel.selectedCurrencyCode = cardCurrency
        enterAmountViewModel.clearSavedState()
        viewModel.resetSelectedDenomination()

        val fragment = EnterAmountFragment.newInstance(
            dashToFiat = false,
            showCurrencySelector = false,
            isMaxButtonVisible = false,
            isCurrencyOptionsPickerVisible = false,
            showAmountResultContainer = false,
            faitCurrencyCode = cardCurrency
        )

        fragment.setViewDetails(continueText = getString(R.string.button_continue))

        // replace: a fragment restored after recreation or left by another currency must go
        childFragmentManager
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.enter_amount_fragment_placeholder, fragment)
            .commitNow()
        enterAmountFragment = fragment
    }

    private fun setupMerchantDenominations(cardCurrency: String) {
        binding.enterAmountFragmentPlaceholder.isVisible = false
        binding.composeContainer.isVisible = true
        binding.fixedDenomText.isVisible = true
        val selected = viewModel.giftCardOrderInfo.value.keys.firstOrNull() ?: 0.0
        binding.fixedDenomText.text = formatFixedAmount(selected, cardCurrency)

        binding.composeContainer.setContent {
            DashWalletTheme {
                DenominationsBottomContainer()
            }
        }
    }

    /** if the merchant is not active, then close this fragment */
    private fun setMerchantEnabled(): Boolean {
        if (viewModel.giftCardMerchant.value?.active != true) {
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

        viewModel.giftCardMerchant.value?.let { merchant ->
            if (merchant.fixedDenomination) {
                return
            }
        } ?: return

        if (viewModel.purchaseBlock?.takeIf { it != GiftCardPurchaseBlock.NO_AMOUNT } != null) {
            setDiscountHint()
            return
        }

        val amountFiat = enterAmountViewModel.fiatAmount.value
        amountFiat?.let {
            val isBlockchainReplaying = viewModel.isBlockchainReplaying.value
            if (!viewModel.withinLimits(amountFiat)) {
                binding.minValue.text =
                    getString(R.string.purchase_gift_card_min, viewModel.minCardPurchaseFiat.toCardFormattedString())
                binding.maxValue.text =
                    getString(R.string.purchase_gift_card_max, viewModel.maxCardPurchaseFiat.toCardFormattedString())
                // don't show min/max values if blockchain is replaying
                binding.minValue.isVisible = !isBlockchainReplaying
                binding.maxValue.isVisible = !isBlockchainReplaying
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

        val merchant = viewModel.giftCardMerchant.value ?: run {
            binding.discountValue.isVisible = false
            return
        }
        val block = viewModel.purchaseBlock
        val purchaseAmount = viewModel.getFirstCardValueAsFiat()
        if (block != null || purchaseAmount == null) {
            val message = block?.takeUnless { it.isSilent }?.message(requireContext(), viewModel.cardCurrency)
            binding.discountValue.isVisible = message != null
            if (message != null) {
                binding.discountValue.setTextColor(resources.getColor(R.color.error_red, null))
                binding.discountValue.text = message
            }
            if (block != GiftCardPurchaseBlock.NO_AMOUNT) {
                binding.minValue.isVisible = false
                binding.maxValue.isVisible = false
            }
            return
        }
        val savingsFraction = if (merchant.fixedDenomination) {
            viewModel.getGiftCardDiscount(purchaseAmount.toBigDecimal().toDouble())
        } else {
            merchant.savingsFraction
        }

        // do not show discount if the entered value is zero or the discount is zero
        if (savingsFraction == DEFAULT_DISCOUNT_AS_DOUBLE || purchaseAmount.isZero) {
            binding.discountValue.isVisible = false
            return
        }

        // only show the discount
        binding.discountValue.isVisible = !viewModel.isBlockchainReplaying.value

        if (viewModel.exceedsBalance()) {
            showBalanceError(true)
            return
        }

        if (!viewModel.withinLimits(purchaseAmount)) {
            binding.discountValue.isVisible = false
            return
        }

        binding.discountValue.setTextColor(resources.getColor(R.color.content_primary, null))
        val discountedAmount = purchaseAmount.discountBy(savingsFraction)

        binding.discountValue.text = getString(
            R.string.purchase_gift_card_discount_hint,
            purchaseAmount.toCardFormattedString(),
            discountedAmount.toCardFormattedString(roundUp = true),
            GenericUtils.formatPercent(savingsFraction)
        )
    }

    private fun formatFixedAmount(amount: Double, cardCurrency: String): String =
        GiftCardCurrency.displayFormat(cardCurrency, trimZeroDecimals = true).format(amount)

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
            viewModel.balance.value?.let { balance -> updateBalanceLabel(balance) }
        }

        viewModel.balance.observe(viewLifecycleOwner) { balance ->
            updateBalanceLabel(balance)
        }
    }

    private fun updateBalanceLabel(balance: Coin) {
        val exchangeRate = viewModel.balanceExchangeRate
        var balanceText = viewModel.dashFormat.format(balance).toString()
        exchangeRate?.let { balanceText += " ~ ${exchangeRate.coinToFiat(balance).toCardFormattedString()}" }
        binding.paymentHeaderView.setBalanceValue(balanceText)
    }

    private suspend fun setupMerchant(merchantId: String, provider: String): Boolean {
        val merchant = viewModel.getMerchantById(merchantId)
        return if (merchant != null) {
            viewModel.selectedProvider = GiftCardProviderType.fromProviderName(provider)
            setupMerchant(merchant)
            true
        } else {
            false
        }
    }

    /** [DashSpendViewModel.selectedProvider] must already be set */
    private suspend fun setupMerchant(merchant: Merchant) {
        viewModel.setGiftCardMerchant(merchant)

        lifecycleScope.launch {
            val updatedMerchant = viewModel.updateMerchantDetails(merchant)

            // a slow answer for a merchant the user has left must not replace the current one
            if (viewModel.giftCardMerchant.value?.merchantId != merchant.merchantId) {
                return@launch
            }
            if (setMerchantEnabled()) {
                viewModel.setGiftCardMerchant(updatedMerchant)
                viewModel.setIsFixedDenomination(updatedMerchant.fixedDenomination)
            }
        }
    }

    @Composable
    private fun DenominationsBottomContainer() {
        val merchant = viewModel.giftCardMerchant.collectAsState().value ?: return
        val isReplaying = viewModel.isBlockchainReplaying.collectAsStateWithLifecycle()
        val cardRate = viewModel.cardRateState.collectAsStateWithLifecycle()
        val cardCurrency = viewModel.cardCurrency ?: return

        val colors = LocalDashColors.current
        Box(
            modifier = Modifier.background(
                color = colors.backgroundSecondary,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 0.dp,
                    bottomEnd = 0.dp
                )
            )
        ) {
            val selectedDenomination = viewModel.giftCardOrderInfo.collectAsStateWithLifecycle()
            MerchantDenominations(
                modifier = Modifier.padding(20.dp),
                denominations = merchant.denominations,
                currency = Currency.getInstance(cardCurrency),
                selectedDenomination = selectedDenomination.value.keys.firstOrNull()?.toBigDecimal()?.toDouble(),
                // cardRate is read so that a rate arriving re-evaluates the checks
                canContinue = cardRate.value is CardRateState.Available && viewModel.purchaseBlock == null &&
                    !viewModel.exceedsBalance() && !isReplaying.value,
                onDenominationSelected = { denomination ->
                    val fiat = GiftCardCurrency.toFiat(denomination, cardCurrency)
                    val quantity = selectedDenomination.value.values.firstOrNull() ?: 1
                    viewModel.setGiftCardOrderInfo(fiat, quantity)
                    binding.fixedDenomText.text = formatFixedAmount(denomination, cardCurrency)
                    setDiscountHint()
                },
                onContinue = {
                    if (viewModel.purchaseBlock == null) {
                        PurchaseGiftCardConfirmDialog().show(requireActivity())
                    }
                }
            )
        }
    }

    // taken from SendCoinsFragment.updateView
    private fun updateView() {
        val isReplaying = viewModel.isBlockchainReplaying.value
        enterAmountFragment?.setError(
            if (isReplaying) getString(R.string.send_coins_fragment_hint_replaying) else ""
        )
        enterAmountViewModel.blockContinue = isReplaying || viewModel.purchaseBlock != null
    }
}
