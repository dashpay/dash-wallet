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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.util.maskEmail
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.dashspend.GiftCardProvider
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import java.util.*

@Composable
fun ItemDetailsContent(
    item: SearchResult,
    isLoggedIn: Boolean = false,
    userEmail: String? = null,
    selectedProvider: GiftCardProvider? = null,
    onProviderSelected: (GiftCardProvider?) -> Unit = {},
    onSendDashClicked: (Boolean) -> Unit = {},
    onReceiveDashClicked: () -> Unit = {},
    onShowAllLocationsClicked: () -> Unit = {},
    onBackButtonClicked: () -> Unit = {},
    onNavigationButtonClicked: () -> Unit = {},
    onDialPhoneButtonClicked: () -> Unit = {},
    onOpenWebsiteButtonClicked: () -> Unit = {},
    onBuyGiftCardButtonClicked: (GiftCardProvider) -> Unit = {},
    onExploreLogOutClicked: (GiftCardProvider) -> Unit = {},
    modifier: Modifier = Modifier
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
    selectedProvider: GiftCardProvider?,
    onProviderSelected: (GiftCardProvider?) -> Unit,
    onSendDashClicked: (Boolean) -> Unit,
    onShowAllLocationsClicked: () -> Unit,
    onBackButtonClicked: () -> Unit,
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit,
    onBuyGiftCardButtonClicked: (GiftCardProvider) -> Unit,
    onExploreLogOutClicked: (GiftCardProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOnline = merchant.type == MerchantType.ONLINE
    val isGrouped = merchant.physicalAmount > 0
    val isDash = merchant.paymentMethod?.trim()?.lowercase() == PaymentMethod.DASH
    val hasMultipleProviders = merchant.source?.lowercase() == ServiceName.CTXSpend.lowercase()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isOnline) {
                    Modifier
                        .fillMaxHeight()
                        .background(Color(0xFFF5F6F7))
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                } else {
                    Modifier.padding(horizontal = 16.dp)
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
            colors = CardDefaults.cardColors(containerColor = Color.White)
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

                // Multiple providers selection or single action button
                if (hasMultipleProviders && !isDash) {
                    MultipleProvidersSection(
                        selectedProvider = selectedProvider,
                        onProviderSelected = onProviderSelected
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Action button
                ActionButton(
                    merchant = merchant,
                    isDash = isDash,
                    selectedProvider = selectedProvider,
                    onSendDashClicked = onSendDashClicked,
                    onBuyGiftCardButtonClicked = onBuyGiftCardButtonClicked
                )

                // User login status
                if (isLoggedIn && userEmail != null && !isDash) {
                    Spacer(modifier = Modifier.height(16.dp))
                    UserLoginStatus(
                        email = userEmail,
                        selectedProvider = selectedProvider ?: GiftCardProvider.CTX,
                        onLogOutClicked = onExploreLogOutClicked
                    )
                }
            }
        }

        // Merchant details (address, phone, website)
        if (!isOnline) {
            Spacer(modifier = Modifier.height(16.dp))
            MerchantDetails(
                merchant = merchant,
                isGrouped = isGrouped,
                onShowAllLocationsClicked = onShowAllLocationsClicked,
                onNavigationButtonClicked = onNavigationButtonClicked,
                onDialPhoneButtonClicked = onDialPhoneButtonClicked,
                onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked
            )
        } else {
            // Online merchant contact details
            Spacer(modifier = Modifier.height(16.dp))
            OnlineMerchantDetails(
                merchant = merchant,
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
        // Logo
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
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF191C1F)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = getMerchantTypeText(merchant.type, isOnline),
                fontSize = 13.sp,
                color = Color(0xFF525C66)
            )
        }
    }
}

@Composable
private fun MultipleProvidersSection(
    selectedProvider: GiftCardProvider?,
    onProviderSelected: (GiftCardProvider?) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.select_gift_card_provider),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF525C66),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F6F7))
        ) {
            Column {
                ProviderOption(
                    provider = GiftCardProvider.CTX,
                    providerName = "CTX",
                    subtitle = stringResource(R.string.flexible_amounts),
                    discount = "-5%",
                    isSelected = selectedProvider == GiftCardProvider.CTX,
                    onSelected = { onProviderSelected(GiftCardProvider.CTX) }
                )
                
                HorizontalDivider(color = Color(0xFFE0E0E0))
                
                ProviderOption(
                    provider = GiftCardProvider.PiggyCards,
                    providerName = "PiggyCards",
                    subtitle = stringResource(R.string.fixed_prices),
                    discount = "-10%",
                    isSelected = selectedProvider == GiftCardProvider.PiggyCards,
                    onSelected = { onProviderSelected(GiftCardProvider.PiggyCards) }
                )
            }
        }
    }
}

@Composable
private fun ProviderOption(
    provider: GiftCardProvider,
    providerName: String,
    subtitle: String,
    discount: String,
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    val backgroundColor = if (isSelected) Color(0x0D008DE4) else Color.Transparent
    val borderColor = if (isSelected) Color(0xFF008DE4) else Color(0x33B0B6BC)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onSelected() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = providerName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF191C1F)
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF75808A)
            )
        }

        Text(
            text = discount,
            fontSize = 13.sp,
            color = Color(0xFF191C1F),
            modifier = Modifier.padding(end = 10.dp)
        )

        RadioButton(
            selected = isSelected,
            onClick = onSelected,
            colors = RadioButtonDefaults.colors(
                selectedColor = Color(0xFF008DE4),
                unselectedColor = Color(0xFFB0B6BC)
            )
        )
    }
}

@Composable
private fun ActionButton(
    merchant: Merchant,
    isDash: Boolean,
    selectedProvider: GiftCardProvider?,
    onSendDashClicked: (Boolean) -> Unit,
    onBuyGiftCardButtonClicked: (GiftCardProvider) -> Unit
) {
    val isEnabled = merchant.active ?: true
    
    Column {
        if (!isEnabled) {
            Text(
                text = stringResource(R.string.temporarily_unavailable),
                fontSize = 12.sp,
                color = Color(0xFF75808A),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = {
                if (isDash) {
                    onSendDashClicked(true)
                } else {
                    onBuyGiftCardButtonClicked(selectedProvider ?: GiftCardProvider.CTX)
                }
            },
            enabled = isEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDash) Color(0xFF008DE4) else Color(0xFFFA9269),
                disabledContainerColor = Color(0xFFE0E0E0)
            )
        ) {
            Icon(
                painter = painterResource(
                    id = if (isDash) R.drawable.ic_dash_inverted else R.drawable.ic_gift_card_inverted
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (isDash) R.string.explore_pay_with_dash else R.string.explore_buy_gift_card
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        // Discount badge for Dash payments
        if (isDash && merchant.savingsFraction != 0.0) {
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .offset(y = (-24).dp)
                    .background(
                        color = Color(0xFF191C1F),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.explore_pay_with_dash_save,
                        merchant.savingsPercentageAsDouble
                    ),
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun UserLoginStatus(
    email: String,
    selectedProvider: GiftCardProvider,
    onLogOutClicked: (GiftCardProvider) -> Unit
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
                onLogOutClicked(selectedProvider)
            }
    )
}

@Composable
private fun MerchantDetails(
    merchant: Merchant,
    isGrouped: Boolean,
    onShowAllLocationsClicked: () -> Unit,
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Address
            if (!merchant.getDisplayAddress("\n").isNullOrEmpty()) {
                DetailItem(
                    title = stringResource(R.string.address),
                    content = merchant.getDisplayAddress("\n") ?: "",
                    subtitle = getDistanceText(merchant),
                    hasAction = merchant.hasCoordinates() || !merchant.googleMaps.isNullOrBlank(),
                    actionIcon = R.drawable.ic_direction,
                    onActionClick = onNavigationButtonClicked
                )
            }

            // Phone
            if (!merchant.phone.isNullOrEmpty()) {
                DetailItem(
                    title = stringResource(R.string.phone),
                    content = merchant.phone!!,
                    isClickable = true,
                    onContentClick = onDialPhoneButtonClicked
                )
            }

            // Website
            if (!merchant.website.isNullOrEmpty()) {
                DetailItem(
                    title = stringResource(R.string.website),
                    content = merchant.website!!,
                    isClickable = true,
                    onContentClick = onOpenWebsiteButtonClicked
                )
            }

            // Show all locations
            if (isGrouped && merchant.physicalAmount > 1) {
                HorizontalDivider(color = Color(0xFFE0E0E0), modifier = Modifier.padding(vertical = 6.dp))
                ShowAllLocationsItem(
                    count = merchant.physicalAmount,
                    onClick = onShowAllLocationsClicked
                )
            }
        }
    }
}

@Composable
private fun OnlineMerchantDetails(
    merchant: Merchant,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            // Phone
            if (!merchant.phone.isNullOrEmpty()) {
                DetailItem(
                    title = stringResource(R.string.phone),
                    content = merchant.phone!!,
                    isClickable = true,
                    onContentClick = onDialPhoneButtonClicked
                )
            }

            // Website
            if (!merchant.website.isNullOrEmpty()) {
                DetailItem(
                    title = stringResource(R.string.website),
                    content = merchant.website!!,
                    isClickable = true,
                    onContentClick = onOpenWebsiteButtonClicked
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
        verticalAlignment = Alignment.CenterVertically
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
                fontSize = 12.sp,
                color = Color(0xFF75808A)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = content,
                fontSize = 14.sp,
                color = if (isClickable) Color(0xFF008DE4) else Color(0xFF191C1F)
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF75808A)
                )
            }
        }

        if (hasAction && actionIcon != null && onActionClick != null) {
            IconButton(
                onClick = onActionClick,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    painter = painterResource(id = actionIcon),
                    contentDescription = null,
                    tint = Color(0xFF008DE4)
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
            .padding(horizontal = 10.dp, vertical = 12.dp),
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
            tint = Color(0xFFB0B6BC),
            modifier = Modifier.size(16.dp)
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
            colors = CardDefaults.cardColors(containerColor = Color.White)
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
                                containerColor = Color(0xFF5DB968)
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
            fontSize = 12.sp,
            color = Color(0xFF75808A),
            modifier = Modifier.padding(bottom = 5.dp)
        )

        // ATM image
        AsyncImage(
            model = atm.coverImage ?: "",
            contentDescription = atm.name,
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(id = R.drawable.ic_image_placeholder),
            error = painterResource(id = R.drawable.ic_image_placeholder)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ATM details
        AtmDetails(
            atm = atm,
            onNavigationButtonClicked = onNavigationButtonClicked,
            onDialPhoneButtonClicked = onDialPhoneButtonClicked,
            onOpenWebsiteButtonClicked = onOpenWebsiteButtonClicked
        )
    }
}

@Composable
private fun AtmDetails(
    atm: Atm,
    onNavigationButtonClicked: () -> Unit,
    onDialPhoneButtonClicked: () -> Unit,
    onOpenWebsiteButtonClicked: () -> Unit
) {
    Column {
        // Name
        Text(
            text = atm.name ?: "",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF191C1F)
        )

        // Address
        if (!atm.getDisplayAddress("\n").isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = atm.getDisplayAddress("\n") ?: "",
                fontSize = 14.sp,
                color = Color(0xFF191C1F),
                lineHeight = 20.sp
            )
        }

        // Distance and actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Distance
            val distanceText = getDistanceText(atm)
            if (distanceText.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_distance),
                        contentDescription = null,
                        tint = Color(0xFF75808A),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = distanceText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF75808A)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            Row {
                if (!atm.phone.isNullOrEmpty()) {
                    IconButton(onClick = onDialPhoneButtonClicked) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_call),
                            contentDescription = stringResource(R.string.call),
                            tint = Color(0xFF191C1F)
                        )
                    }
                }

                if (atm.hasCoordinates() || !atm.googleMaps.isNullOrBlank()) {
                    IconButton(onClick = onNavigationButtonClicked) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_direction),
                            contentDescription = stringResource(R.string.directions),
                            tint = Color(0xFF191C1F)
                        )
                    }
                }

                if (!atm.website.isNullOrEmpty()) {
                    IconButton(onClick = onOpenWebsiteButtonClicked) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_link),
                            contentDescription = stringResource(R.string.website),
                            tint = Color(0xFF191C1F)
                        )
                    }
                }
            }
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
    }

    ItemDetailsContent(
        item = merchant,
        isLoggedIn = true,
        userEmail = "user@example.com",
        selectedProvider = GiftCardProvider.PiggyCards
    )
}