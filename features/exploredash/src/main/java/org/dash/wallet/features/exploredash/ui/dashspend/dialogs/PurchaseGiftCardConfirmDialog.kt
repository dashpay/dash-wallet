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
import androidx.annotation.StyleRes
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.TransitionManager
import coil.load
import coil.size.Scale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.DirectPayException
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.discountBy
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import org.dash.wallet.features.exploredash.ui.dashspend.DashSpendViewModel
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment(R.layout.dialog_confirm_purchase_gift_card) {
    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardConfirmDialog::class.java)
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
        val paymentValue = viewModel.giftCardPaymentValue.value
        val savingsFraction = merchant.savingsFraction
        binding.merchantName.text = merchant.name
        merchant.logoLocation?.let { logoLocation ->
            binding.merchantLogo.load(logoLocation) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(R.drawable.ic_image_placeholder)
                error(R.drawable.ic_image_placeholder)
            }
        }
        binding.giftCardDiscountValue.text = GenericUtils.formatPercent(savingsFraction)
        binding.giftCardTotalValue.text = paymentValue.toFormattedString()
        val discountedValue = paymentValue.discountBy(savingsFraction)
        binding.giftCardYouPayValue.text = discountedValue.toFormattedStringRoundUp()
        binding.purchaseCardValue.text = paymentValue.toFormattedString()

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
            if (authManager.authenticate(requireActivity()) == null) {
                hideLoading()
                return@launch
            }

            showLoading()

            val data = try {
                viewModel.purchaseGiftCard()
            } catch (ex: CTXSpendException) {
                hideLoading()
                when {
                    ex.isNetworkError -> {
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
                    ex.errorCode == 400 && ex.isLimitError -> {
                        viewModel.logError(ex,"${ex.serviceName} returned error: limits")
                        AdaptiveDialog.create(
                            R.drawable.ic_error,
                            getString(R.string.gift_card_purchase_failed),
                            getString(R.string.gift_card_limit_error),
                            getString(R.string.button_close),
                            if (ex.serviceName == ServiceName.CTXSpend) getString(R.string.gift_card_contact_ctx) else getString(R.string.gift_card_contact_piggycards)
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
                    ex.errorCode == 500 -> {
                        viewModel.logError(ex,"${ex.serviceName} returned error: Error 500")
                        AdaptiveDialog.create(
                            R.drawable.ic_error,
                            getString(R.string.gift_card_purchase_failed),
                            getString(R.string.gift_card_server_error),
                            getString(R.string.button_close),
                            if (ex.serviceName == ServiceName.CTXSpend) getString(R.string.gift_card_contact_ctx) else getString(R.string.gift_card_contact_piggycards)
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
                    ex.isRegionNotAllowed -> {
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
                    else -> {
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
                return@launch
            }

            if (!data.cryptoAmount.isNullOrEmpty() && viewModel.needsCrowdNodeWarning(data.cryptoAmount)) {
                val shouldContinue = MinimumBalanceDialog().showAsync(requireActivity())

                if (shouldContinue != true) {
                    hideLoading()
                    return@launch
                }
            }

            val transactionId = createSendingRequestFromDashUri(data.paymentUrls?.get("DASH.DASH")!!)
            transactionId?.let {
                enterAmountViewModel.clearSavedState()
                viewModel.saveGiftCardDummy(transactionId, data)
                showGiftCardDetailsDialog(transactionId, data.id)
            }
        }
    }

    private suspend fun createSendingRequestFromDashUri(url: String): Sha256Hash? {
        return try {
            viewModel.createSendingRequestFromDashUri(url)
        } catch (x: InsufficientMoneyException) {
            hideLoading()
            log.error("purchaseGiftCard InsufficientMoneyException", x)
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.insufficient_money_title),
                getString(R.string.insufficient_money_msg),
                getString(R.string.button_close)
            ).show(requireActivity())
            null
        } catch (ex: DirectPayException) {
            log.error("purchaseGiftCard DirectPayException", ex)
            hideLoading()
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
            null
        } catch (ex: Exception) {
            log.error("purchaseGiftCard error", ex)
            hideLoading()
            val message = getString(when {
                ex.cause is BitcoinURIParseException && ex.message?.contains("mismatched network") == true -> R.string.gift_card_error_wrong_network
                else -> R.string.gift_card_error
            })
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.send_coins_error_msg),
                message,
                getString(R.string.button_close),
                getString(R.string.gift_card_contact_ctx)
            ).show(requireActivity()) { result ->
                if (result == true) {
                    val intent = viewModel.createEmailIntent(
                        subject = "DashPay DashSpend Issue: Purchase Error",
                        sendToService = false,
                        CTXSpendException(ex.message ?: "purchase gift card error: sending payment", viewModel.giftCardMerchant.value?.source, cause = ex)
                    )

                    val chooser = Intent.createChooser(
                        intent,
                        getString(R.string.report_issue_dialog_mail_intent_chooser)
                    )
                    launcher.launch(chooser)
                }
            }
            null
        }
    }

    private fun showErrorRetryDialog(action: ((Boolean?) -> Unit)? = null) {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.gift_card_purchase_failed),
            getString(R.string.gift_card_error),
            getString(R.string.cancel),
            getString(R.string.try_again)
        ).show(requireActivity()) { action?.invoke(it) }
    }

    private fun showGiftCardDetailsDialog(txId: Sha256Hash, giftCardId: String) {
        GiftCardDetailsDialog.newInstance(txId).show(requireActivity()).also {
            val navController = findNavController()
            navController.popBackStack(navController.graph.startDestinationId, false)

            this@PurchaseGiftCardConfirmDialog.dismissAllowingStateLoss()
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
