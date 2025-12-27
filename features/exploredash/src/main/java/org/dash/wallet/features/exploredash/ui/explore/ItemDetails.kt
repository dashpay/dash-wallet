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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashRadioButton
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.util.maskEmail
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProviderType
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.DenominationType
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import java.text.DecimalFormat
import java.util.*
import kotlin.String

@Composable
fun ItemDetails(
    item: SearchResult,
    modifier: Modifier = Modifier,
    isLoggedIn: Boolean = false,
    userEmail: String? = null,
    selectedProvider: GiftCardProviderType,
    onProviderSelected: (GiftCardProvider) -> Unit = {},
    onSendDashClicked: (Boolean) -> Unit = {},
    onReceiveDashClicked: () -> Unit = {},
    onShowAllLocationsClicked: () -> Unit = {},
    onBackButtonClicked: () -> Unit = {},
    onNavigationButtonClicked: () -> Unit = {},
    onDialPhoneButtonClicked: () -> Unit = {},
    onOpenWebsiteButtonClicked: () -> Unit = {},
    onBuyGiftCardButtonClicked: () -> Unit = {},
    onExploreLogOutClicked: () -> Unit = {}
) {
    when (item) {
        is Merchant -> {
            MerchantDetailsContent(
                merchant = item,
                isLoggedIn = isLoggedIn,
                userEmail = userEmail,
                selectedProvider = selectedProvider,
                onProviderSelected = onProviderSelected,
                onSendDashClicked = onSendDashClicked,
                onShowAllLocationsClicked = onShowAllLocationsClicked,
                onBackButtonClicked = onBackButtonClicked,
                onNavigationButtonClicked = onNavigationButtonClicked,
                onDialPhoneButtonClicked = onDialPhoneButtonClicked,
                onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked,
                onBuyGiftCardButtonClicked = onBuyGiftCardButtonClicked,
                onExploreLogOutClicked = onExploreLogOutClicked,
                modifier = modifier
            )
        }
        is Atm -> {
            AtmDetailsContent(
                atm = item,
                onSendDashClicked = onSendDashClicked,
                onReceiveDashClicked = onReceiveDashClicked,
                onNavigationButtonClicked = onNavigationButtonClicked,
                onDialPhoneButtonClicked = onDialPhoneButtonClicked,
                onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MerchantDetailsContent(
    merchant: Merchant,
    isLoggedIn: Boolean,
    userEmail: String?,
    selectedProvider: GiftCardProviderType,
    onProviderSelected: (GiftCardProvider) -> Unit,
    onSendDashClicked: (Boolean) -> Unit,
    onShowAllLocationsClicked: () -> Unit,
    onBackButtonClicked: () -> Unit,
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit,
    onBuyGiftCardButtonClicked: () -> Unit,
    onExploreLogOutClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = merchant.type == MerchantType.ONLINE
    val isGrouped = merchant.physicalAmount > 0
    val isDash = merchant.paymentMethod?.trim()?.lowercase() == PaymentMethod.DASH
    val hasMultipleProviders = merchant.giftCardProviders.size > 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isOnline) {
                    Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                } else {
                    Modifier.padding(horizontal = 20.dp)
                }
            )
    ) {
        // Back button for physical merchants
        if (!isOnline && !isGrouped) {
            TextButton(
                onClick = onBackButtonClicked,
                modifier = Modifier.padding(bottom = 15.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_small_back_arrow),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.explore_back_to_locations),
                    fontSize = 12.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MyTheme.Colors.backgroundSecondary)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Merchant header
                MerchantHeader(
                    merchant = merchant,
                    isOnline = isOnline
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (!isDash) {
                    if (hasMultipleProviders) {
                        MultipleProvidersSection(
                            providers = merchant.giftCardProviders,
                            selectedProvider = selectedProvider,
                            onProviderSelected = onProviderSelected
                        )
                    } else {
                        merchant.giftCardProviders.firstOrNull()?.let { provider ->
                            SingleProviderSection(
                                provider = provider
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }

                ActionButton(
                    merchant = merchant,
                    selectedProvider = selectedProvider,
                    isDash = isDash,
                    onSendDashClicked = onSendDashClicked,
                    onBuyGiftCardButtonClicked = onBuyGiftCardButtonClicked
                )

                // User login status
                if (isLoggedIn && userEmail != null && !isDash) {
                    Spacer(modifier = Modifier.height(16.dp))
                    UserLoginStatus(
                        email = userEmail,
                        onLogOutClicked = onExploreLogOutClicked
                    )
                }
            }
        }

        // Merchant details (address, phone, website) - only show if there are details
        val hasAddress = !isOnline && merchant.getDisplayAddress("\n").isNotEmpty()
        val hasPhone = !merchant.phone.isNullOrEmpty()
        val hasWebsite = !merchant.website.isNullOrEmpty()
        val hasShowAllLocations = !isOnline && isGrouped && merchant.physicalAmount > 1

        if (hasAddress || hasPhone || hasWebsite || hasShowAllLocations) {
            Spacer(modifier = Modifier.height(16.dp))
            ItemContactDetails(
                item = merchant,
                isOnline = isOnline,
                isGrouped = isGrouped,
                onShowAllLocationsClicked = onShowAllLocationsClicked,
                onNavigationButtonClicked = onNavigationButtonClicked,
                onDialPhoneButtonClicked = onDialPhoneButtonClicked,
                onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked
            )
        }
    }
}

@Composable
private fun MerchantHeader(
    merchant: Merchant,
    isOnline: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        AsyncImage(
            model = merchant.logoLocation ?: "",
            contentDescription = merchant.name,
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(id = R.drawable.ic_image_placeholder),
            error = painterResource(id = R.drawable.ic_image_placeholder)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = merchant.name ?: "",
                style = MyTheme.SubtitleSemibold,
                color = MyTheme.Colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = getMerchantTypeText(merchant.type, isOnline),
                style = MyTheme.Caption,
                color = MyTheme.Colors.textSecondary
            )
        }
    }
}

@Composable
private fun MultipleProvidersSection(
    providers: List<GiftCardProvider>,
    selectedProvider: GiftCardProviderType,
    onProviderSelected: (GiftCardProvider) -> Unit
) {
    val discountFormat = remember {
        DecimalFormat().apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
    }

    Column {
        Text(
            text = stringResource(R.string.select_gift_card_provider),
            style = MyTheme.CaptionMedium,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 2.dp)
        )

        Column {
            for (provider in providers) {
                ProviderOption(
                    providerName = provider.provider,
                    subtitle = if (provider.denominationsType == DenominationType.MinMax.value) {
                        stringResource(R.string.flexible_amounts)
                    } else {
                        stringResource(R.string.fixed_amounts)
                    },
                    discount = "-${discountFormat.format(provider.savingsPercentage.toDouble() / 100)}%",
                    isSelected = provider.provider == selectedProvider.name,
                    isEnabled = provider.active,
                    onSelected = { onProviderSelected(provider) }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SingleProviderSection(
    provider: GiftCardProvider
) {
    val discountFormat = remember {
        DecimalFormat().apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
    }
    val subtitle = if (provider.denominationsType == DenominationType.MinMax.value) {
        stringResource(R.string.flexible_amounts)
    } else {
        stringResource(R.string.fixed_amounts)
    }
    val discount = "-${discountFormat.format(provider.savingsPercentage.toDouble() / 100)}%"
    val isSelected = false
    val isEnabled = provider.active
    val onSelected = { }

    val backgroundColor = if (isSelected) MyTheme.Colors.dashBlue.copy(alpha = 0.1f) else Color.Transparent
    val borderColor = if (isSelected) MyTheme.Colors.dashBlue else MyTheme.Colors.gray300.copy(alpha = 0.5f)
    val textColor = if (isEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray400
    val subtitleColor = if (isEnabled) MyTheme.Colors.textTertiary else MyTheme.Colors.gray400
    DashRadioButton(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape = RoundedCornerShape(12.dp))
            // .border(1.dp, borderColor, shape = RoundedCornerShape(12.dp))
            .clickable(enabled = isEnabled) { if (isEnabled) onSelected() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        text = provider.provider,
        helpText = subtitle,
        selected = false,
        onClick = { },
        trailingText = discount,
        enabled = provider.active,
        onlyOption = true
    )
}

@Composable
private fun ProviderOption(
    providerName: String,
    subtitle: String,
    discount: String,
    isSelected: Boolean,
    isEnabled: Boolean,
    onSelected: () -> Unit
) {
    val backgroundColor = if (isSelected) MyTheme.Colors.dashBlue.copy(alpha = 0.1f) else Color.Transparent
    val borderColor = if (isSelected) MyTheme.Colors.dashBlue else MyTheme.Colors.gray300.copy(alpha = 0.5f)
    val textColor = if (isEnabled) MyTheme.Colors.textPrimary else MyTheme.Colors.gray400
    val subtitleColor = if (isEnabled) MyTheme.Colors.textTertiary else MyTheme.Colors.gray400

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, shape = RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, shape = RoundedCornerShape(12.dp))
            .clickable(enabled = isEnabled) { if (isEnabled) onSelected() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DashRadioButton(
            text = providerName,
            helpText = subtitle,
            selected = isSelected,
            onClick = onSelected,
            trailingText = discount,
            enabled = isEnabled
        )
    }
}

@Composable
private fun ActionButton(
    merchant: Merchant,
    selectedProvider: GiftCardProviderType,
    isDash: Boolean,
    onSendDashClicked: (Boolean) -> Unit,
    onBuyGiftCardButtonClicked: () -> Unit
) {
    val isEnabled = merchant.giftCardProviders.find {
        it.provider == selectedProvider.name
    }?.active ?: true

    Column {
        if (!isEnabled) {
            Text(
                text = stringResource(R.string.temporarily_unavailable),
                style = MyTheme.Overline,
                color = MyTheme.Colors.gray400,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )
        }
        // where can the user spend the gift card
        if (!isDash) {
            Text(
                text = stringResource(R.string.country_availability),
                style = MyTheme.Overline,
                color = MyTheme.Colors.gray400,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )
        }
        DashButton(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            text = stringResource(
                if (isDash) R.string.explore_pay_with_dash else R.string.explore_buy_gift_card
            ),
            leadingIcon = ImageVector.vectorResource(
                if (isDash) R.drawable.ic_dash_inverted else R.drawable.ic_gift_card
            ),
            style = if (isDash) Style.FilledBlue else Style.FilledOrange,
            size = Size.Medium,
            stretch = true,
            isEnabled = isEnabled,
            isLoading = false,
            onClick = {
                if (isDash) {
                    onSendDashClicked(true)
                } else {
                    onBuyGiftCardButtonClicked()
                }
            }
        )

        // Discount badge for Dash payments
        if (isDash && merchant.savingsFraction != 0.0) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .offset(y = (-24).dp)
                    .background(
                        color = MyTheme.Colors.textPrimary,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.explore_pay_with_dash_save,
                        merchant.savingsPercentageAsDouble
                    ),
                    style = MyTheme.Overline,
                    color = MyTheme.Colors.backgroundSecondary
                )
            }
        }
    }
}

@Composable
private fun UserLoginStatus(
    email: String,
    onLogOutClicked: () -> Unit
) {
    val annotatedText = buildAnnotatedString {
        append(stringResource(R.string.logged_in_as, email.maskEmail()))
        append(" ")
        pushStringAnnotation(tag = "logout", annotation = "logout")
        withStyle(
            SpanStyle(
                color = Color(0xFF008DE4),
                textDecoration = TextDecoration.Underline
            )
        ) {
            append(stringResource(R.string.log_out))
        }
        pop()
    }

    Text(
        text = annotatedText,
        fontSize = 13.sp,
        color = Color(0xFF525C66),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onLogOutClicked()
            }
    )
}

@Composable
private fun ItemContactDetails(
    item: SearchResult,
    isOnline: Boolean = false,
    isGrouped: Boolean = false,
    onShowAllLocationsClicked: () -> Unit = {},
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit
) {
    val hasAddress = !isOnline && item.getDisplayAddress("\n").isNotEmpty()
    val hasPhone = !item.phone.isNullOrEmpty()
    val hasWebsite = !item.website.isNullOrEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Only show card if there are details to display
        if (hasAddress || hasPhone || hasWebsite) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOnline) Color.White else MyTheme.Colors.backgroundSecondary
                )
            ) {
                Column(modifier = Modifier.padding(if (isOnline) 6.dp else 10.dp)) {
                    // Address - only for physical items
                    if (hasAddress) {
                        DetailItem(
                            title = stringResource(R.string.address),
                            content = item.getDisplayAddress("\n"),
                            subtitle = getDistanceText(item),
                            hasAction = item.hasCoordinates() || !item.googleMaps.isNullOrBlank(),
                            actionIcon = R.drawable.ic_direction,
                            onActionClick = onNavigationButtonClicked
                        )
                    }

                    // Phone
                    if (hasPhone) {
                        DetailItem(
                            title = stringResource(R.string.phone),
                            content = item.phone!!,
                            isClickable = true,
                            onContentClick = onDialPhoneButtonClicked
                        )
                    }

                    // Website
                    if (hasWebsite) {
                        DetailItem(
                            title = stringResource(R.string.website),
                            content = item.website!!,
                            isClickable = true,
                            onContentClick = onOpenWebsiteButtonClicked
                        )
                    }
                }
            }
        }

        // Show all locations - only for grouped physical merchants
        if (!isOnline && isGrouped && item is Merchant && item.physicalAmount > 1) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MyTheme.Colors.backgroundSecondary),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                ShowAllLocationsItem(
                    count = item.physicalAmount,
                    onClick = onShowAllLocationsClicked
                )
            }
        }
    }
}

@Composable
private fun DetailItem(
    title: String,
    content: String,
    subtitle: String? = null,
    isClickable: Boolean = false,
    hasAction: Boolean = false,
    actionIcon: Int? = null,
    onContentClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (isClickable && onContentClick != null) {
                        Modifier.clickable { onContentClick() }
                    } else {
                        Modifier
                    }
                )
        ) {
            Text(
                text = title,
                style = MyTheme.Overline,
                color = MyTheme.Colors.textSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = content,
                style = MyTheme.Body2Regular,
                color = if (isClickable) MyTheme.Colors.dashBlue else MyTheme.Colors.textPrimary
            )

            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MyTheme.Overline,
                    color = MyTheme.Colors.textSecondary
                )
            }
        }

        if (hasAction && actionIcon != null && onActionClick != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onActionClick,
                modifier = Modifier.padding(top = 16.dp).size(22.dp)
            ) {
                Icon(
                    painter = painterResource(id = actionIcon),
                    contentDescription = null,
                    tint = Color.Unspecified
                )
            }
        }
    }
}

@Composable
private fun ShowAllLocationsItem(
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.explore_show_all_locations, count),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF191C1F),
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(id = R.drawable.ic_show_all),
            contentDescription = null,
            tint = MyTheme.Colors.textTertiary
        )
    }
}

@Composable
private fun AtmDetailsContent(
    atm: Atm,
    onSendDashClicked: (Boolean) -> Unit,
    onReceiveDashClicked: () -> Unit,
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // ATM header with buy/sell buttons
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MyTheme.Colors.backgroundSecondary)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Logo and manufacturer
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = atm.logoLocation ?: "",
                        contentDescription = atm.manufacturer,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = R.drawable.ic_image_placeholder),
                        error = painterResource(id = R.drawable.ic_image_placeholder)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = atm.manufacturer?.replaceFirstChar { it.uppercase() } ?: "",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF191C1F)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buy/Sell buttons
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (atm.type != AtmType.SELL) {
                        Button(
                            onClick = onReceiveDashClicked,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MyTheme.Colors.green
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.explore_buy_dash),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    if (atm.type != AtmType.SELL && atm.type != AtmType.BUY && !atm.type.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.width(7.dp))
                    }

                    if (atm.type != AtmType.BUY && !atm.type.isNullOrEmpty()) {
                        Button(
                            onClick = { onSendDashClicked(false) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF008DE4)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.explore_sell_dash),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Location hint
        Text(
            text = stringResource(R.string.explore_atm_location_hint),
            style = MyTheme.Overline,
            color = MyTheme.Colors.textSecondary,
            modifier = Modifier.padding(start = 12.dp, bottom = 5.dp)
        )

        // ATM Name
        if (!atm.name.isNullOrEmpty()) {
            Text(
                text = atm.name!!,
                style = MyTheme.SubtitleSemibold,
                color = MyTheme.Colors.textPrimary,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp)
            )
        }

        // ATM details
        val hasAtmDetails = atm.getDisplayAddress("\n").isNotEmpty() ||
            !atm.phone.isNullOrEmpty() ||
            !atm.website.isNullOrEmpty()

        if (hasAtmDetails) {
            ItemContactDetails(
                item = atm,
                isOnline = false,
                isGrouped = false,
                onNavigationButtonClicked = onNavigationButtonClicked,
                onDialPhoneButtonClicked = onDialPhoneButtonClicked,
                onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked
            )
        }
    }
}

// Helper functions
@Composable
private fun getMerchantTypeText(type: String?, isOnline: Boolean): String {
    return when (cleanMerchantTypeValue(type)) {
        MerchantType.ONLINE -> stringResource(R.string.explore_online_merchant)
        MerchantType.PHYSICAL -> stringResource(R.string.explore_physical_merchant)
        MerchantType.BOTH -> stringResource(R.string.explore_both_types_merchant)
        else -> ""
    }
}

@Composable
private fun getDistanceText(item: SearchResult): String {
    val context = LocalContext.current
    val isMetric = Locale.getDefault().isMetric
    val distanceStr = item.getDistanceStr(isMetric)

    return when {
        distanceStr.isEmpty() -> ""
        isMetric -> context.getString(R.string.distance_kilometers, distanceStr)
        else -> context.getString(R.string.distance_miles, distanceStr)
    }
}

private fun cleanMerchantTypeValue(value: String?): String? {
    return value?.trim()?.lowercase()?.replace(" ", "_")
}

private fun SearchResult.hasCoordinates(): Boolean {
    return latitude != null && longitude != null
}

private fun String.maskEmail(): String {
    val parts = split("@")
    return if (parts.size == 2) {
        val username = parts[0]
        val domain = parts[1]
        val masked = if (username.length > 2) {
            username.first() + "*".repeat(username.length - 2) + username.last()
        } else {
            username
        }
        "$masked@$domain"
    } else {
        this
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewMerchantDetails() {
    val ctxProvider = GiftCardProvider(
        merchantId = UUID.randomUUID().toString(),
        provider = "CTX",
        redeemType = "gift card",
        savingsPercentage = 1000,
        active = true,
        denominationsType = "fixed",
        sourceId = "123"
    )
    val piggyCardsProvider = GiftCardProvider(
        merchantId = UUID.randomUUID().toString(),
        provider = "PiggyCards",
        redeemType = "gift card",
        savingsPercentage = 900,
        active = false,
        denominationsType = "fixed",
        sourceId = "124"
    )
    val merchant = Merchant().apply {
        name = "Burger King"
        type = MerchantType.PHYSICAL
        logoLocation = ""
        paymentMethod = PaymentMethod.GIFT_CARD
        source = ServiceName.CTXSpend
        active = true
        phone = "+1234567890"
        website = "www.burgerking.com"
        latitude = 0.0
        longitude = 0.0
        physicalAmount = 10
        giftCardProviders = listOf(
            ctxProvider,
            piggyCardsProvider
        )
    }

    ItemDetails(
        item = merchant,
        isLoggedIn = true,
        userEmail = "user@example.com",
        selectedProvider = GiftCardProviderType.CTX
    )
}
