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
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.GiftCard
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.dashdirectgiftcard.GetGiftCardResponse
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

        val merchant = viewModel.purchaseGiftCardDataMerchant
        val paymentValue = viewModel.purchaseGiftCardDataPaymentValue
        val savingsPercentage = merchant?.savingsPercentage ?: DEFAULT_DISCOUNT
        merchant?.let {
            binding.merchantName.text = it.name
            it.logoLocation?.let { logoLocation ->
                binding.merchantLogo.load(logoLocation) {
                    crossfade(true)
                    scale(Scale.FILL)
                    placeholder(R.drawable.ic_image_placeholder)
                    error(R.drawable.ic_image_placeholder)
                }
            }
            binding.giftCardDiscountValue.text = GenericUtils.formatPercent(savingsPercentage)
        }

        paymentValue?.let {
            binding.giftCardTotalValue.text = GenericUtils.fiatToString(it.second)

            val discountedValue = viewModel.getDiscountedAmount(it.second, savingsPercentage)
            binding.giftCardYouPayValue.text = GenericUtils.fiatToStringRoundUp(discountedValue)

            binding.purchaseCardValue.text = GenericUtils.fiatToString(it.second)
        }

        binding.collapseButton.setOnClickListener { dismiss() }

        binding.confirmButton.setOnClickListener {
            showLoading()
            lifecycleScope.launch {
                when (val response = viewModel.purchaseGiftCard()) {
                    is ResponseResource.Success -> {
                        if (response.value?.data?.success == true) {
                            response.value?.data?.uri?.let {
                                var transaction: Transaction? = null
                                try {
                                    transaction = viewModel.createSendingRequestFromDashUri(it)
                                } catch (ex: InsufficientMoneyException) {
                                    hideLoading()
                                    log.error("purchaseGiftCard error", ex)
                                    AdaptiveDialog.create(
                                        R.drawable.ic_info_red,
                                        getString(R.string.insufficient_money_title),
                                        getString(R.string.insufficient_money_msg),
                                        getString(R.string.close)
                                    ).show(requireActivity())
                                    ex.printStackTrace()
                                } catch (ex: Exception) {
                                    log.error("purchaseGiftCard error", ex)
                                    hideLoading()
                                    AdaptiveDialog.create(
                                        R.drawable.ic_info_red,
                                        getString(R.string.send_coins_error_msg),
                                        getString(R.string.insufficient_money_msg),
                                        getString(R.string.close)
                                    )
                                        .show(requireActivity())
                                    ex.printStackTrace()
                                }
                                transaction?.let {
                                    response.value?.data?.paymentId?.let { paymentId ->
                                        response.value?.data?.orderId?.let { orderId ->
                                            getPaymentStatus(paymentId, orderId, it, merchant, paymentValue)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is ResponseResource.Failure -> {
                        hideLoading()
                        log.error("purchaseGiftCard error ${response.errorCode}: ${response.errorBody}")
                        showErrorRetryDialog { dismiss() }
                    }
                    else -> { }
                }
            }
        }
    }

    private suspend fun getPaymentStatus(
        paymentId: String,
        orderId: String,
        transaction: Transaction,
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?
    ) {
        when (val response = viewModel.getPaymentStatus(paymentId, orderId)) {
            is ResponseResource.Success -> {
                if (response.value?.data?.status == "paid") {
                    response.value?.data?.giftCardId?.let { getGiftCard(it, transaction, merchant, paymentValue) }
                } else if (response.value?.data?.status == "unpaid") {
                    hideLoading()
                    showErrorRetryDialog {
                        if (it == true) {
                            showLoading()
                            lifecycleScope.launch {
                                getPaymentStatus(paymentId, orderId, transaction, merchant, paymentValue)
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
                            getPaymentStatus(paymentId, orderId, transaction, merchant, paymentValue)
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
        transaction: Transaction,
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?
    ) {
        when (val response = viewModel.getGiftCardDetails(giftCardId)) {
            is ResponseResource.Success -> {
                if (response.value?.successful == true) {
                    showGiftCardDetailsDialog(merchant, paymentValue, transaction.txId, response.value?.data)
                }
            }
            is ResponseResource.Failure -> {
                hideLoading()
                log.error("getGiftCardDetails error ${response.errorCode}: ${response.errorBody}")
                showErrorRetryDialog {
                    if (it == true) {
                        showLoading()
                        lifecycleScope.launch { getGiftCard(giftCardId, transaction, merchant, paymentValue) }
                    }
                }
            }
            else -> { }
        }
    }
    private fun showGiftCardDetailsDialog(
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?,
        txId: Sha256Hash,
        data: GetGiftCardResponse.Data?
    ) {
        GiftCardDetailsDialog.newInstance(
            GiftCard(
                merchantName = merchant?.name,
                merchantLogo = merchant?.logoLocation,
                price = GenericUtils.fiatToString(paymentValue?.second),
                transactionId = txId.toString(),
                number = data?.cardNumber,
                pin = data?.cardPin,
//                    barcodeImg = data?.barcodeUrl, TODO
                checkCurrentBalanceUrl = merchant?.website
            )
        )
            .show(requireActivity())
            .also {
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
