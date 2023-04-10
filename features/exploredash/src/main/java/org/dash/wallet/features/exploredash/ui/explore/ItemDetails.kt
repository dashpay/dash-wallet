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

package org.dash.wallet.features.exploredash.ui.explore

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View.OnClickListener
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import coil.load
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.ui.getRoundedRippleBackground
import org.dash.wallet.common.util.makeLinks
import org.dash.wallet.common.util.maskEmail
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.dash.wallet.features.exploredash.databinding.ItemDetailsViewBinding
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import java.util.*

class ItemDetails(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val binding = ItemDetailsViewBinding.inflate(LayoutInflater.from(context), this)

    private var onSendDashClicked: ((isPayingWithDash: Boolean) -> Unit)? = null
    private var onReceiveDashClicked: (() -> Unit)? = null
    private var onShowAllLocationsClicked: (() -> Unit)? = null
    private var onBackButtonClicked: (() -> Unit)? = null
    private var onNavigationButtonClicked: (() -> Unit)? = null
    private var onDialPhoneButtonClicked: (() -> Unit)? = null
    private var onOpenWebsiteButtonClicked: (() -> Unit)? = null
    private var onBuyGiftCardButtonClicked: (() -> Unit)? = null
    private var onDashDirectLogOutClicked: (() -> Unit)? = null

    private var isLoggedIn = false
    private var isAtm = false

    init {
        orientation = VERTICAL
        val horizontalPadding = resources.getDimensionPixelOffset(R.dimen.details_horizontal_margin)
        updatePaddingRelative(start = horizontalPadding, end = horizontalPadding)
    }

    fun bindItem(item: SearchResult) {
        if (item is Merchant) {
            isAtm = false
            bindMerchantDetails(item)
        } else if (item is Atm) {
            isAtm = true
            bindAtmDetails(item)
        }

        refreshEmailVisibility()
    }

    fun setOnSendDashClicked(listener: (Boolean) -> Unit) {
        onSendDashClicked = listener
    }

    fun setOnShowAllLocationsClicked(listener: () -> Unit) {
        onShowAllLocationsClicked = listener
    }

    fun setOnReceiveDashClicked(listener: () -> Unit) {
        onReceiveDashClicked = listener
    }

    fun setOnBackButtonClicked(listener: () -> Unit) {
        onBackButtonClicked = listener
    }

    fun setOnNavigationButtonClicked(listener: () -> Unit) {
        onNavigationButtonClicked = listener
    }

    fun setOnDialPhoneButtonClicked(listener: () -> Unit) {
        onDialPhoneButtonClicked = listener
    }

    fun setOnOpenWebsiteButtonClicked(listener: () -> Unit) {
        onOpenWebsiteButtonClicked = listener
    }

    fun setOnBuyGiftCardButtonClicked(listener: () -> Unit) {
        onBuyGiftCardButtonClicked = listener
    }

    fun setOnDashDirectLogOutClicked(listener: () -> Unit) {
        onDashDirectLogOutClicked = listener
    }

    @SuppressLint("SetTextI18n")
    fun setDashDirectLogInUser(email: String?, userSignIn: Boolean) {
        isLoggedIn = email?.isNotEmpty() == true && userSignIn
        refreshEmailVisibility()
        email?.let {
            binding.loginDashDirectUser.text =
                context.resources.getString(R.string.logged_in_as, email.maskEmail()) +
                " " +
                context.resources.getString(R.string.log_out)

            binding.loginDashDirectUser.makeLinks(
                Pair(
                    context.resources.getString(R.string.log_out),
                    OnClickListener {
                        onDashDirectLogOutClicked?.invoke()
                        binding.loginDashDirectUser.isGone = true
                    }
                ),
                isUnderlineText = true
            )
        }
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
            itemAddress.text = item.getDisplayAddress("\n")

            val isMetric = Locale.getDefault().isMetric
            val distanceStr = item.getDistanceStr(isMetric)
            itemDistance.text =
                when {
                    distanceStr.isEmpty() -> ""
                    isMetric -> resources.getString(R.string.distance_kilometers, distanceStr)
                    else -> resources.getString(R.string.distance_miles, distanceStr)
                }
            itemDistance.isVisible = !isOnline && distanceStr.isNotEmpty()

            linkBtn.isVisible = !item.website.isNullOrEmpty()
            linkBtn.setOnClickListener {
                openWebsite(item.website!!)
                onOpenWebsiteButtonClicked?.invoke()
            }

            directionBtn.isVisible =
                !isOnline && ((item.latitude != null && item.longitude != null) || !item.googleMaps.isNullOrBlank())
            directionBtn.setOnClickListener {
                openMaps(item)
                onNavigationButtonClicked?.invoke()
            }

            callBtn.isVisible = !isOnline && !item.phone.isNullOrEmpty()
            callBtn.setOnClickListener {
                dialPhone(item.phone!!)
                onDialPhoneButtonClicked?.invoke()
            }
        }
    }

    private fun bindMerchantDetails(merchant: Merchant) {
        binding.apply {
            val isGrouped = merchant.physicalAmount > 0
            val isOnline = merchant.type == MerchantType.ONLINE

            buySellContainer.isVisible = false
            locationHint.isVisible = false
            backButton.isVisible = !isOnline && !isGrouped

            loadImage(merchant.logoLocation, itemImage)
            itemType.text = getMerchantType(merchant.type)
            itemAddress.isVisible = !isOnline
            showAllBtn.isVisible = !isOnline && isGrouped && merchant.physicalAmount > 1

            val isDash = merchant.paymentMethod?.trim()?.lowercase() == PaymentMethod.DASH
            val drawable =
                ResourcesCompat.getDrawable(
                    resources,
                    if (isDash) R.drawable.ic_dash_inverted else R.drawable.ic_gift_card_inverted,
                    null
                )
            payBtnIcon.setImageDrawable(drawable)

            if (isDash) {
                payBtn.isVisible = true
                payBtnTxt.text = context.getText(R.string.explore_pay_with_dash)
                payBtn.background = resources.getRoundedRippleBackground(R.style.PrimaryButtonTheme_Large_Blue)
                payBtn.setOnClickListener { onSendDashClicked?.invoke(true) }
            } else {
                // DashDirect allows payments via API, other sources require a deeplink
                payBtn.isVisible = merchant.source?.lowercase() == ServiceName.DashDirect ||
                    !merchant.deeplink.isNullOrBlank()
                payBtnTxt.text = context.getText(R.string.explore_buy_gift_card)
                payBtn.background = resources.getRoundedRippleBackground(R.style.PrimaryButtonTheme_Large_Orange)
                payBtn.setOnClickListener { onBuyGiftCardButtonClicked?.invoke() }
            }

            showAllBtn.setOnClickListener { onShowAllLocationsClicked?.invoke() }
            backButton.setOnClickListener { onBackButtonClicked?.invoke() }

            if (isOnline) {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentHeight = 1f }
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
            backButton.isVisible = false

            sellBtn.setOnClickListener { onSendDashClicked?.invoke(false) }
            buyBtn.setOnClickListener { onReceiveDashClicked?.invoke() }

            buyBtn.isVisible = atm.type != AtmType.SELL
            sellBtn.isVisible = atm.type != AtmType.BUY

            root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintPercentHeight = ResourcesCompat.getFloat(resources, R.dimen.atm_details_height_ratio)
            }

            loadImage(atm.logoLocation, logoImg)
            loadImage(atm.coverImage, itemImage)

            bindCommonDetails(atm, false)
        }
    }

    private fun loadImage(image: String?, into: ImageView) {
        into.load(image) {
            crossfade(200)
            scale(Scale.FILL)
            placeholder(R.drawable.ic_image_placeholder)
            error(R.drawable.ic_image_placeholder)
            transformations(
                RoundedCornersTransformation(resources.getDimensionPixelSize(R.dimen.logo_corners_radius).toFloat())
            )
        }
    }

    private fun openMaps(item: SearchResult) {
        val uri =
            if (!item.googleMaps.isNullOrBlank()) {
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

    private fun cleanMerchantTypeValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }

    private fun refreshEmailVisibility() {
        binding.loginDashDirectUser.isVisible = isLoggedIn && !isAtm
    }
}
