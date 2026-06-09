/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

// Mirrors the common DashList card styling (its shape/shadow tokens are private).
// Replicated here so the coin list can be a lazy LazyColumn while keeping the look.
private val CardShape = RoundedCornerShape(20.dp)
private val CardShadowColor = Color(0xFFB8C1CC).copy(alpha = 0.10f)

// Figma colors/gray/gray400/gray400alpha10 — the search field background.
private val SearchFieldBackground = Color(0x1A75808A)

@Composable
fun MayaCryptoCurrencyPickerScreen(
    viewModel: MayaViewModel,
    onBackClick: () -> Unit,
    onCoinClick: (String) -> Unit,
    onShowError: (Int) -> Unit
) {
    val uiState by viewModel.currencyPickerUIState.collectAsStateWithLifecycle()
    val portalState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasHaltedCoins by viewModel.hasHaltedCoins.collectAsStateWithLifecycle()

    LaunchedEffect(portalState.errorCode) {
        portalState.errorCode?.let(onShowError)
    }

    MayaCryptoCurrencyPickerScreenContent(
        items = uiState.coins,
        isLoading = uiState.isLoading,
        hasHaltedCoins = hasHaltedCoins,
        searchQuery = uiState.searchQuery,
        onSearchChange = viewModel::onSearchQuery,
        onCoinClick = onCoinClick,
        onBackClick = onBackClick
    )
}

@Composable
private fun MayaCryptoCurrencyPickerScreenContent(
    items: List<CoinPickerItem>,
    isLoading: Boolean,
    hasHaltedCoins: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCoinClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var dismissed by remember { mutableStateOf(false) }

    // Filter here (not in the ViewModel) so we can match the localized coin name in
    // addition to the code/asset, matching the legacy fragment. stringResource is only
    // available in composition; the list is small so per-recomposition filtering is fine.
    val query = searchQuery.trim().uppercase()
    val displayItems: List<CoinPickerItem> = if (query.isEmpty()) {
        items
    } else {
        val matches = mutableListOf<CoinPickerItem>()
        for (coin in items) {
            val name = if (coin.nameId != 0) stringResource(coin.nameId) else coin.currencyCode
            val code = if (coin.codeId != 0) stringResource(coin.codeId) else coin.asset
            if (name.uppercase().contains(query) ||
                code.uppercase().contains(query) ||
                coin.asset.uppercase().contains(query)
            ) {
                matches.add(coin)
            }
        }
        matches
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavBarBackTitle(
                title = stringResource(R.string.maya_select_coin_title),
                onBackClick = onBackClick
            )

            SearchField(
                query = searchQuery,
                onQueryChange = onSearchChange,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    CircularProgressIndicator(
                        color = MyTheme.Colors.dashBlue,
                        strokeWidth = 3.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
            } else {
                // Lazy list inside the rounded white "DashList" card. Each coin is its
                // own LazyColumn item (rather than one item wrapping a forEach) so only
                // visible rows compose on the first frame — this is what lets the screen
                // appear immediately instead of stalling on the full list. fill = false
                // makes the card wrap its content for short/filtered lists and cap at the
                // available height (scrolling) when long.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 20.dp)
                        .shadow(
                            elevation = 5.dp,
                            shape = CardShape,
                            ambientColor = CardShadowColor,
                            spotColor = CardShadowColor
                        )
                        .clip(CardShape)
                        .background(MyTheme.Colors.backgroundSecondary)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    items(displayItems, key = { it.asset }) { coin ->
                        CoinRow(item = coin, onCoinClick = onCoinClick)
                    }
                }
            }
        }

        if (hasHaltedCoins && !dismissed) {
            Toast(
                text = stringResource(R.string.maya_halted_coins_toast),
                actionText = stringResource(android.R.string.ok),
                imageResource = ToastImageResource.Warning.resourceId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 5.dp, vertical = 8.dp)
            ) {
                dismissed = true
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp)),
        placeholder = {
            Text(
                text = stringResource(CommonR.string.search_hint),
                style = MyTheme.Body2Regular,
                color = MyTheme.Colors.textTertiary
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(CommonR.drawable.ic_search),
                contentDescription = null,
                tint = MyTheme.Colors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        },
        singleLine = true,
        textStyle = MyTheme.Body2Regular,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = SearchFieldBackground,
            unfocusedContainerColor = SearchFieldBackground,
            disabledContainerColor = SearchFieldBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedTextColor = MyTheme.Colors.textPrimary,
            unfocusedTextColor = MyTheme.Colors.textPrimary
        )
    )
}

@Composable
private fun CoinRow(
    item: CoinPickerItem,
    onCoinClick: (String) -> Unit
) {
    ListItem(
        modifier = Modifier.alpha(if (item.isEnabled) 1f else 0.5f),
        leadingContent = {
            AsyncImage(
                model = item.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
            )
        },
        title = if (item.nameId != 0) stringResource(item.nameId) else item.currencyCode,
        subtitle = if (item.codeId != 0) stringResource(item.codeId) else item.asset,
        trailingContent = {
            if (item.isHalted) {
                Text(
                    text = stringResource(R.string.maya_halted_label),
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colorResource(CommonR.color.gray_100))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = item.price ?: "",
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textPrimary
                    )
                    // Single-provider assets show the network statically; both-provider
                    // assets show "Multiple networks" until the background quote resolves
                    // a preferred network, then show it with a trailing "*" to mark it as
                    // calculated.
                    item.routeLabelId?.let { labelId ->
                        val label = stringResource(labelId) + if (item.routeCalculated) "*" else ""
                        Text(
                            text = label,
                            style = MyTheme.Typography.BodySmall,
                            color = MyTheme.Colors.textTertiary
                        )
                    }
                }
            }
        },
        onClick = if (item.isEnabled) {
            { onCoinClick(item.asset) }
        } else {
            null
        }
    )
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaCryptoCurrencyPickerScreenPreview() {
    val sample = listOf(
        CoinPickerItem(
            asset = "BTC.BTC",
            currencyCode = "BTC",
            nameId = 0,
            codeId = 0,
            iconUrl = "",
            price = "$64,000.00",
            // Single-provider (Maya-only) → static label, no asterisk.
            routeLabelId = R.string.maya_route_label_maya,
            routeCalculated = false,
            isHalted = false,
            isEnabled = true
        ),
        CoinPickerItem(
            asset = "NEAR.NEAR",
            currencyCode = "NEAR",
            nameId = 0,
            codeId = 0,
            iconUrl = "",
            price = "$5.20",
            routeLabelId = R.string.maya_route_label_near,
            routeCalculated = false,
            isHalted = false,
            isEnabled = true
        ),
        CoinPickerItem(
            asset = "ETH.ETH",
            currencyCode = "ETH",
            nameId = 0,
            codeId = 0,
            iconUrl = "",
            price = "$3,100.00",
            // Both providers, preferred network resolved by quote → "Maya*".
            routeLabelId = R.string.maya_route_label_maya,
            routeCalculated = true,
            isHalted = false,
            isEnabled = true
        ),
        CoinPickerItem(
            asset = "UNI.UNI",
            currencyCode = "UNI",
            nameId = 0,
            codeId = 0,
            iconUrl = "",
            price = "$8.40",
            // Both providers, still resolving → "Multiple networks".
            routeLabelId = R.string.maya_route_label_multiple,
            routeCalculated = false,
            isHalted = false,
            isEnabled = true
        ),
        CoinPickerItem(
            asset = "USDT.USDT",
            currencyCode = "USDT",
            nameId = 0,
            codeId = 0,
            iconUrl = "",
            price = null,
            routeLabelId = R.string.maya_route_label_maya,
            routeCalculated = false,
            isHalted = true,
            isEnabled = false
        )
    )
    MayaCryptoCurrencyPickerScreenContent(
        items = sample,
        isLoading = false,
        hasHaltedCoins = true,
        searchQuery = "",
        onSearchChange = {},
        onCoinClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaCryptoCurrencyPickerScreenLoadingPreview() {
    MayaCryptoCurrencyPickerScreenContent(
        items = emptyList(),
        isLoading = true,
        hasHaltedCoins = false,
        searchQuery = "",
        onSearchChange = {},
        onCoinClick = {},
        onBackClick = {}
    )
}
