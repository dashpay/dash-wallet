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

package org.dash.wallet.features.exploredash.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.databinding.ItemDetailsViewBinding

class ItemDetails(context: Context, attrs: AttributeSet): LinearLayout(context, attrs) {
    private val binding = ItemDetailsViewBinding.inflate(LayoutInflater.from(context), this)

    private var onSendDashClicked: (() -> Unit)? = null
    private var onReceiveDashClicked: (() -> Unit)? = null
    private var onShowAllLocationsClicked: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        val horizontalPadding = resources.getDimensionPixelOffset(R.dimen.details_horizontal_margin)
        updatePaddingRelative(start = horizontalPadding, end = horizontalPadding)
    }

    fun bindItem(item: SearchResult, isOnline: Boolean, isGrouped: Boolean) {
        if (item is Merchant) {
            bindMerchantDetails(item, isOnline, isGrouped)
        } else if (item is Atm) {
            bindAtmDetails(item)
        }
    }

    fun setOnSendDashClicked(listener: () -> Unit) {
        onSendDashClicked = listener
    }

    fun setOnShowAllLocationsClicked(listener: () -> Unit) {
        onShowAllLocationsClicked = listener
    }

    fun setOnReceiveDashClicked(listener: () -> Unit) {
        onReceiveDashClicked = listener
    }

    fun getMerchantType(type: String?): String {
        return when (cleanMerchantTypeValue(type)) {
            MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
            MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
            MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
            else -> ""
        }
    }

    private fun bindCommonDetails(item: SearchResult, isOnline: Boolean) {
        binding.apply {
            itemName.text = item.name
            itemAddress.text = item.displayAddress

            linkBtn.isVisible = !item.website.isNullOrEmpty()
            linkBtn.setOnClickListener {
                openWebsite(item.website!!)
            }

            directionBtn.isVisible = !isOnline &&
                    ((item.latitude != null && item.longitude != null) ||
                            !item.googleMaps.isNullOrBlank())
            directionBtn.setOnClickListener {
                openMaps(item)
            }

            callBtn.isVisible = !isOnline && !item.phone.isNullOrEmpty()
            callBtn.setOnClickListener {
                dialPhone(item.phone!!)
            }
        }
    }

    private fun bindMerchantDetails(merchant: Merchant, isOnline: Boolean, isGrouped: Boolean) {
        binding.apply {
            buySellContainer.isVisible = false
            locationHint.isVisible = false

            loadImage(merchant.logoLocation, itemImage)
            itemType.text = getMerchantType(merchant.type)
            itemAddress.isVisible = !isOnline
            showAllBtn.isVisible = !isOnline && isGrouped && merchant.physicalAmount > 1

            val isDash = merchant.paymentMethod?.trim()?.lowercase() == PaymentMethod.DASH
            val drawable = ResourcesCompat.getDrawable(
                resources,
                if (isDash) R.drawable.ic_dash else R.drawable.ic_gift_card, null
            )
            payBtn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)

            if (isDash) {
                payBtn.isVisible = true
                payBtn.text = context.getText(R.string.explore_pay_with_dash)
                payBtn.setOnClickListener { onSendDashClicked?.invoke() }
            } else {
                payBtn.isVisible = !merchant.deeplink.isNullOrBlank()
                payBtn.text = context.getText(R.string.explore_buy_gift_card)
                payBtn.setOnClickListener { openDeeplink(merchant.deeplink!!) }
            }

            showAllBtn.setOnClickListener { onShowAllLocationsClicked?.invoke() }

            if (isOnline) {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 1f
                }
                updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_online_margin_top))
            } else {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight =
                        ResourcesCompat.getFloat(resources, R.dimen.merchant_details_height_ratio)
                }
                updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_physical_margin_top))
            }

            bindCommonDetails(merchant, isOnline)
        }
    }

    private fun bindAtmDetails(atm: Atm) {
        binding.apply {
            payBtn.isVisible = false
            manufacturer.text = atm.manufacturer?.replaceFirstChar { it.uppercase() }
            itemType.isVisible = false
            showAllBtn.isVisible = false

            sellBtn.setOnClickListener { onSendDashClicked?.invoke() }
            buyBtn.setOnClickListener { onReceiveDashClicked?.invoke() }

            buyBtn.isVisible = atm.type != AtmType.SELL
            sellBtn.isVisible = atm.type != AtmType.BUY

            root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintPercentHeight =
                    ResourcesCompat.getFloat(resources, R.dimen.atm_details_height_ratio)
            }

            loadImage(atm.logoLocation, logoImg)
            loadImage(atm.coverImage, itemImage)

            bindCommonDetails(atm, false)
        }
    }

    private fun loadImage(image: String?, into: ImageView) {
        Glide.with(context)
            .load(image)
            .placeholder(R.drawable.ic_image_placeholder)
            .error(R.drawable.ic_image_placeholder)
            .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(into)
    }

    private fun openMaps(item: SearchResult) {
        val uri = if (!item.googleMaps.isNullOrBlank()) {
            item.googleMaps
        } else {
            context.getString(R.string.explore_maps_intent_uri, item.latitude!!, item.longitude!!)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        context.startActivity(intent)
    }

    private fun dialPhone(phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel: $phone"))
        context.startActivity(intent)
    }

    private fun openWebsite(website: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(website))
        context.startActivity(intent)
    }

    private fun openDeeplink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        context.startActivity(intent)
    }

    private fun cleanMerchantTypeValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }
}