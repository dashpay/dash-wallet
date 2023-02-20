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
import org.dash.wallet.features.exploredash.ui.PurchaseGiftCardViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants.DEFAULT_DISCOUNT

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment() {
    @StyleRes
    override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)
    private val purchaseGiftCardViewModel: PurchaseGiftCardViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
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
            binding.giftCardTotalValue.text =
                GenericUtils.fiatToString(it.second)

            val discountedValue = purchaseGiftCardViewModel.getDiscountedAmount(it.second, savingsPercentage)
            binding.giftCardYouPayValue.text =
                GenericUtils.fiatToStringRoundUp(discountedValue)

            binding.purchaseCardValue.text =
                GenericUtils.fiatToString(it.second)
        }

        binding.collapseButton.setOnClickListener {
            dismiss()
        }

        binding.confirmButton.setOnClickListener {
            lifecycleScope.launch {
                when (val response = purchaseGiftCardViewModel.purchaseGiftCard()) {
                    is ResponseResource.Success -> {
                        if (response.value?.data?.success == true) {
                            response.value?.data?.uri?.let {
                                try {
                                    val transaction = purchaseGiftCardViewModel.createSendingRequestFromDashUri(it)
                                    showGiftCardDetailsDialog(merchant, paymentValue, transaction.txId)
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
                }
            }
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
}
