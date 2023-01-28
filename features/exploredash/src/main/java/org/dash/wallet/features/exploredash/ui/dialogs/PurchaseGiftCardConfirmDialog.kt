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
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.GiftCardDetailsDialogModel
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.PurchaseGiftCardViewModel
import java.text.NumberFormat

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
            // This number formatter takes 0.0275 and returns 2.75% using current locale
            val percentFormat = NumberFormat.getPercentInstance()
            percentFormat.minimumFractionDigits = 2;
            binding.giftCardDiscountValue.text = percentFormat.format((it.savingsPercentage ?: 0.0) / 100.0)
        }

        paymentValue?.let {
            binding.giftCardTotalValue.text =
                GenericUtils.fiatToString(it.second)

            binding.giftCardYouPayValue.text =
                GenericUtils.fiatToString(it.second)

            binding.purchaseCardValue.text =
                GenericUtils.fiatToString(it.second)
        }

        binding.collapseButton.setOnClickListener {
            dismiss()
        }

        binding.confirmButton.setOnClickListener {
            lifecycleScope.launch {
                purchaseGiftCardViewModel.purchaseGiftCard()
                // TODO Change to response from API
                GiftCardDetailsDialog.newInstance(
                    GiftCardDetailsDialogModel(
                        merchantName = merchant?.name,
                        merchantLogo = merchant?.logoLocation,
                        giftCardPrice = GenericUtils.fiatToString(paymentValue?.second)
                    )
                ).show(requireActivity()).also {
                    val navController = findNavController()
                    navController.popBackStack(navController.graph.startDestinationId, false)

                    this@PurchaseGiftCardConfirmDialog.dismiss()
                }
            }
        }
    }
}
