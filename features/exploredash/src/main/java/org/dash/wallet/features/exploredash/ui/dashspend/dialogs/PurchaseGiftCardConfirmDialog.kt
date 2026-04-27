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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPurchaseMode
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import javax.inject.Inject

data class PurchaseConfirmUIState(
    val merchantName: String = "",
    val merchantLogoUrl: String? = null,
    val purchaseValueText: String = "",
    val giftCardTotalText: String = "",
    val discountText: String = "",
    val youPayText: String = "",
    val breakdownText: String? = null,
    val isLoading: Boolean = false
)

@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : ComposeBottomSheet() {
    override val backgroundStyle = R.style.PrimaryBackground
    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardConfirmDialog::class.java)
        private val currency = Currency.getInstance(Constants.USD_CURRENCY)
        private val noCentsFormat = NumberFormat.getCurrencyInstance().apply {
            currency = PurchaseGiftCardConfirmDialog@currency
            minimumFractionDigits = 0
        }
        private val currencyFormat = NumberFormat.getCurrencyInstance().apply {
            currency = PurchaseGiftCardConfirmDialog@currency
        }
    }

    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    @Inject
    lateinit var authManager: AuthenticationManager

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Optionally handle result here
    }

    private val _uiState = MutableStateFlow(PurchaseConfirmUIState())
    val uiState: StateFlow<PurchaseConfirmUIState> = _uiState.asStateFlow()

    @Composable
    override fun Content() {
        PurchaseGiftCardConfirmContent(
            uiStateFlow = uiState,
            onCancel = { dismiss() },
            onConfirm = { onConfirmButtonClicked() }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchant = viewModel.giftCardMerchant.value
        if (merchant == null) {
            log.warn("PurchaseGiftCardConfirmDialog: No merchant available, dismissing dialog")
            dismiss()
            return
        }

        val orderTotalAmount = viewModel.giftCardOrderInfo.value.keys.sumOf { it }
        val savingsFraction = viewModel.getGiftCardDiscount(orderTotalAmount.toBigDecimal().toDouble())

        val isFixed = viewModel.isFixedDenomination.value
        val isMultiple = viewModel.isFixedDenominationMultiple.value
        val mode = when {
            isFixed != true && isMultiple != true -> GiftCardPurchaseMode.FlexibleSingle
            isFixed == true -> GiftCardPurchaseMode.Fixed(merchant.denominations)
            else -> GiftCardPurchaseMode.FlexibleMultiple(emptyList())
        }

        val breakdown = when (mode) {
            is GiftCardPurchaseMode.FlexibleSingle -> null
            is GiftCardPurchaseMode.FlexibleMultiple,
            is GiftCardPurchaseMode.Fixed -> {
                val nonZero = viewModel.giftCardOrderInfo.value.filter { it.value > 0 }.toSortedMap()
                if (nonZero.isNotEmpty()) {
                    nonZero.entries.joinToString("\n") { (denom, qty) ->
                        "$qty x ${noCentsFormat.format(denom)}"
                    }
                } else {
                    null
                }
            }
        }

        val discountedValue = orderTotalAmount - savingsFraction * orderTotalAmount
        val youPayText = currencyFormat.format(
            discountedValue.toBigDecimal()
                .setScale(currency.defaultFractionDigits, RoundingMode.UP)
                .toDouble()
        )

        _uiState.value = PurchaseConfirmUIState(
            merchantName = merchant.name ?: "",
            merchantLogoUrl = merchant.logoLocation,
            purchaseValueText = currencyFormat.format(orderTotalAmount.toBigDecimal().toDouble()),
            giftCardTotalText = noCentsFormat.format(orderTotalAmount.toBigDecimal().toDouble()),
            discountText = GenericUtils.formatPercent(savingsFraction) ?: "",
            youPayText = youPayText,
            breakdownText = breakdown,
            isLoading = false
        )
    }

    private fun onConfirmButtonClicked() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Double-check merchant is still available before proceeding
            if (viewModel.giftCardMerchant.value == null) {
                log.warn("PurchaseGiftCardConfirmDialog: Merchant became null during confirmation, dismissing")
                dismiss()
                return@launch
            }
            showLoading()
            if (!isAdded || authManager.authenticate(requireActivity()) == null) {
                hideLoading()
                return@launch
            }

            val data = try {
                viewModel.purchaseGiftCard()
            } catch (ex: CTXSpendException) {
                hideLoading()
                when {
                    ex.isNetworkError -> {
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                getString(R.string.gift_card_error),
                                getString(R.string.button_close)
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        "DashPay DashSpend Issue: Network Error",
                                        sendToService = true,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                    ex.errorCode == 400 && ex.isLimitError -> {
                        viewModel.logError(ex, "${ex.serviceName} returned error: limits")
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                getString(R.string.gift_card_limit_error),
                                getString(R.string.button_close),
                                if (ex.serviceName == ServiceName.CTXSpend) {
                                    getString(R.string.gift_card_contact_ctx)
                                } else {
                                    getString(R.string.gift_card_contact_piggycards)
                                }
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        "${ex.serviceName} Issue: Spending Limit Problem",
                                        sendToService = true,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                    ex.isOutOfStock -> {
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                getString(R.string.gift_card_out_of_stock_error),
                                getString(R.string.button_close),
                                getString(R.string.gift_card_contact_support)
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        "PiggyCards Issue: Out of Stock",
                                        sendToService = true,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                    ex.errorCode == 500 -> {
                        val serviceName = if (ex.serviceName == ServiceName.CTXSpend) "CTX" else "PiggyCards"
                        viewModel.logError(ex, "${ex.serviceName} returned error: Error 500")
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                getString(R.string.gift_card_server_error, serviceName),
                                getString(R.string.button_close),
                                if (ex.serviceName == ServiceName.CTXSpend) {
                                    getString(R.string.gift_card_contact_ctx)
                                } else {
                                    getString(R.string.gift_card_contact_piggycards)
                                }
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        "${ex.serviceName} Issue: Purchase, Internal Server Error",
                                        sendToService = true,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                    ex.isRegionNotAllowed -> {
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                getString(R.string.gift_card_server_region_error),
                                getString(R.string.button_close),
                                getString(R.string.gift_card_contact_support)
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        "DashSpend Issue: Purchase, Region Not Allowed",
                                        sendToService = false,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                    else -> {
                        if (isAdded) {
                            AdaptiveDialog.create(
                                R.drawable.ic_error,
                                getString(R.string.gift_card_purchase_failed),
                                ex.message ?: getString(R.string.gift_card_error),
                                getString(R.string.button_close),
                                getString(R.string.gift_card_contact_support)
                            ).show(requireActivity()) { result ->
                                if (result == true) {
                                    val intent = viewModel.createEmailIntent(
                                        subject = "DashPay DashSpend Issue: Purchase Error",
                                        sendToService = false,
                                        ex
                                    )

                                    val chooser = Intent.createChooser(
                                        intent,
                                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                                    )
                                    launcher.launch(chooser)
                                }
                            }
                        }
                    }
                }
                return@launch
            }

            val totalAmount = Coin.valueOf(
                data.sumOf {
                    if (!it.cryptoAmount.isNullOrEmpty()) {
                        Coin.parseCoin(it.cryptoAmount).value
                    } else {
                        0L
                    }
                }
            )

            if (!totalAmount.isZero && viewModel.needsCrowdNodeWarning(totalAmount)) {
                if (!isAdded) {
                    hideLoading()
                    return@launch
                }
                val shouldContinue = MinimumBalanceDialog().showAsync(requireActivity())

                if (shouldContinue != true) {
                    hideLoading()
                    return@launch
                }
            }

            val transactionId = createSendingRequestFromDashUri(data.first().paymentUrls?.get("DASH.DASH")!!)
            transactionId?.let {
                enterAmountViewModel.clearSavedState()
                viewModel.saveGiftCardDummy(transactionId, data)
                showGiftCardDetailsDialog(transactionId, data.first().id)
            }
        }
    }

    private suspend fun createSendingRequestFromDashUri(url: String): Sha256Hash? {
        return try {
            viewModel.createSendingRequestFromDashUri(url)
        } catch (x: InsufficientMoneyException) {
            hideLoading()
            log.error("purchaseGiftCard InsufficientMoneyException", x)
            if (isAdded) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.insufficient_money_title),
                    getString(R.string.insufficient_money_msg),
                    getString(R.string.button_close)
                ).show(requireActivity())
            }
            null
        } catch (ex: DirectPayException) {
            log.error("purchaseGiftCard DirectPayException", ex)
            hideLoading()
            if (isAdded) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.payment_request_problem_title),
                    getString(R.string.payment_request_problem_message),
                    getString(R.string.button_close)
                ).show(requireActivity()) {
                    val intent = viewModel.createEmailIntent(
                        subject = "DashPay DashSpend Issue: DirectPay Error",
                        sendToService = false,
                        CTXSpendException(ex.message ?: "purchase gift card error: direct pay", cause = ex)
                    )

                    val chooser = Intent.createChooser(
                        intent,
                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                    )
                    launcher.launch(chooser)
                }
            }
            null
        } catch (ex: Exception) {
            log.error("purchaseGiftCard error", ex)
            hideLoading()
            if (isAdded) {
                val message = getString(
                    when {
                        ex.cause is BitcoinURIParseException &&
                            ex.message?.contains("mismatched network") == true ->
                            R.string.gift_card_error_wrong_network
                        else -> R.string.gift_card_error
                    }
                )
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.send_coins_error_msg),
                    message,
                    getString(R.string.button_close),
                    getString(R.string.gift_card_contact_support)
                ).show(requireActivity()) { result ->
                    if (result == true) {
                        val intent = viewModel.createEmailIntent(
                            subject = "DashPay DashSpend Issue: Purchase Error",
                            sendToService = false,
                            CTXSpendException(
                                ex.message ?: "purchase gift card error: sending payment",
                                viewModel.giftCardMerchant.value?.source,
                                cause = ex
                            )
                        )

                        val chooser = Intent.createChooser(
                            intent,
                            getString(R.string.report_issue_dialog_mail_intent_chooser)
                        )
                        launcher.launch(chooser)
                    }
                }
            }
            null
        }
    }

    private fun showGiftCardDetailsDialog(txId: Sha256Hash, giftCardId: String) {
        if (isAdded) {
            GiftCardDetailsDialog.newInstance(txId).show(requireActivity()).also {
                val navController = findNavController()
                navController.popBackStack(navController.graph.startDestinationId, false)

                this@PurchaseGiftCardConfirmDialog.dismissAllowingStateLoss()
            }
        }
    }

    private fun showLoading() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)
    }

    private fun hideLoading() {
        if (isAdded) {
            _uiState.value = _uiState.value.copy(isLoading = false)
            dialog?.setCancelable(true)
            dialog?.setCanceledOnTouchOutside(true)
        }
    }
}

// ─── Layer 2: state-collection bridge ────────────────────────────────────────
@Composable
private fun PurchaseGiftCardConfirmContent(
    uiStateFlow: StateFlow<PurchaseConfirmUIState>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val uiState by uiStateFlow.collectAsState()
    PurchaseGiftCardConfirmView(
        uiState = uiState,
        onCancel = onCancel,
        onConfirm = onConfirm
    )
}

// ─── Layer 3: pure UI (used by previews) ─────────────────────────────────────
@Composable
internal fun PurchaseGiftCardConfirmView(
    uiState: PurchaseConfirmUIState,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 15.dp)
    ) {
        // NavBar — back chevron + centred title (NavBarBackTitle Figma variant)
        NavBarTitle(
            title = stringResource(R.string.purchase_confirm_transaction),
            modifier = Modifier.background(MyTheme.Colors.dashBlue)
        )

        // Big amount — Headline M Bold (Amount display)
        Text(
            text = uiState.purchaseValueText,
            style = MyTheme.Typography.HeadlineMediumBold,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp, bottom = 24.dp)
        )

        // Detail card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MyTheme.Colors.backgroundSecondary)
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            // FROM row — Dash Wallet
            ConfirmRow(label = stringResource(R.string.purchase_gift_card_from)) {
                Image(
                    painter = painterResource(R.drawable.ic_dash_pay),
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 8.dp)
                )
                ConfirmValueText(text = stringResource(R.string.dash_wallet_name))
            }

            // TO row — Merchant
            ConfirmRow(label = stringResource(R.string.purchase_gift_card_to)) {
                AsyncImage(
                    model = uiState.merchantLogoUrl,
                    contentDescription = null,
                    placeholder = painterResource(R.drawable.ic_image_placeholder),
                    error = painterResource(R.drawable.ic_image_placeholder),
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 8.dp)
                )
                ConfirmValueText(text = uiState.merchantName)
            }

            // GIFT CARD VALUE row
            ConfirmRow(label = stringResource(R.string.purchase_gift_card_total_label)) {
                ConfirmValueText(text = uiState.giftCardTotalText)
            }

            // Optional denomination breakdown
            uiState.breakdownText?.let { breakdown ->
                ConfirmRow(label = stringResource(R.string.purchase_gift_card_quantity_label)) {
                    ConfirmValueText(
                        text = breakdown,
                        textAlign = TextAlign.End
                    )
                }
            }

            // DISCOUNT row
            ConfirmRow(label = stringResource(R.string.purchase_gift_card_discount)) {
                ConfirmValueText(text = uiState.discountText)
            }

            // YOU PAY row
            ConfirmRow(label = stringResource(R.string.purchase_gift_card_you_pay)) {
                ConfirmValueText(text = uiState.youPayText)
            }
        }

        // Bottom buttons (btn-l tinted-gray + btn-l filled-blue)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashButton(
                text = stringResource(R.string.cancel),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                isEnabled = !uiState.isLoading
            )
            DashButton(
                text = stringResource(R.string.purchase_gift_card_confirm),
                style = Style.FilledBlue,
                size = Size.Large,
                isLoading = uiState.isLoading,
                onClick = onConfirm,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConfirmRow(
    label: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Body M Regular / textSecondary — confirm-sheet label
        Text(
            text = label,
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun ConfirmValueText(
    text: String,
    textAlign: TextAlign = TextAlign.End
) {
    // Body M Medium / textPrimary — confirm-sheet value
    Text(
        text = text,
        style = MyTheme.Typography.BodyMediumMedium,
        color = MyTheme.Colors.textPrimary,
        textAlign = textAlign
    )
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun PurchaseGiftCardConfirmPreview() {
    PurchaseGiftCardConfirmView(
        uiState = PurchaseConfirmUIState(
            merchantName = "Target",
            purchaseValueText = "$25.00",
            giftCardTotalText = "$25",
            discountText = "5%",
            youPayText = "$23.75"
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun PurchaseGiftCardConfirmBreakdownPreview() {
    PurchaseGiftCardConfirmView(
        uiState = PurchaseConfirmUIState(
            merchantName = "Target",
            purchaseValueText = "$60.00",
            giftCardTotalText = "$60",
            breakdownText = "1 x $10\n2 x $25",
            discountText = "5%",
            youPayText = "$57.00"
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun PurchaseGiftCardConfirmBreakdown2Preview() {
    PurchaseGiftCardConfirmView(
        uiState = PurchaseConfirmUIState(
            merchantName = "Target",
            purchaseValueText = "$60.00",
            giftCardTotalText = "$60",
            breakdownText = "3 x $20",
            discountText = "5%",
            youPayText = "$57.00"
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
@Composable
private fun PurchaseGiftCardConfirmLoadingPreview() {
    PurchaseGiftCardConfirmView(
        uiState = PurchaseConfirmUIState(
            merchantName = "Target",
            purchaseValueText = "$25.00",
            giftCardTotalText = "$25",
            discountText = "5%",
            youPayText = "$23.75",
            isLoading = true
        )
    )
}