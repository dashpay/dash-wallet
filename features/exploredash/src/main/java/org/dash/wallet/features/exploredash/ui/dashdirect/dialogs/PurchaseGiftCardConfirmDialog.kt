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

package org.dash.wallet.features.exploredash.ui.dashdirect.dialogs

import android.os.Bundle
import android.view.View
import androidx.annotation.StyleRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.load
import coil.size.Scale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.common.util.toFormattedStringRoundUp
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashdirect.model.giftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.dashdirect.DashDirectViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants.DEFAULT_DISCOUNT
import org.dash.wallet.features.exploredash.utils.exploreViewModels
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment(R.layout.dialog_confirm_purchase_gift_card) {
    companion object {
        private val log = LoggerFactory.getLogger(PurchaseGiftCardConfirmDialog::class.java)
    }

    @StyleRes override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)
    private val viewModel by exploreViewModels<DashDirectViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchant = viewModel.giftCardMerchant
        val paymentValue = viewModel.giftCardPaymentValue
        val savingsPercentage = merchant.savingsPercentage ?: DEFAULT_DISCOUNT
        binding.merchantName.text = merchant.name
        merchant.logoLocation?.let { logoLocation ->
            binding.merchantLogo.load(logoLocation) {
                crossfade(true)
                scale(Scale.FILL)
                placeholder(R.drawable.ic_image_placeholder)
                error(R.drawable.ic_image_placeholder)
            }
        }
        binding.giftCardDiscountValue.text = GenericUtils.formatPercent(savingsPercentage)
        binding.giftCardTotalValue.text = paymentValue.toFormattedString()
        val discountedValue = viewModel.getDiscountedAmount(paymentValue, savingsPercentage)
        binding.giftCardYouPayValue.text = discountedValue.toFormattedStringRoundUp()
        binding.purchaseCardValue.text = paymentValue.toFormattedString()

        binding.collapseButton.setOnClickListener { dismiss() }
        binding.confirmButton.setOnClickListener { onConfirmButtonClicked() }
    }

    private fun onConfirmButtonClicked() {
        showLoading()
        lifecycleScope.launch {
            val data = viewModel.purchaseGiftCard()

            if (data?.uri != null && data.paymentId != null && data.orderId != null) {
                val transactionId = createSendingRequestFromDashUri(data.uri)
                transactionId?.let {
                    getPaymentStatus(data.paymentId, data.orderId, it)
                }
            } else {
                hideLoading()
                showErrorRetryDialog { dismiss() }
            }
        }
    }

    private suspend fun createSendingRequestFromDashUri(it: String): Sha256Hash? {
        return try {
            viewModel.createSendingRequestFromDashUri(it)
        } catch (x: InsufficientMoneyException) {
            hideLoading()
            log.error("purchaseGiftCard InsufficientMoneyException", x)
            AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.insufficient_money_title),
                getString(R.string.insufficient_money_msg),
                getString(R.string.button_close)
            ).show(requireActivity())
            null
        } catch (ex: Exception) {
            log.error("purchaseGiftCard error", ex)
            hideLoading()
            AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.send_coins_error_msg),
                getString(R.string.gift_card_error),
                getString(R.string.button_close)
            ).show(requireActivity())
            null
        }
    }

    private suspend fun getPaymentStatus(
        paymentId: String,
        orderId: String,
        transactionId: Sha256Hash
    ) {
        when (val response = viewModel.getPaymentStatus(paymentId, orderId)) {
            is ResponseResource.Success -> {
                if (response.value?.data?.status == "paid") {
                    response.value?.data?.giftCardId?.let { getGiftCard(it, transactionId) }
                } else if (response.value?.data?.status == "unpaid") {
                    hideLoading()
                    showErrorRetryDialog {
                        if (it == true) {
                            showLoading()
                            lifecycleScope.launch {
                                getPaymentStatus(paymentId, orderId, transactionId)
                            }
                        }
                    }
                }
            }
            is ResponseResource.Failure -> {
                hideLoading()
                log.error("getPaymentStatus error ${response.errorCode}: ${response.errorBody}")
                showErrorRetryDialog {
                    if (it == true) {
                        showLoading()
                        lifecycleScope.launch {
                            getPaymentStatus(paymentId, orderId, transactionId)
                        }
                    }
                }
            }
            else -> { }
        }
    }

    private fun showErrorRetryDialog(action: ((Boolean?) -> Unit)? = null) {
        AdaptiveDialog.create(
            R.drawable.ic_info_red,
            getString(R.string.gift_card_purchase_failed),
            getString(R.string.gift_card_error),
            getString(R.string.cancel),
            getString(R.string.try_again)
        ).show(requireActivity()) { action?.invoke(it) }
    }

    private suspend fun getGiftCard(
        giftCardId: Long,
        transactionId: Sha256Hash
    ) {
        when (val response = viewModel.getGiftCardDetails(giftCardId)) {
            is ResponseResource.Success -> {
                if (response.value?.successful == true && response.value?.data != null) {
                    showGiftCardDetailsDialog(transactionId, response.value!!.data!!)
                }
            }
            is ResponseResource.Failure -> {
                hideLoading()
                log.error("getGiftCardDetails error ${response.errorCode}: ${response.errorBody}")
                showErrorRetryDialog {
                    if (it == true) {
                        showLoading()
                        lifecycleScope.launch { getGiftCard(giftCardId, transactionId) }
                    }
                }
            }
            else -> { }
        }
    }
    private fun showGiftCardDetailsDialog(
        txId: Sha256Hash,
        data: GetGiftCardResponse.Data
    ) {
        viewModel.saveGiftCard(txId, data)
        GiftCardDetailsDialog.newInstance(txId, data.barcodeUrl).show(requireActivity()).also {
            val navController = findNavController()
            navController.popBackStack(navController.graph.startDestinationId, false)

            this@PurchaseGiftCardConfirmDialog.dismiss()
        }
    }

    private fun showLoading() {
        binding.confirmButton.text = ""
        binding.confirmButtonLoading.isVisible = true
        binding.confirmButton.isClickable = false
    }

    private fun hideLoading() {
        binding.confirmButton.setText(R.string.purchase_gift_card_confirm)
        binding.confirmButtonLoading.isGone = true
        binding.confirmButton.isClickable = true
    }
}
