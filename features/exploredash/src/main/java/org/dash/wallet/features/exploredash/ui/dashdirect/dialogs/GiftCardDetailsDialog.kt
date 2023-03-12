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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.annotation.StyleRes
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.copy
import org.dash.wallet.common.util.toFormattedString
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashdirect.model.GiftCard
import org.dash.wallet.features.exploredash.databinding.DialogGiftCardDetailsBinding

@AndroidEntryPoint
class GiftCardDetailsDialog : OffsetDialogFragment(R.layout.dialog_gift_card_details) {
    companion object {
        private const val ARG_MODEL = "argModel"

        fun newInstance(model: GiftCard) =
            GiftCardDetailsDialog().apply { arguments = bundleOf(ARG_MODEL to model) }
    }

    @StyleRes override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true
    private val binding by viewBinding(DialogGiftCardDetailsBinding::bind)
    private lateinit var giftCard: GiftCard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: might crash if restored by OS. Revert
        requireArguments().apply { giftCard = getParcelable(ARG_MODEL)!! }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.merchantName.text = giftCard.merchantName
        val iconSize = resources.getDimensionPixelSize(R.dimen.transaction_icon_size)
        binding.merchantLogo.load(giftCard.merchantLogo) {
            crossfade(200)
            scale(Scale.FILL)
            transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_image_placeholder)
        }

        val price = Fiat.valueOf(giftCard.currency, giftCard.price)
        binding.originalPurchaseValue.text = price.toFormattedString()

        binding.purchaseCardNumber.text = giftCard.number
        binding.purchaseCardPin.text = giftCard.pin

        binding.copyCardNumber.setOnClickListener {
            binding.purchaseCardNumber.text.toString().copy(requireActivity(), "card number")
        }

        binding.copyCardPin.setOnClickListener {
            binding.purchaseCardPin.text.toString().copy(requireActivity(), "card pin")
        }

        binding.howToUseButton.setOnClickListener {
            binding.howToUseButton.isVisible = false
            binding.howToUseInfo.isVisible = true
        }
        binding.collapseButton.setOnClickListener { dismiss() }

        binding.viewTransactionDetailsCard.setOnClickListener {
            findNavController().navigate(Uri.parse("${Constants.DEEP_LINK_PREFIX}/transactions/$it"))
        }

        binding.checkCurrentBalance.isVisible = giftCard.currentBalanceUrl?.isNotEmpty() == true
        binding.checkCurrentBalance.setOnClickListener {
            giftCard.currentBalanceUrl?.let {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                requireContext().startActivity(intent)
            }
        }

        if (giftCard.barcodeImg?.isNotEmpty() == true) {
            binding.purchaseCardBarcode.isVisible = true
            val imageRequest = ImageRequest.Builder(requireContext())
                .data(giftCard.barcodeImg)
                .target(binding.purchaseCardBarcode)
                .scale(Scale.FILL)
                .listener(
                    onStart = {
                        binding.barcodeLoadingIndicator.isVisible = true
                    },
                    onSuccess = { _, _ ->
                        binding.barcodeLoadingIndicator.isVisible = false
                    },
                    onError = { _, _ ->
                        binding.barcodeLoadingIndicator.isVisible = false
                        binding.barcodeLoadingError.isVisible = true
                    }
                )
                .build()
            requireContext().imageLoader.enqueue(imageRequest)
        }
    }
}
