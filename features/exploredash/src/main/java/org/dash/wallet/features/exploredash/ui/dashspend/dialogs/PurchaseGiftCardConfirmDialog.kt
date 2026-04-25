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
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.TransitionManager
import coil.load
import coil.size.Scale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.uri.BitcoinURIParseException
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.ui.dashspend.GiftCardPurchaseMode
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import javax.inject.Inject

@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment(R.layout.dialog_confirm_purchase_gift_card) {
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

    @StyleRes override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)
    private val viewModel by exploreViewModels<DashSpendViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()

    @Inject
    lateinit var authManager: AuthenticationManager

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Optionally handle result here
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchant = viewModel.giftCardMerchant.value
        if (merchant == null) {
            log.warn("PurchaseGiftCardConfirmDialog: No merchant available, dismissing dialog")
            dismiss()
            return
        }

        val orderInfo = viewModel.giftCardOrderInfo.value
        val savingsFraction = viewModel.getGiftCardDiscount(orderInfo.value.toBigDecimal().toDouble())

        // Merchant info
        binding.merchantName.text = merchant.name
        merchant.logoLocation?.let { logoLocation ->
            binding.merchantLogo.load(logoLocation) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(R.drawable.ic_image_placeholder)
                error(R.drawable.ic_image_placeholder)
                listener(
                    onError = { _, result ->
                        log.error(
                            "Image load error for ${merchant.name}: ${merchant.logoLocation}: ${result.throwable.message}",
                            result.throwable
                        )
                    }
                )
            }
        }

        // Determine mode
        val isFixed = viewModel.isFixedDenomination.value
        val isMultiple = viewModel.isFixedDenominationMultiple.value
        val mode = when {
            isFixed != true && isMultiple != true -> GiftCardPurchaseMode.FlexibleSingle
            isFixed == true -> GiftCardPurchaseMode.Fixed(merchant.denominations)
            else -> GiftCardPurchaseMode.FlexibleMultiple(emptyList())
        }

        // Bind the large amount shown at the top of the dialog with cents
        binding.purchaseCardValue.text = currencyFormat.format(orderInfo.value.toBigDecimal().toDouble())

        // Populate the optional extra rows container
        val container = binding.extraRowsContainer
        container.removeAllViews()
        when (mode) {
            is GiftCardPurchaseMode.FlexibleSingle -> {
                container.isVisible = false
            }
            is GiftCardPurchaseMode.FlexibleMultiple,
            is GiftCardPurchaseMode.Fixed -> {
                val denomQtys = viewModel.denominationQuantities.value
                val nonZero = denomQtys.filter { it.value > 0 }.toSortedMap()
                if (nonZero.isNotEmpty()) {
                    val lines = nonZero.entries.joinToString("\n") { (denom, qty) ->
                        "$qty x ${noCentsFormat.format(denom)}"
                    }
                    addInfoRow(container, getString(R.string.purchase_gift_card_quantity_label), lines)
                    container.isVisible = true
                } else {
                    container.isVisible = false
                }
            }
        }

        // Always-visible summary rows
        binding.giftCardDiscountValue.text = GenericUtils.formatPercent(savingsFraction)
        binding.giftCardTotalValue.text = noCentsFormat.format(orderInfo.value.toBigDecimal().toDouble())
        val discountedValue = orderInfo.value.discountBy(savingsFraction)
        binding.giftCardYouPayValue.text = currencyFormat.format(discountedValue.toBigDecimal().setScale(currency.defaultFractionDigits, RoundingMode.UP).toDouble())

        binding.cancelButton.setOnClickListener { dismiss() }
        binding.confirmButton.setOnClickListener { onConfirmButtonClicked() }
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
                                    getString(
                                        R.string.gift_card_contact_piggycards
                                    )
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
                                    getString(
                                        R.string.gift_card_contact_piggycards
                                    )
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

    private fun showErrorRetryDialog(action: ((Boolean?) -> Unit)? = null) {
        if (isAdded) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.gift_card_purchase_failed),
                getString(R.string.gift_card_error),
                getString(R.string.cancel),
                getString(R.string.try_again)
            ).show(requireActivity()) { action?.invoke(it) }
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
        binding.cancelButton.animate().cancel()

        // Fade out cancel button
        binding.cancelButton.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.cancelButton.isVisible = false
                binding.confirmButton.text = ""
                binding.confirmButton.isClickable = false

                // Expand confirm button
                val constraintSet = ConstraintSet()
                constraintSet.clone(binding.rootLayout)

                constraintSet.clear(R.id.confirm_button, ConstraintSet.START)
                constraintSet.connect(
                    R.id.confirm_button,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    dpToPx(15)
                )
                constraintSet.connect(
                    R.id.confirm_button,
                    ConstraintSet.END,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.END,
                    dpToPx(15)
                )

                TransitionManager.beginDelayedTransition(binding.rootLayout)
                constraintSet.applyTo(binding.rootLayout)

                binding.confirmButtonLoading.isVisible = true
            }
            .start()
    }

    private fun hideLoading() {
        if (isAdded) {
            // Restore Cancel button
            binding.cancelButton.alpha = 0f
            binding.cancelButton.isVisible = true
            binding.cancelButton.animate()
                .alpha(1f)
                .setDuration(300)
                .start()

            // Shrink Confirm button, restore Cancel button position
            val constraintSet = ConstraintSet()
            constraintSet.clone(binding.rootLayout)

            constraintSet.clear(R.id.confirm_button, ConstraintSet.START)
            constraintSet.connect(
                R.id.confirm_button,
                ConstraintSet.START,
                R.id.guideline,
                ConstraintSet.START,
                dpToPx(15)
            )
            constraintSet.connect(
                R.id.confirm_button,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                dpToPx(15)
            )

            constraintSet.clear(R.id.cancel_button, ConstraintSet.END)
            constraintSet.connect(R.id.cancel_button, ConstraintSet.END, R.id.confirm_button, ConstraintSet.START, 8)
            constraintSet.connect(
                R.id.cancel_button,
                ConstraintSet.START,
                ConstraintSet.PARENT_ID,
                ConstraintSet.START,
                dpToPx(15)
            )

            TransitionManager.beginDelayedTransition(binding.rootLayout)
            constraintSet.applyTo(binding.rootLayout)

            binding.confirmButton.setText(R.string.purchase_gift_card_confirm)
            binding.confirmButtonLoading.isGone = true
            binding.confirmButton.isClickable = true
        }
    }

    private fun addInfoRow(container: LinearLayout, label: String, value: String) {
        val topPx = dpToPx(12)
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = topPx }
        }
        val labelView = AppCompatTextView(requireContext()).apply {
            text = label
            TextViewCompat.setTextAppearance(this, R.style.Caption_Medium_Tertiary)
            setPadding(0, 0, 0, topPx)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = AppCompatTextView(requireContext()).apply {
            text = value
            TextViewCompat.setTextAppearance(this, R.style.Caption)
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(labelView)
        row.addView(valueView)
        container.addView(row)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
