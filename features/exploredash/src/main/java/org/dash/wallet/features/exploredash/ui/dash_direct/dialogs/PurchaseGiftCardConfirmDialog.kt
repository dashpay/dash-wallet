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

package org.dash.wallet.features.exploredash.ui.dash_direct.dialogs

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.target.ImageViewTarget
import coil.transform.RoundedCornersTransformation
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
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.dash_direct.DashDirectViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants.DEFAULT_DISCOUNT
import org.dash.wallet.features.exploredash.utils.exploreViewModels

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment() {
    @StyleRes override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)
    private val viewModel by exploreViewModels<DashDirectViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_confirm_purchase_gift_card, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchant = viewModel.purchaseGiftCardDataMerchant
        val paymentValue = viewModel.purchaseGiftCardDataPaymentValue
        val savingsPercentage = merchant?.savingsPercentage ?: DEFAULT_DISCOUNT
        merchant?.let {
            binding.merchantName.text = it.name
            loadIconAndCacheForDatabase(it)
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
            lifecycleScope.launch {
                when (val response = viewModel.purchaseGiftCard()) {
                    is ResponseResource.Success -> {
                        if (response.value?.data?.success == true) {
                            response.value?.data?.uri?.let {
                                try {
                                    val transaction = viewModel.createSendingRequestFromDashUri(it)
                                    response.value?.data?.paymentId?.let { paymentId ->
                                        response.value?.data?.orderId?.let { orderId ->
                                            getPaymentStatus(
                                                paymentId,
                                                orderId,
                                                transaction,
                                                merchant,
                                                paymentValue
                                            )
                                        }
                                    }
                                } catch (x: InsufficientMoneyException) {
                                    Log.e(this::class.java.simpleName, "purchaseGiftCard InsufficientMoneyException")
                                    AdaptiveDialog.create(
                                        R.drawable.ic_info_red,
                                        getString(R.string.insufficient_money_title),
                                        getString(R.string.insufficient_money_msg),
                                        getString(R.string.close)
                                    ).show(requireActivity())
                                    x.printStackTrace()
                                    false
                                } catch (ex: Exception) {
                                    Log.e(this::class.java.simpleName, "purchaseGiftCard error")

                                    AdaptiveDialog.create(
                                        R.drawable.ic_info_red,
                                        getString(R.string.send_coins_error_msg),
                                        getString(R.string.insufficient_money_msg),
                                        getString(R.string.close)
                                    ).show(requireActivity())
                                    ex.printStackTrace()
                                    false
                                }
                            }
                        }
                    }

                    is ResponseResource.Failure -> {
                        Log.e(this::class.java.simpleName, response.errorBody ?: "purchaseGiftCard error")
                    }
                    else -> {}
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
                    response.value?.data?.giftCardId?.let {
                        getGift(it, transaction, merchant, paymentValue)
                    }
                } else {
//                    AdaptiveDialog.create(
//                        R.drawable.ic_info_red,
//                        getString(R.string.something_wrong_title),
//                        getString(R.string.retry),
//                        getString(R.string.retry)
//                    ).show(requireActivity()) {
//                        lifecycleScope.launch {
//                            getPaymentStatus(
//                                paymentId,
//                                orderId,
//                                transaction,
//                                merchant,
//                                paymentValue
//                            )
//                        }
//                    }
                }
            }
            is ResponseResource.Failure -> {
                Log.e(this::class.java.simpleName, response.errorBody ?: "purchaseGiftCard error")
            }
            else -> {}
        }
    }

    private suspend fun getGift(
        giftCardId: Long,
        transaction: Transaction,
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?
    ) {
        when (val response = viewModel.getGiftCardDetails(giftCardId)) {
            is ResponseResource.Success -> {
                if (response.value?.successful == true) {
                    showGiftCardDetailsDialog(merchant, paymentValue, transaction.txId)
                }
            }
            is ResponseResource.Failure -> {
                Log.e(this::class.java.simpleName, response.errorBody ?: "purchaseGiftCard error")
            }
            else -> {}
        }
    }
    private fun showGiftCardDetailsDialog(
        merchant: Merchant?,
        paymentValue: Pair<Coin, Fiat>?,
        txId: Sha256Hash

    ) {
        GiftCardDetailsDialog.newInstance(
            GiftCardDetailsDialogModel(
                merchantName = merchant?.name,
                merchantLogo = merchant?.logoLocation,
                giftCardPrice = GenericUtils.fiatToString(paymentValue?.second),
                transactionId = txId.toString()
            )
        ).show(requireActivity()).also {
            val navController = findNavController()
            navController.popBackStack(navController.graph.startDestinationId, false)

            this@PurchaseGiftCardConfirmDialog.dismiss()
        }
    }

    private fun loadIconAndCacheForDatabase(merchant: Merchant) {
        val request = ImageRequest.Builder(requireContext())
            .data(merchant.logoLocation)
            .crossfade(200)
            .scale(Scale.FILL)
            .size(150) // This size is for the stored merchant icon. 50x3 for xxhdpi screens.
            .placeholder(R.drawable.ic_image_placeholder)
            .error(R.drawable.ic_image_placeholder)
            .transformations(RoundedCornersTransformation(resources.getDimensionPixelSize(R.dimen.logo_corners_radius).toFloat()))
            .target(object: ImageViewTarget(binding.merchantLogo) {
                override fun onSuccess(result: Drawable) {
                    super.onSuccess(result)
                    merchant.iconBitmap = result.toBitmap()
                }
            })
            .build()
        requireContext().imageLoader.enqueue(request)
    }
}
