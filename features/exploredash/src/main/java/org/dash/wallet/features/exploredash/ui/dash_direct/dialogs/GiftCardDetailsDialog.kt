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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import coil.load
import coil.size.Scale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.copy
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.GiftCardDetailsDialogModel
import org.dash.wallet.features.exploredash.databinding.DialogGiftCardDetailsBinding
import org.dash.wallet.features.exploredash.utils.DashDirectConstants

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class GiftCardDetailsDialog : OffsetDialogFragment() {
    @StyleRes override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    // private var purchaseGiftCardData: Pair<Pair<Coin, Fiat>, Merchant>? = null
    private val binding by viewBinding(DialogGiftCardDetailsBinding::bind)

    private var giftCardDetailsDialogModel: GiftCardDetailsDialogModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { giftCardDetailsDialogModel = it.getParcelable(ARG_MODEL) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_gift_card_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        giftCardDetailsDialogModel?.let {
            binding.merchentName.text = it.merchantName
            it.merchantLogo?.let { url ->
                binding.merchentLogo.load(url) {
                    crossfade(200)
                    scale(Scale.FILL)
                    placeholder(R.drawable.ic_image_placeholder)
                    error(R.drawable.ic_image_placeholder)
                }
            }
        }

        binding.originalPurchaseValue.text = giftCardDetailsDialogModel?.giftCardPrice

        binding.purchaseCardNumber.text = giftCardDetailsDialogModel?.giftCardNumber
        binding.purchaseCardPin.text = giftCardDetailsDialogModel?.giftCardPin

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
        binding.collapseButton.setOnClickListener { dismiss() }

        binding.viewTransactionDetailsCard.setOnClickListener {
            giftCardDetailsDialogModel?.transactionId?.let {
                if (it.isNotEmpty()) {
                    findNavController().navigate(Uri.parse("${Constants.DEEP_LINK_PREFIX}/transactions/$it"))
                }
            }
        }

        binding.checkCurrentBalance.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DashDirectConstants.CHECK_BALANCE_URL))
            requireContext().startActivity(intent)
        }
    }

    companion object {
        private const val ARG_MODEL = "argModel"

        fun newInstance(model: GiftCardDetailsDialogModel) =
            GiftCardDetailsDialog().apply { arguments = Bundle().apply { putParcelable(ARG_MODEL, model) } }
    }
}
