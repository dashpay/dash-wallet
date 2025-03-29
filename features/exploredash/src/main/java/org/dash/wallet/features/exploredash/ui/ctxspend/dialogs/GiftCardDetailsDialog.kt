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
package org.dash.wallet.features.exploredash.ui.ctxspend.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StyleRes
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import com.google.zxing.BarcodeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.ctxspend.model.Barcode
import org.dash.wallet.features.exploredash.databinding.DialogGiftCardDetailsBinding
import org.dash.wallet.features.exploredash.repository.CTXSpendException
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Currency
import androidx.core.net.toUri
import org.dash.wallet.features.exploredash.ExploreDatabase
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class GiftCardDetailsDialog : OffsetDialogFragment(R.layout.dialog_gift_card_details) {
    companion object {
        private val log = LoggerFactory.getLogger(GiftCardDetailsDialog::class.java)
        private const val ARG_TRANSACTION_ID = "transactionId"

        fun newInstance(transactionId: Sha256Hash) =
            GiftCardDetailsDialog().apply {
                arguments = bundleOf(
                    ARG_TRANSACTION_ID to transactionId
                )
            }
    }

    @StyleRes override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true
    private val binding by viewBinding(DialogGiftCardDetailsBinding::bind)
    private val viewModel by viewModels<GiftCardDetailsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.collapseButton.setOnClickListener { dismiss() }

        binding.copyCardNumber.setOnClickListener {
            binding.purchaseCardNumber.text.toString().copy(requireActivity(), "card number")
        }

        binding.copyCardPin.setOnClickListener {
            binding.purchaseCardPin.text.toString().copy(requireActivity(), "card pin")
        }

        binding.howToUseButton.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.DashSpend.HOW_TO_USE)
            binding.howToUseButton.isVisible = false
            binding.howToUseInfo.isVisible = true
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            state.giftCard?.let { bindGiftCardDetails(binding, it) }

            val bitmap = state.icon
            val iconSize = resources.getDimensionPixelSize(R.dimen.transaction_details_icon_size)

            if (bitmap != null) {
                binding.merchantLogo.load(bitmap) {
                    crossfade(true)
                    scale(Scale.FILL)
                    transformations(RoundedCornersTransformation(iconSize * 2.toFloat()))
                    placeholder(R.drawable.ic_gift_card_tx)
                    error(R.drawable.ic_gift_card_tx)
                }

                binding.secondaryIcon.isVisible = true
                binding.secondaryIcon.setImageResource(R.drawable.ic_gift_card_tx)
            } else {
                binding.secondaryIcon.isVisible = false
                binding.merchantLogo.setImageResource(R.drawable.ic_gift_card_tx)
            }

            state.date?.let {
                val formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy, hh:mm a")
                binding.purchaseDate.text = it.format(formatter)
            }

            val barcode = state.barcode

            if (barcode != null) {
                decodeBarcode(barcode)
            } else {
                binding.purchaseCardBarcode.isVisible = false
                binding.barcodePlaceholder.isVisible = true
                binding.barcodeLoadingError.isVisible = false
            }

            val error = state.error

            if (error != null) {
                binding.infoLoadingIndicator.isVisible = false

                val message = if (error is CTXSpendException && error.resourceString != null) {
                    getString(error.resourceString!!.resourceId, *error.resourceString!!.args.toTypedArray())
                } else {
                    null // This message is not localized so don't display it.  It will be in the logs
                }

                binding.cardError.isVisible = true
                binding.cardError.text = message ?: getString(R.string.gift_card_details_error)
            } else {
                binding.cardError.isVisible = false
            }
        }

        (requireArguments().getSerializable(ARG_TRANSACTION_ID) as? Sha256Hash)?.let { transactionId ->
            viewModel.init(transactionId)
        }

        binding.viewTransactionDetailsCard.setOnClickListener {
            deepLinkNavigate(DeepLinkDestination.Transaction(viewModel.transactionId.toString()))
        }
    }

    private fun bindGiftCardDetails(binding: DialogGiftCardDetailsBinding, giftCard: GiftCard) {
        binding.merchantName.text = giftCard.merchantName
        val currencyFormat = (NumberFormat.getCurrencyInstance() as DecimalFormat).apply {
            this.currency = Currency.getInstance(Constants.USD_CURRENCY)
        }
        binding.originalPurchaseValue.text = currencyFormat.format(giftCard.price)
        binding.purchaseCardNumber.text = giftCard.number
        binding.cardNumberGroup.isVisible = !giftCard.number.isNullOrEmpty()
        binding.purchaseCardPin.text = giftCard.pin
        binding.cardPinGroup.isVisible = !giftCard.pin.isNullOrEmpty()
        binding.infoLoadingIndicator.isVisible = giftCard.number.isNullOrEmpty()

        binding.checkCurrentBalance.isVisible = giftCard.merchantUrl?.isNotEmpty() == true
        binding.checkCurrentBalance.setOnClickListener {
            giftCard.merchantUrl?.let {
                val intent = Intent(Intent.ACTION_VIEW, it.toUri())
                requireContext().startActivity(intent)
            }
        }
    }

    private fun decodeBarcode(barcode: Barcode) {
        binding.purchaseCardInfo.doOnLayout {
            lifecycleScope.launch {
                val margin = resources.getDimensionPixelOffset(R.dimen.details_horizontal_margin)
                val bitmap = try {
                    withContext(Dispatchers.Default) {
                        val size = Size(
                            binding.purchaseCardInfo.measuredWidth - margin * 2,
                            binding.purchaseCardBarcode.layoutParams.height
                        )
                        Qr.bitmap(barcode.value, barcode.barcodeFormat, size)
                    }
                } catch (ex: Exception) {
                    log.error("Failed to decode barcode", ex)
                    null
                }

                if (bitmap != null) {
                    binding.barcodeLoadingError.isVisible = false
                    binding.barcodePlaceholder.isVisible = false
                    binding.purchaseCardBarcode.isVisible = true

                    if (barcode.barcodeFormat == BarcodeFormat.QR_CODE) {
                        binding.purchaseCardBarcode.updateLayoutParams<ViewGroup.LayoutParams> {
                            height = resources.getDimensionPixelSize(R.dimen.barcode_qr_size)
                        }
                    }

                    binding.purchaseCardBarcode.load(bitmap) {
                        crossfade(true)
                        scale(Scale.FILL)
                        listener(
                            onError = { _, _ ->
                                binding.barcodePlaceholder.isVisible = false
                                binding.purchaseCardBarcode.isVisible = false
                                binding.barcodeLoadingError.isVisible = true
                            }
                        )
                    }
                } else {
                    binding.purchaseCardBarcode.isVisible = false
                    binding.barcodePlaceholder.isVisible = false
                    binding.barcodeLoadingError.isVisible = true
                }
            }
        }
    }
}
