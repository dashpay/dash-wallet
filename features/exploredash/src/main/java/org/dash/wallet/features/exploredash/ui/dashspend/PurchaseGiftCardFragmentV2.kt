/*
 * Copyright 2025 Dash Core Group.
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.explore.model.Merchant
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.PurchaseGiftCardConfirmDialog
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.util.Currency
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
import androidx.lifecycle.lifecycleScope
import org.bitcoinj.core.Coin
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.features.exploredash.ui.explore.dialogs.ExploreDashInfoDialog
import kotlin.math.max

@AndroidEntryPoint
class PurchaseGiftCardFragmentV2 : Fragment() {

    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardFragmentV2::class.java)
    }

    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val exploreViewModel by exploreViewModels<ExploreViewModel>()

    private val currencyFormat = NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(Constants.USD_CURRENCY)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val merchant by viewModel.giftCardMerchant.collectAsStateWithLifecycle()
            val isFixedDenomination by viewModel.isFixedDenomination.collectAsStateWithLifecycle()
            val isMultiple by viewModel.isFixedDenominationMultiple.collectAsStateWithLifecycle()
            val isBlockchainReplaying by viewModel.isBlockchainReplaying.collectAsStateWithLifecycle()
            val balance by viewModel.balance.asFlow().collectAsStateWithLifecycle(null)
            val exchangeRate by viewModel.usdExchangeRate.asFlow().collectAsStateWithLifecycle(null)

            var amountText by rememberSaveable { mutableStateOf("0") }
            val denominationQuantities = remember { mutableStateMapOf<Double, Int>() }
            var showBalance by remember { mutableStateOf(false) }
            var minFiat by remember { mutableStateOf<Fiat?>(null) }
            var maxFiat by remember { mutableStateOf<Fiat?>(null) }

            // Refresh min/max values whenever the exchange rate or merchant changes.
            // Keying on both is necessary because the merchant loads asynchronously after
            // the exchange rate, so a rate-only key would read before the merchant is set.
            LaunchedEffect(exchangeRate, merchant) {
                viewModel.refreshMinMaxCardPurchaseValues()
                minFiat = viewModel.minCardPurchaseFiat
                maxFiat = viewModel.maxCardPurchaseFiat
            }

            // When isFixed switches, reset local state
            LaunchedEffect(isFixedDenomination) {
                amountText = "0"
                denominationQuantities.clear()
            }

            // Compute the purchase mode from ViewModel state
            val mode = remember(isFixedDenomination, merchant, maxFiat, isMultiple) {
                buildPurchaseMode(isFixedDenomination, isMultiple, merchant)
            }

            // Balance text
            val (dashBalanceString, fiatBalanceString) = remember(balance, exchangeRate) {
                buildFiatBalanceText(balance, exchangeRate)
            }
            
            val fiatBalance = remember(balance, exchangeRate) {
                buildFiatBalance(balance, exchangeRate)
            }

            // Total amount for multiple/fixed modes
            val totalDouble = denominationQuantities.entries.sumOf { (denom, qty) -> denom * qty }
            val totalAmountText = if (totalDouble > 0) currencyFormat.format(totalDouble) else "$0.00"

            // canContinue logic per mode
            val canContinue = when (mode) {
                is GiftCardPurchaseMode.FlexibleSingle -> {
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    val min = minFiat?.toBigDecimal()?.toDouble() ?: 0.0
                    val max = maxFiat?.toBigDecimal()?.toDouble() ?: Double.MAX_VALUE
                    val balanceMax = fiatBalance.toBigDecimal().toDouble()
                    amount > 0 && amount >= min && amount <= max && amount < balanceMax && !isBlockchainReplaying
                }
                is GiftCardPurchaseMode.FlexibleMultiple,
                is GiftCardPurchaseMode.Fixed -> {
                    val max = maxFiat?.toBigDecimal()?.toDouble() ?: Double.MAX_VALUE
                    totalDouble > 0 && totalDouble <= max && totalDouble < fiatBalance.toBigDecimal().toDouble() && !isBlockchainReplaying && totalDouble < 2500
                }
                else -> false
            }

            // Min/max hint text for single flexible mode
            val minHintText = minFiat?.toFormattedString()
                ?.let { getString(R.string.purchase_gift_card_min, it) } ?: ""
            val maxHintText = maxFiat?.toFormattedString()
                ?.let { getString(R.string.purchase_gift_card_max, it) } ?: ""

            // Show error when amount is out of range
            val errorText = when {
                isBlockchainReplaying -> getString(R.string.send_coins_fragment_hint_replaying)
                mode is GiftCardPurchaseMode.FlexibleSingle -> {
                    val amount = amountText.toDoubleOrNull() ?: 0.0
                    val min = minFiat?.toBigDecimal()?.toDouble() ?: 0.0
                    val max = maxFiat?.toBigDecimal()?.toDouble() ?: Double.MAX_VALUE
                    val balanceMax = fiatBalance.toBigDecimal().toDouble()

                    when {
                        amount > 0 && amount < min -> getString(R.string.purchase_gift_card_min, minFiat?.toFormattedString() ?: "")
                        amount > balanceMax -> getString(R.string.insufficient_money_msg)
                        amount > max -> getString(R.string.purchase_gift_card_max, maxFiat?.toFormattedString() ?: "")
                        else -> ""
                    }
                }
                else -> {
                    val balanceMax = fiatBalance.toBigDecimal().toDouble()
                    if (totalDouble > balanceMax) {
                        getString(R.string.purchase_gift_card_insufficient_money_error)
                    } else if (totalDouble > 2500.0) {
                        getString(R.string.purchase_gift_card_max_multiple_error, Fiat.parseFiat("$", 2500.00.toString()).toFormattedString())
                    } else {
                        ""
                    }
                }
            }

            val discountHintText = remember(mode, amountText, totalDouble, merchant, minFiat, maxFiat, exchangeRate, isBlockchainReplaying) {
                buildDiscountHintText(mode, amountText, totalDouble, merchant, minFiat, maxFiat, isBlockchainReplaying)
            }

            val uiState = PurchaseGiftCardV2UiState(
                mode = mode,
                merchantName = merchant?.name.orEmpty(),
                merchantLogoUrl = merchant?.logoLocation,
                dashBalance = dashBalanceString,
                fiatBalance = fiatBalanceString,
                showBalance = showBalance,
                currencySymbol = "$", // should be the correct symble for USD based the current locale
                amountText = amountText,
                minHintText = minHintText,
                maxHintText = maxHintText,
                denominationQuantities = denominationQuantities.toMap(),
                totalAmountText = totalAmountText,
                canContinue = canContinue,
                errorText = errorText,
                discountHintText = discountHintText
            )

            PurchaseGiftCardScreenV2(
                uiState = uiState,
                onBack = { findNavController().popBackStack() },
                onInfo = {
                    ExploreDashInfoDialog().show(requireActivity())
                },
                onTabChanged = { isMultiple ->
                    amountText = "0"
                    denominationQuantities.clear()
                    viewModel.isFixedDenominationMultiple.value = isMultiple
                    // Keep current merchant; just switch UI mode
                },
                onToggleBalance = { showBalance = !showBalance },
                onKeyInput = { key ->
                    amountText = processAmountKeyInput(amountText, key)
                    // Update viewModel order info so confirm dialog has up-to-date amount
                    val fiatAmount = try {
                        Fiat.parseFiat(Constants.USD_CURRENCY, amountText)
                    } catch (e: Exception) {
                        null
                    }
                    fiatAmount?.let { viewModel.setGiftCardOrderInfo(it, 1) }
                },
                onQuantityChanged = { denomination, quantity ->
                    if (quantity == 0) {
                        denominationQuantities.remove(denomination)
                    } else {
                        denominationQuantities[denomination] = quantity
                    }
                    viewModel.denominationQuantities.value = denominationQuantities.toMap()
                    // Update viewModel with total for the confirm dialog
                    val newTotal = denominationQuantities.entries.sumOf { (d, q) -> d * q }
                    val newQty = denominationQuantities.values.sum()
                    if (newTotal > 0) {
                        val fiat = Fiat.parseFiat(Constants.USD_CURRENCY, newTotal.toString())
                        viewModel.setGiftCardOrderInfo(fiat, newQty)
                    }
                },
                onContinue = {
                    when (val m = mode) {
                        is GiftCardPurchaseMode.FlexibleSingle -> {
                            val fiat = try {
                                Fiat.parseFiat(Constants.USD_CURRENCY, amountText)
                            } catch (e: Exception) {
                                return@PurchaseGiftCardScreenV2
                            }
                            viewModel.setGiftCardOrderInfo(fiat, 1)
                        }
                        is GiftCardPurchaseMode.FlexibleMultiple,
                        is GiftCardPurchaseMode.Fixed -> {
                            val total = denominationQuantities.entries.sumOf { (d, q) -> d * q }
                            val qty = denominationQuantities.values.sum()
                            if (total > 0) {
                                val fiat = Fiat.parseFiat(Constants.USD_CURRENCY, total.toString())
                                viewModel.setGiftCardOrderInfo(fiat, qty)
                            }
                        }
                    }
                    PurchaseGiftCardConfirmDialog().show(requireActivity())
                }
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMerchant()
    }

    private fun setupMerchant() {
        val savedMerchantId = viewModel.getSavedMerchantId()
        val savedProvider = viewModel.getSavedProvider()
        val currentMerchant = exploreViewModel.selectedItem.value
        val currentProvider = viewModel.selectedProvider

        viewLifecycleOwner.lifecycleScope.launch {
            if (savedMerchantId != null && currentMerchant == null &&
                savedProvider != null && currentProvider == null
            ) {
                if (!loadMerchantById(savedMerchantId, savedProvider)) {
                    findNavController().popBackStack()
                }
            } else if (currentMerchant is Merchant && currentMerchant.merchantId != null &&
                !currentMerchant.source.isNullOrEmpty() && currentProvider != null
            ) {
                loadMerchant(currentMerchant)
            } else {
                findNavController().popBackStack()
            }
        }
    }

    private suspend fun loadMerchantById(merchantId: String, provider: String): Boolean {
        val merchant = viewModel.getMerchantById(merchantId) ?: return false
        viewModel.selectedProvider = GiftCardProviderType.fromProviderName(provider)
        loadMerchant(merchant)
        return true
    }

    private suspend fun loadMerchant(merchant: Merchant) {
        viewModel.setGiftCardMerchant(merchant)
        if (viewModel.giftCardMerchant.value?.active != true) {
            findNavController().popBackStack()
            return
        }
        val updated = viewModel.updateMerchantDetails(merchant)
        if (viewModel.giftCardMerchant.value?.active != true) {
            findNavController().popBackStack()
            return
        }
        viewModel.setGiftCardMerchant(updated)
        viewModel.setIsFixedDenomination(updated.fixedDenomination)
    }

    private fun buildPurchaseMode(
        isFixed: Boolean?,
        isMultiple: Boolean?,
        merchant: Merchant?
    ): GiftCardPurchaseMode {
        return when {
            isFixed != true && isMultiple != true -> GiftCardPurchaseMode.FlexibleSingle
            isFixed == true -> {
                val denoms = merchant?.denominations ?: emptyList()
                GiftCardPurchaseMode.Fixed(denoms)
            }
            else -> {
                val denominations = arrayListOf<Double>()
                val minimum = max(merchant?.minCardPurchase ?: 0.0, 5.0)
                val maximum = merchant?.maxCardPurchase ?: 500.00
                denominations.add(minimum)
                denominations.add(minimum * 2)
                denominations.add(minimum * 4)
                denominations.add(maximum / 2)
                denominations.add(maximum)
                GiftCardPurchaseMode.FlexibleMultiple(denominations)
            }
        }
    }

    private fun buildDiscountHintText(
        mode: GiftCardPurchaseMode,
        amountText: String,
        totalDouble: Double,
        merchant: Merchant?,
        minFiat: Fiat?,
        maxFiat: Fiat?,
        isBlockchainReplaying: Boolean
    ): String {
        merchant ?: return ""
        if (isBlockchainReplaying) return ""
        val savingsFraction = merchant.savingsFraction
        if (savingsFraction == 0.0) return ""
        val amount = when (mode) {
            GiftCardPurchaseMode.FlexibleSingle -> {
                try {
                    Fiat.parseFiat(Constants.USD_CURRENCY, amountText)
                } catch (_: Exception) {
                    return ""
                }
            }
            else -> {
                try {
                    Fiat.parseFiat(Constants.USD_CURRENCY, totalDouble.toBigDecimal().toPlainString())
                } catch (_: Exception) {
                    return ""
                }
            }
        }
        if (amount.isZero) return ""
        if (mode is GiftCardPurchaseMode.FlexibleSingle) {
            if (minFiat != null && amount.isLessThan(minFiat)) return ""
            if (maxFiat != null && amount.isGreaterThan(maxFiat)) return ""
        }
        val discountedAmount = amount.discountBy(savingsFraction)
        return getString(
            R.string.purchase_gift_card_discount_hint,
            amount.toFormattedString(),
            discountedAmount.toFormattedStringRoundUp(),
            GenericUtils.formatPercent(savingsFraction)
        )
    }

    private fun buildFiatBalanceText(balance: Coin?, exchangeRate: ExchangeRate?): Pair<String, String> {
        balance ?: return Pair("", "")
        val dashText = viewModel.dashFormat.format(balance).toString()
        val fiatRate = exchangeRate?.let { org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat) }
        return if (fiatRate != null) {
            Pair(dashText, fiatRate.coinToFiat(balance).toFormattedString())
        } else {
            Pair(dashText, "")
        }
    }

    private fun buildFiatBalance(balance: Coin?, exchangeRate: ExchangeRate?): Fiat {
        val defaultResult = Fiat.valueOf(exchangeRate?.currencySymbol ?: Constants.USD_CURRENCY, 0)
        balance ?: return defaultResult
        val fiatRate = exchangeRate?.let { org.bitcoinj.utils.ExchangeRate(Coin.COIN, it.fiat) }
        return if (fiatRate != null) {
            fiatRate.coinToFiat(balance)
        } else {
            defaultResult
        }
    }
}