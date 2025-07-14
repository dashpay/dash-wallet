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
import android.util.AttributeSet
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.net.toUri
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*

class ItemDetails(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    private var onSendDashClicked: ((isPayingWithDash: Boolean) -> Unit)? = null
    private var onReceiveDashClicked: (() -> Unit)? = null
    private var onShowAllLocationsClicked: (() -> Unit)? = null
    private var onBackButtonClicked: (() -> Unit)? = null
    private var onNavigationButtonClicked: (() -> Unit)? = null
    private var onDialPhoneButtonClicked: (() -> Unit)? = null
    private var onOpenWebsiteButtonClicked: (() -> Unit)? = null
    private var onBuyGiftCardButtonClicked: ((GiftCardProvider) -> Unit)? = null
    private var onExploreLogOutClicked: ((GiftCardProvider) -> Unit)? = null
    private var giftCardProviderPicked: ((GiftCardProvider?) -> Unit)? = null

    private var currentItem by mutableStateOf<SearchResult?>(null)
    private var isLoggedIn by mutableStateOf(false)
    private var userEmail by mutableStateOf<String?>(null)
    private var selectedProvider by mutableStateOf<GiftCardProvider?>(null)
    private var isAtm = false

    var log: Logger = LoggerFactory.getLogger(ItemDetails::class.java)
    private val composeView: ComposeView

    init {
        orientation = VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val item = currentItem
                if (item != null) {
                    log.debug("ItemDetails: Rendering item ${item.name}")
                    ItemDetailsContent(
                        item = item,
                        isLoggedIn = isLoggedIn,
                        userEmail = userEmail,
                        selectedProvider = selectedProvider,
                        onProviderSelected = { provider ->
                            selectedProvider = provider
                            giftCardProviderPicked?.invoke(provider)
                        },
                        onSendDashClicked = { isPayingWithDash ->
                            onSendDashClicked?.invoke(isPayingWithDash)
                        },
                        onReceiveDashClicked = {
                            onReceiveDashClicked?.invoke()
                        },
                        onShowAllLocationsClicked = {
                            onShowAllLocationsClicked?.invoke()
                        },
                        onBackButtonClicked = {
                            onBackButtonClicked?.invoke()
                        },
                        onNavigationButtonClicked = {
                            openMaps(item)
                            onNavigationButtonClicked?.invoke()
                        },
                        onDialPhoneButtonClicked = {
                            item.phone?.let { phone -> dialPhone(phone) }
                            onDialPhoneButtonClicked?.invoke()
                        },
                        onOpenWebsiteButtonClicked = {
                            item.website?.let { website -> openWebsite(website) }
                            onOpenWebsiteButtonClicked?.invoke()
                        },
                        onBuyGiftCardButtonClicked = { provider ->
                            onBuyGiftCardButtonClicked?.invoke(provider)
                        },
                        onExploreLogOutClicked = { provider ->
                            onExploreLogOutClicked?.invoke(provider)
                        }
                    )
                } else {
                    log.debug("ItemDetails: No item to display")
                    // Empty content when no item is bound
                }
            }
        }
        
        addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    fun bindItem(item: SearchResult) {
        log.debug("ItemDetails: bindItem called with ${item.name}")
        if (item is Merchant) {
            isAtm = false
        } else if (item is Atm) {
            isAtm = true
        }
        currentItem = item
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
    
    fun setOnBuyGiftCardButtonClicked(listener: (GiftCardProvider) -> Unit) {
        onBuyGiftCardButtonClicked = listener
    }

    fun setOnDashSpendLogOutClicked(listener: (GiftCardProvider) -> Unit) {
        onExploreLogOutClicked = listener
    }

    fun setGiftCardProviderPicked(listener: (GiftCardProvider?) -> Unit) {
        giftCardProviderPicked = listener
    }

    @SuppressLint("SetTextI18n")
    fun setDashSpendUser(email: String?, userSignIn: Boolean) {
        isLoggedIn = email?.isNotEmpty() == true && userSignIn
        userEmail = email
    }

    fun getMerchantType(type: String?): String {
        return when (cleanMerchantTypeValue(type)) {
            MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
            MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
            MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
            else -> ""
        }
    }

    private fun openMaps(item: SearchResult) {
        val uri = if (!item.googleMaps.isNullOrBlank()) {
            item.googleMaps
        } else {
            context.getString(R.string.explore_maps_intent_uri, item.latitude!!, item.longitude!!)
        }

        uri?.let {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
            context.startActivity(intent)
        }
    }

    private fun dialPhone(phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, "tel: $phone".toUri())
        context.startActivity(intent)
    }

    private fun openWebsite(website: String) {
        val fixedUrl = if (!website.startsWith("http")) "https://$website" else website
        val intent = Intent(Intent.ACTION_VIEW, fixedUrl.toUri())
        context.startActivity(intent)
    }

    private fun cleanMerchantTypeValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }
}