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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.copy
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogGiftCardDetailsBinding
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.utils.DashDirectConstants

class GiftCardDetailsDialog : OffsetDialogFragment() {
    @StyleRes
    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true
    private val exploreViewModel: ExploreViewModel by activityViewModels()

    private val binding by viewBinding(DialogGiftCardDetailsBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_gift_card_details, container, false)
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
        }

        paymentValue?.let {
            binding.originalPurchaseValue.text =
                GenericUtils.fiatToString(it.second)
        }
        binding.purchaseCardNumber.text = "122222233"
        binding.purchaseCardPin.text = "12222"
        binding.copyCardNumber.setOnClickListener {
            binding.purchaseCardNumber.text.toString().copy(requireActivity(), "card number")
        }

        binding.copyCardPin.setOnClickListener {
            binding.purchaseCardPin.text.toString().copy(requireActivity(), "card pin")
        }

        binding.purchaseSeeHowToUseGiftCardLabel.setOnClickListener {
            binding.purchaseSeeHowToUseGiftCardLabel.isVisible = false
            binding.purchaseSeeHowToUseGiftCard.isVisible = true
        }
        binding.collapseButton.setOnClickListener {
            dismiss()
        }

        val id = exploreViewModel.transcation_id.toString()
        binding.viewTransactionDetailsCard.setOnClickListener {
            findNavController().navigate(Uri.parse("${Constants.DEEP_LINK_PREFIX}/transactions/$id"))
        }

        binding.checkCurrentBalance.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DashDirectConstants.CHECK_BALANCE_URL))
            requireContext().startActivity(intent)
        }
    }
}
