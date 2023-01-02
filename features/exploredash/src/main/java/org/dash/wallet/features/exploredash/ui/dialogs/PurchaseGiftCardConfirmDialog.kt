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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogConfirmPurchaseGiftCardBinding
import org.dash.wallet.features.exploredash.ui.ExploreViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseGiftCardConfirmDialog : OffsetDialogFragment() {
    @StyleRes
    override val backgroundStyle = R.style.PrimaryBackground

    private val exploreViewModel: ExploreViewModel by activityViewModels()

    private val binding by viewBinding(DialogConfirmPurchaseGiftCardBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_confirm_purchase_gift_card, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val merchent = exploreViewModel.purchaseGiftCardData?.second
        val paymentValue = exploreViewModel.purchaseGiftCardData?.first
        merchent?.let {
            binding.merchentName.text = it.name
            merchent.logoLocation?.let {
                Glide.with(requireContext())
                    .load(it)
                    .placeholder(org.dash.wallet.common.R.drawable.ic_image_placeholder)
                    .error(org.dash.wallet.common.R.drawable.ic_image_placeholder)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .into(binding.merchentLogo)
            }
            binding.giftCardDiscountValue.text = "0%"
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
            exploreViewModel.setConfirmPurchaseGiftCard(true)
            dismiss()
        }
    }
}
