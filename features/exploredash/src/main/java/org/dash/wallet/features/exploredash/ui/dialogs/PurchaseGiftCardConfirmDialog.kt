/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
import org.dash.wallet.features.exploredash.data.model.GiftCardDetailsDialogModel
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.dashdirectgiftcard.GetGiftCardResponse
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.PurchaseGiftCardViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants.DEFAULT_DISCOUNT

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment() {
    @StyleRes override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)
    private val purchaseGiftCardViewModel: PurchaseGiftCardViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_confirm_purchase_gift_card, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchant = purchaseGiftCardViewModel.purchaseGiftCardDataMerchant
        val paymentValue = purchaseGiftCardViewModel.purchaseGiftCardDataPaymentValue
        val savingsPercentage = merchant?.savingsPercentage ?: DEFAULT_DISCOUNT
        merchant?.let {
            binding.merchentName.text = it.name
            it.logoLocation?.let { logoLocation ->
                Glide.with(requireContext())
                    .load(logoLocation)
                    .placeholder(org.dash.wallet.common.R.drawable.ic_image_placeholder)
                    .error(org.dash.wallet.common.R.drawable.ic_image_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(binding.merchentLogo)
            }
            binding.giftCardDiscountValue.text = GenericUtils.formatPercent(savingsPercentage)
        }

        paymentValue?.let {
            binding.giftCardTotalValue.text = GenericUtils.fiatToString(it.second)

            val discountedValue = purchaseGiftCardViewModel.getDiscountedAmount(it.second, savingsPercentage)
            binding.giftCardYouPayValue.text = GenericUtils.fiatToStringRoundUp(discountedValue)

            binding.purchaseCardValue.text = GenericUtils.fiatToString(it.second)
        }

        binding.collapseButton.setOnClickListener { dismiss() }

        binding.confirmButton.setOnClickListener {
            onConfirmButtonClicked(merchant, paymentValue)
        }
    }

    private fun onConfirmButtonClicked(
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?
    ) {
        showLoading()
        lifecycleScope.launch {
            when (val response = purchaseGiftCardViewModel.purchaseGiftCard()) {
                is ResponseResource.Success -> {
                    if (response.value?.data?.success == true) {
                        response.value?.data?.uri?.let {
                            var transaction: Transaction? = null
                            try {
                                transaction =
                                    purchaseGiftCardViewModel.createSendingRequestFromDashUri(it)
                            } catch (x: InsufficientMoneyException) {
                                hideLoading()
                                Log.e(
                                    this::class.java.simpleName,
                                    "purchaseGiftCard InsufficientMoneyException"
                                )
                                AdaptiveDialog.create(
                                    R.drawable.ic_info_red,
                                    getString(R.string.insufficient_money_title),
                                    getString(R.string.insufficient_money_msg),
                                    getString(R.string.close)
                                )
                                    .show(requireActivity())
                                x.printStackTrace()
                            } catch (ex: Exception) {
                                Log.e(this::class.java.simpleName, "purchaseGiftCard error")
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
                                        getPaymentStatus(
                                            paymentId,
                                            orderId,
                                            it,
                                            merchant,
                                            paymentValue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    hideLoading()
                    Log.e(this::class.java.simpleName, "purchaseGiftCard error")
                    showErrorRetryDialog { dismiss() }
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
        when (val response = purchaseGiftCardViewModel.getPaymentStatus(paymentId, orderId)) {
            is ResponseResource.Success -> {
                if (response.value?.data?.status == "paid") {
                    response.value?.data?.giftCardId?.let { getGift(it, transaction, merchant, paymentValue) }
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
            else -> {
                hideLoading()
                Log.e(this::class.java.simpleName, "purchaseGiftCard error")
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
    }

    private fun showErrorRetryDialog(action: ((Boolean?) -> Unit)? = null) {
        AdaptiveDialog.create(
                R.drawable.ic_info_red,
                getString(R.string.gift_card_purchase_failed),
                getString(R.string.gift_card_error),
                getString(R.string.cancel),
                getString(R.string.try_again)
            )
            .show(requireActivity()) { action?.invoke(it) }
    }

    private suspend fun getGift(
        giftCardId: Long,
        transaction: Transaction,
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?
    ) {
        when (val response = purchaseGiftCardViewModel.getGiftCardDetails(giftCardId)) {
            is ResponseResource.Success -> {
                if (response.value?.successful == true) {
                    showGiftCardDetailsDialog(merchant, paymentValue, transaction.txId, response?.value?.data)
                }
            }
            else -> {
                hideLoading()
                Log.e(this::class.java.simpleName, "purchaseGiftCard error")
                showErrorRetryDialog {
                    if (it == true) {
                        showLoading()
                        lifecycleScope.launch { getGift(giftCardId, transaction, merchant, paymentValue) }
                    }
                }
            }
        }
    }
    private fun showGiftCardDetailsDialog(
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?,
        txId: Sha256Hash,
        data: GetGiftCardResponse.Data?
    ) {
        GiftCardDetailsDialog.newInstance(
                GiftCardDetailsDialogModel(
                    merchantName = merchant?.name,
                    merchantLogo = merchant?.logoLocation,
                    giftCardPrice = GenericUtils.fiatToString(paymentValue?.second),
                    transactionId = txId.toString(),
                    giftCardNumber = data?.cardNumber,
                    giftCardPin = data?.cardPin,
                    barcodeImg = data?.barcodeUrl,
                    giftCardCheckCurrentBalanceUrl = merchant?.website
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
