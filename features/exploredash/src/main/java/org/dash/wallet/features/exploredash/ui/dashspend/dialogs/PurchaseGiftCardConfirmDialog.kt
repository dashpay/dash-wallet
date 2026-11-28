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
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.payments.parsers.isPaymentUriParseError
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.services.InsufficientFundsException
import org.dash.wallet.common.services.PaymentStillConfirmingException
import org.dash.wallet.common.services.PaymentSubmissionPendingException
import org.dash.wallet.common.services.SendEngineNotSyncedException
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.model.GiftCardInfo
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.DuplicateGiftCardSubmissionException
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPriceChange
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPurchaseBlock
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPurchaseMode
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardQuote
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardSubmissionState
import org.dash.wallet.features.exploredash.ui.dashspend.PaidOrder
import org.dash.wallet.features.exploredash.ui.dashspend.message
import org.dash.wallet.features.exploredash.ui.explore.MerchantLogo
import org.dash.wallet.features.exploredash.utils.GiftCardCurrency
import org.dash.wallet.features.exploredash.utils.SavingsFormatting
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import javax.inject.Inject

data class PurchaseConfirmUIState(
    val merchantName: String = "",
    val merchantLogoUrl: String? = null,
    val purchaseValueText: String = "",
    val currencyCode: String = "",
    val giftCardTotalText: String = "",
    val discountText: String = "",
    val youPayText: String = "",
    val breakdownText: String? = null,
    val isLoading: Boolean = false,
    /** A purchase is already submitted or unresolved, so this order must not be paid for again. */
    val submissionBlocked: Boolean = false,
    val useExpandedLayout: Boolean = false,
    val isNetworkAvailable: Boolean = true
)

@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : ComposeBottomSheet() {
    override val backgroundStyle = R.style.PrimaryBackground

    private var needsExpand = false
    override val forceExpand: Boolean
        get() = needsExpand

    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardConfirmDialog::class.java)

        // Baseline screen height (in dp, at the default font scale) needed to fit the amount,
        // detail card and action buttons in the wrap-content sheet. The actual threshold is
        // this value multiplied by the current fontScale: bigger fonts inflate the content, so
        // they require a taller screen before the compact layout is safe. Below the threshold we
        // force the sheet to expand (full height + scrollable body with pinned buttons).
        // Tune this value if clipping is still observed.
        private const val SMALL_SCREEN_HEIGHT_DP = 720
        private val currencyFormatNoSymbol = NumberFormat.getInstance()
    }

    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    @Inject
    lateinit var authManager: AuthenticationManager

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Optionally handle result here
    }

    // what this sheet shows, and so the only order, currency and total Confirm may buy
    private var shownQuote: GiftCardQuote? = null

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
        // first: a payment started from an earlier instance of this sheet ends here, whatever
        // has become of the cart since
        observePurchaseOutcome()

        val merchant = viewModel.giftCardMerchant.value
        if (merchant == null) {
            log.warn("PurchaseGiftCardConfirmDialog: No merchant available, dismissing dialog")
            dismissUnlessPurchaseOutstanding()
            return
        }

        val cardCurrency = viewModel.cardCurrency
        if (cardCurrency == null) {
            log.warn("PurchaseGiftCardConfirmDialog: card currency unknown, dismissing dialog")
            dismissUnlessPurchaseOutstanding()
            return
        }
        val quote = viewModel.currentQuote()
        if (quote == null || viewModel.purchaseBlock.isAboutTheAmount()) {
            log.warn("PurchaseGiftCardConfirmDialog: nothing to buy, dismissing dialog")
            dismissUnlessPurchaseOutstanding()
            return
        }
        shownQuote = quote
        val shownOrder = quote.order
        val noCentsFormat = GiftCardCurrency.displayFormat(cardCurrency, trimZeroDecimals = true)
        val currencyFormat = GiftCardCurrency.displayFormat(cardCurrency)

        val orderEntries = shownOrder.entries
        val orderTotalAmount = orderEntries.sumOf { it.key * it.value }
        // Compute a weighted savings fraction: sum each (denom * qty * (1 - per-denom discount))
        // across line items, then derive the overall discount. A single getGiftCardDiscount call
        // on orderTotalAmount returns 0 for mixed-denomination orders because no card matches the
        // summed total exactly.
        val discountedTotal = orderEntries.sumOf { (denom, qty) ->
            val perDenomDiscount = viewModel.getGiftCardDiscount(denom)
            denom * qty * (1.0 - perDenomDiscount)
        }
        val savingsFraction = if (orderTotalAmount > 0.0) {
            1.0 - discountedTotal / orderTotalAmount
        } else {
            0.0
        }

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

        val youPayText = currencyFormat.format(
            quote.total
        )

        val breakdownLines = breakdown?.lineSequence()?.count() ?: 0
        // Expand when there are multiple breakdown lines, or when the available height is too
        // small to show everything without clipping the bottom action buttons. The effective
        // content height grows with the user's font scale (and narrow screens wrap text taller),
        // so the threshold is scaled by fontScale rather than compared to a fixed dp value.
        val config = resources.configuration
        val requiredHeightDp = SMALL_SCREEN_HEIGHT_DP * config.fontScale
        val isShortScreen = config.screenHeightDp < requiredHeightDp
        needsExpand = breakdownLines >= 2 || isShortScreen
        if (needsExpand) {
            view.updateLayoutParams { height = ViewGroup.LayoutParams.MATCH_PARENT }
        }

        _uiState.value = PurchaseConfirmUIState(
            merchantName = merchant.name ?: "",
            merchantLogoUrl = merchant.logoLocation,
            purchaseValueText = currencyFormatNoSymbol.format(orderTotalAmount.toBigDecimal().toDouble()),
            currencyCode = cardCurrency,
            giftCardTotalText = noCentsFormat.format(orderTotalAmount.toBigDecimal().toDouble()),
            discountText = SavingsFormatting.format(
                context = requireContext(),
                percent = savingsFraction * 100,
                decimals = 2,
                discountPrefix = ""
            ),
            youPayText = youPayText,
            breakdownText = breakdown,
            // the collector above has run already and will not again until the state changes
            isLoading = viewModel.submissionState.value == GiftCardSubmissionState.IN_PROGRESS,
            submissionBlocked = viewModel.submissionState.value != GiftCardSubmissionState.IDLE,
            useExpandedLayout = needsExpand,
            isNetworkAvailable = true
        )

        viewModel.isNetworkAvailable.observe(viewLifecycleOwner) { isNetworkAvailable ->
            _uiState.update {
                it.copy(isNetworkAvailable = isNetworkAvailable)
            }
        }

        // The amounts on this sheet are fixed when it opens, so it closes once they are no longer
        // what would be ordered. Not during a purchase: its order has been placed by then.
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.cardCurrencyState,
                viewModel.giftCardOrderInfo,
                viewModel.giftCardMerchant,
                viewModel.acceptedDetailsVersion
            ) { _, _, _, _ -> }.collect {
                // a cart of PiggyCards is rebuilt by its screen on recreation, so only a new price
                // of the same cart closes its sheet; Confirm checks the cart itself
                val current = viewModel.currentQuote()
                val isOtherOrder = if (viewModel.selectedProvider == GiftCardProviderType.CTX) {
                    current != quote
                } else {
                    current?.order == quote.order && current.total.compareTo(quote.total) != 0
                }
                val isOutdated = viewModel.cardCurrency != cardCurrency || isOtherOrder ||
                    viewModel.purchaseBlock.isAboutTheAmount()
                // nor while its order waits for the buyer's answer to a new price
                if (isOutdated && viewModel.submissionState.value == GiftCardSubmissionState.IDLE &&
                    viewModel.priceChange.value == null
                ) {
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    private fun dismissUnlessPurchaseOutstanding() {
        if (!viewModel.isPurchaseOutstanding) {
            dismiss()
        }
    }

    private fun observePurchaseOutcome() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.paidOrder.collect { order ->
                if (order != null && isAdded && viewModel.takePaidOrder(order)) {
                    enterAmountViewModel.clearSavedState()
                    showGiftCardDetailsDialog(order)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.priceChange.collect { change ->
                if (change != null && isAdded) {
                    showPriceChange(change)
                }
            }
        }

        // Taken only while resumed: a dialog shown earlier waits for the activity to resume, and
        // is lost, with the news already taken, when the activity is recreated first.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.droppedPriceChange.collect { change ->
                    if (change != null && isAdded && viewModel.takeDroppedPriceChange(change)) {
                        showPriceChangeUnpaid()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.paymentFailure.collect { failure ->
                    if (failure != null && isAdded && viewModel.takePaymentFailure(failure)) {
                        submitPayment { throw failure }
                    }
                }
            }
        }

        // Held by the view model, so a dialog rebuilt after activity recreation starts out
        // knowing a purchase is outstanding instead of offering Confirm again.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.submissionState.collect { state ->
                isCancelable = state != GiftCardSubmissionState.IN_PROGRESS
                _uiState.update {
                    it.copy(
                        isLoading = state == GiftCardSubmissionState.IN_PROGRESS,
                        submissionBlocked = state != GiftCardSubmissionState.IDLE
                    )
                }
                // The restrictions showLoading() applies, for a dialog rebuilt while a purchase is
                // under way too (review 4307041834): its own flow, which would lift them, ended
                // with the previous view, so they follow the purchase state here.
                val purchasing = state == GiftCardSubmissionState.IN_PROGRESS
                dialog?.setCancelable(!purchasing)
                dialog?.setCanceledOnTouchOutside(!purchasing)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            // A fresh instance means the user opened this screen to buy something. A rebuilt one
            // (activity recreation) must keep whatever state the outstanding purchase left.
            viewModel.beginNewPurchase()
        }
    }

    private fun GiftCardPurchaseBlock?.isAboutTheAmount() =
        this == GiftCardPurchaseBlock.NO_AMOUNT || this == GiftCardPurchaseBlock.AMOUNT_NOT_OFFERED

    private fun showPurchaseBlocked(block: GiftCardPurchaseBlock) {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.gift_card_purchase_failed),
            block.message(requireContext(), viewModel.cardCurrency),
            getString(R.string.button_close)
        ).show(requireActivity())
    }

    private fun onConfirmButtonClicked() {
        // Claim before purchaseGiftCard(), which creates a fresh order at the merchant. Merely
        // checking here would let two quick taps both pass, since the state was not claimed until
        // payAndRecordOrder, and each would leave an order behind.
        viewModel.purchaseBlock?.let { block ->
            log.warn("ignoring Confirm, the purchase is blocked: {}", block)
            showPurchaseBlocked(block)
            return
        }
        if (!viewModel.tryStartSubmission()) {
            log.warn("ignoring Confirm, a gift card purchase is already outstanding")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Give the claim back on every path that never reaches submission. Once
            // payAndRecordOrder has run the state is no longer IN_PROGRESS, so this is a no-op.
            try {
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
                    viewModel.purchaseGiftCard(checkNotNull(shownQuote))
                } catch (ex: CTXSpendException) {
                    hideLoading()
                    val reason = ex.resourceString
                    when {
                        reason != null -> {
                            if (isAdded) {
                                AdaptiveDialog.create(
                                    R.drawable.ic_error,
                                    getString(R.string.gift_card_purchase_failed),
                                    getString(reason.resourceId),
                                    getString(R.string.button_close)
                                ).show(requireActivity())
                                if (reason.resourceId == R.string.gift_card_order_changed) {
                                    dismissAllowingStateLoss()
                                }
                            }
                        }
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

                val dashPaymentUrl = data.first().paymentUrls?.get("DASH.DASH")
                if (dashPaymentUrl == null) {
                    log.error("paymentUrls missing DASH.DASH for gift card ${data.first().id}")
                    hideLoading()
                    if (isAdded) {
                        AdaptiveDialog.create(
                            R.drawable.ic_error,
                            getString(R.string.gift_card_purchase_failed),
                            getString(R.string.gift_card_error),
                            getString(R.string.button_close)
                        ).show(requireActivity())
                    }
                    return@launch
                }
                // the details are shown from paidOrder: this sheet may be gone by the time it is paid
                createSendingRequestFromDashUri(dashPaymentUrl, data)
            } finally {
                viewModel.releaseUnusedSubmission()
            }
        }
    }

    private fun showPriceChangeUnpaid() = requireActivity().showPriceChangeUnpaid()

    private fun showPriceChange(change: GiftCardPriceChange) {
        hideLoading()
        val activity = requireActivity()
        // one restored with the activity has lost its listener, and its buttons would do nothing
        (activity.supportFragmentManager.findFragmentByTag(PRICE_CHANGE_DIALOG_TAG) as? DialogFragment)
            ?.dismissAllowingStateLoss()
        val fiatFormat = GiftCardCurrency.displayFormat(change.currency)
        val dashFormat = viewModel.dashFormat.noCode()
        AdaptiveDialog.create(
            R.drawable.ic_info_blue,
            getString(R.string.gift_card_price_updated_title),
            getString(
                R.string.gift_card_price_updated_message,
                fiatFormat.format(change.chargedTotal),
                dashFormat.format(change.charged),
                fiatFormat.format(change.shownTotal),
                dashFormat.format(change.shown)
            ),
            getString(R.string.button_cancel),
            getString(R.string.gift_card_price_updated_pay)
        ).show(activity, PRICE_CHANGE_DIALOG_TAG) { result ->
            when {
                // answered already, or replaced by a newer change
                viewModel.priceChange.value != change -> Unit
                // the view model pays, and reports, whatever becomes of this sheet meanwhile
                result == true -> {
                    if (isAdded && view != null) {
                        showLoading()
                    }
                    viewModel.acceptPriceChange(change)
                }
                else -> {
                    viewModel.declinePriceChange()
                    if (isAdded) {
                        dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    private suspend fun createSendingRequestFromDashUri(
        url: String,
        giftCards: List<GiftCardInfo>
    ): String? {
        val quote = checkNotNull(shownQuote)
        // submission and order recording together, so a destroyed view cannot separate them
        return submitPayment { viewModel.payOrder(quote, url, giftCards) }
    }

    /** [pay] returns null when the order was left unpaid for the buyer to decide on a new price. */
    private suspend fun submitPayment(pay: suspend () -> String?): String? {
        return try {
            pay()
        } catch (ex: CTXSpendException) {
            log.error("purchaseGiftCard error: the order was not paid", ex)
            hideLoading()
            if (isAdded) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.gift_card_purchase_failed),
                    getString(ex.resourceString?.resourceId ?: R.string.gift_card_error),
                    getString(R.string.button_close)
                ).show(requireActivity())
            }
            null
        } catch (x: InsufficientFundsException) {
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
        } catch (ex: PaymentSubmissionPendingException) {
            // The payment left the device but the merchant's answer was lost. The wallet keeps
            // checking the network in the background; the transaction shows up as sent once it is
            // found, and its inputs are released if it never appears. Don't let the user retry now.
            log.warn("purchaseGiftCard submission result unknown for {}", ex.txId, ex)
            // payAndRecordOrder has already recorded the order against this transaction id.
            if (isAdded) {
                enterAmountViewModel.clearSavedState()
            }
            showPaymentPending()
            null
        } catch (ex: PaymentStillConfirmingException) {
            // An earlier payment's outcome is still unknown and its inputs could not be kept out
            // of this one. Nothing was sent; the order rows recorded for it are discarded by
            // payAndRecordOrder like any other unsent payment.
            log.warn("purchaseGiftCard refused, an earlier payment is still being confirmed", ex)
            hideLoading()
            if (isAdded) {
                AdaptiveDialog.create(
                    R.drawable.ic_warning,
                    getString(R.string.payment_submission_pending_title),
                    getString(R.string.payment_still_confirming),
                    getString(R.string.button_close)
                ).show(requireActivity())
            }
            null
        } catch (ex: SendEngineNotSyncedException) {
            // The wallet has not caught up with the chain. Nothing was sent, payAndRecordOrder
            // discards the order rows, and the same purchase works once the sync finishes: say
            // that, not "contact support".
            log.warn("purchaseGiftCard refused, the wallet is not synced yet", ex)
            hideLoading()
            if (isAdded) {
                AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.send_coins_error_msg),
                    getString(R.string.send_coins_fragment_hint_replaying),
                    getString(R.string.button_close)
                ).show(requireActivity())
            }
            null
        } catch (ex: DuplicateGiftCardSubmissionException) {
            // A purchase is already submitted or unresolved for this order. Do not start another.
            log.warn("ignoring duplicate gift card submission, state is {}", ex.state)
            hideLoading()
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
                        ex.cause?.isPaymentUriParseError == true &&
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

    /**
     * The payment was submitted but its result is unknown. Leave the purchase flow afterwards:
     * this order is paid for as far as we know, and keeping the sheet open would put a live
     * Confirm button back in front of the user once the warning is closed. Clearing the entered
     * amount is not enough on its own, because the cart comes from the view model's order info,
     * so Confirm would place and pay for a second identical order out of any other unlocked
     * outputs.
     */
    private fun showPaymentPending() {
        hideLoading()
        if (isAdded) {
            AdaptiveDialog.create(
                R.drawable.ic_warning,
                getString(R.string.payment_submission_pending_title),
                getString(R.string.payment_submission_pending_message),
                getString(R.string.button_close)
            ).show(requireActivity()).also { dismissPurchaseFlow() }
        }
    }

    /** Returns to the start of the flow and closes this sheet, as a successful purchase does. */
    private fun dismissPurchaseFlow() {
        val navController = findNavController()
        navController.popBackStack(navController.graph.startDestinationId, false)
        this@PurchaseGiftCardConfirmDialog.dismissAllowingStateLoss()
    }

    private fun showGiftCardDetailsDialog(order: PaidOrder) {
        val txId = order.txId
        if (isAdded) {
            if (order.isMultipleCards) {
                GiftCardOrderDetailsDialog.newInstance(txId).show(requireActivity()).also {
                    val navController = findNavController()
                    navController.popBackStack(navController.graph.startDestinationId, false)
                    this@PurchaseGiftCardConfirmDialog.dismissAllowingStateLoss()
                }
            } else {
                GiftCardDetailsDialog.newInstance(txId).show(requireActivity()).also {
                    val navController = findNavController()
                    navController.popBackStack(navController.graph.startDestinationId, false)
                    this@PurchaseGiftCardConfirmDialog.dismissAllowingStateLoss()
                }
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
    val colors = LocalDashColors.current
    Column(
        modifier = if (uiState.useExpandedLayout) {
            Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        }
    ) {
        NavBarTitle(
            title = stringResource(R.string.purchase_confirm_transaction)
        )

        Column(
            modifier = Modifier
                // Always allow the body to scroll so the pinned buttons below stay
                // visible on short screens. fill = false keeps the sheet wrapping its
                // content when it fits, and only caps/scrolls the body when it doesn't.
                .weight(1f, fill = uiState.useExpandedLayout)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            EnterAmount(
                primaryAmount = uiState.purchaseValueText,
                currencyCodes = listOf(uiState.currencyCode),
                showSecondary = false,
                showMaxButton = false,
                showPrimaryChevron = false,
                showBalanceButton = false
            )

            // Detail card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(20.dp),
                        spotColor = Color(0x1AB8C1CC),
                        ambientColor = Color(0x1AB8C1CC)
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.backgroundSecondary)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // FROM row — Dash Wallet
                ConfirmRow(
                    label = stringResource(R.string.purchase_gift_card_from),
                    isCaption = true
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_dash_pay),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ConfirmValueText(
                        text = stringResource(R.string.dash_wallet_name),
                        isCaption = true
                    )
                }

                // TO row — Merchant
                ConfirmRow(
                    label = stringResource(R.string.purchase_gift_card_to),
                    isCaption = true
                ) {
                    MerchantLogo(
                        merchantName = uiState.merchantName,
                        logoUrl = uiState.merchantLogoUrl,
                        size = 18.dp,
                        shape = RoundedCornerShape(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ConfirmValueText(
                        text = uiState.merchantName,
                        isCaption = true
                    )
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
        }

        // Bottom buttons (btn-l tinted-gray + btn-l filled-blue)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
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
                isEnabled = uiState.isNetworkAvailable && !uiState.isLoading && !uiState.submissionBlocked,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ConfirmRow(
    label: String,
    isCaption: Boolean = false,
    trailing: @Composable () -> Unit
) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = if (isCaption) MyTheme.Typography.FootnoteMedium else MyTheme.Typography.BodyMediumMedium,
            color = colors.textTertiary,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun ConfirmValueText(
    text: String,
    isCaption: Boolean = false,
    textAlign: TextAlign = TextAlign.End
) {
    val colors = LocalDashColors.current
    Text(
        text = text,
        style = if (isCaption) MyTheme.Typography.Footnote else MyTheme.Typography.BodyMedium,
        color = colors.textPrimary,
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
            purchaseValueText = "25.00",
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
            purchaseValueText = "60.00",
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
            purchaseValueText = "60.00",
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
            purchaseValueText = "25.00",
            giftCardTotalText = "$25",
            discountText = "5%",
            youPayText = "$23.75",
            isLoading = true
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7, heightDp = 720)
@Composable
private fun PurchaseGiftCardConfirmExpandedPreview() {
    PurchaseGiftCardConfirmView(
        uiState = PurchaseConfirmUIState(
            merchantName = "Target",
            purchaseValueText = "260.00",
            giftCardTotalText = "$260",
            breakdownText = "1 x \$5\n2 x \$10\n3 x \$25\n2 x \$50\n1 x \$80",
            discountText = "5%",
            youPayText = "$247.00"
        )
    )
}

private const val PRICE_CHANGE_DIALOG_TAG = "gift_card_price_change"

/**
 * For the purchase screens: a payment can end, or stop at a new price, when no confirmation
 * sheet is left to say so.
 */
internal fun Fragment.observePurchaseWithoutSheet(viewModel: DashSpendViewModel) {
    fun hasSheet() = requireActivity().supportFragmentManager.fragments.any { it is PurchaseGiftCardConfirmDialog }

    // leaving the purchase leaves nobody to answer: the order stays unpaid, and the buyer is told
    viewLifecycleOwner.observeOnDestroy {
        val activity = activity
        if (activity != null && !activity.isChangingConfigurations) {
            viewModel.dropPriceChange()
            viewModel.droppedPriceChange.value?.takeIf(viewModel::takeDroppedPriceChange)?.let {
                activity.showPriceChangeUnpaid()
            }
        }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewModel.paidOrder.collect { order ->
            if (order != null && isAdded && !hasSheet() && viewModel.takePaidOrder(order)) {
                val details = if (order.isMultipleCards) {
                    GiftCardOrderDetailsDialog.newInstance(order.txId)
                } else {
                    GiftCardDetailsDialog.newInstance(order.txId)
                }
                details.show(requireActivity())
                val navController = findNavController()
                navController.popBackStack(navController.graph.startDestinationId, false)
            }
        }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewModel.priceChange.collect { change ->
            if (change != null && isAdded && !hasSheet()) {
                viewModel.dropPriceChange()
            }
        }
    }

    // taken only while resumed, when the dialog can be shown at once
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.droppedPriceChange.collect { change ->
                if (change != null && isAdded && !hasSheet() && viewModel.takeDroppedPriceChange(change)) {
                    requireActivity().showPriceChangeUnpaid()
                }
            }
        }
    }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.paymentFailure.collect { failure ->
                if (failure != null && isAdded && !hasSheet() && viewModel.takePaymentFailure(failure)) {
                    requireActivity().showPaymentFailure(failure)
                }
            }
        }
    }
}

private fun FragmentActivity.showPriceChangeUnpaid() {
    AdaptiveDialog.create(
        R.drawable.ic_info_blue,
        getString(R.string.gift_card_price_updated_title),
        getString(R.string.gift_card_price_changed_unpaid),
        getString(R.string.button_close)
    ).show(this)
}

// with no sheet left, only whether the payment may be out matters
private fun FragmentActivity.showPaymentFailure(failure: Exception) {
    val isPending = failure is PaymentSubmissionPendingException
    AdaptiveDialog.create(
        if (isPending) R.drawable.ic_warning else R.drawable.ic_error,
        getString(if (isPending) R.string.payment_submission_pending_title else R.string.gift_card_purchase_failed),
        getString(
            when {
                isPending -> R.string.payment_submission_pending_message
                else -> (failure as? CTXSpendException)?.resourceString?.resourceId ?: R.string.gift_card_error
            }
        ),
        getString(R.string.button_close)
    ).show(this)
}
