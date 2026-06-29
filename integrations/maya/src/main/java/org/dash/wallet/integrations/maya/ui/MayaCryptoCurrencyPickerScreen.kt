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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.CoinSelect
import org.dash.wallet.common.ui.components.CoinSelectState
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.SearchField
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.integrations.maya.R

// Mirrors the common DashList card styling (its shape/shadow tokens are private).
// Replicated here so the coin list can be a lazy LazyColumn while keeping the look.
private val CardShape = RoundedCornerShape(20.dp)
private val CardShadowColor = Color(0xFFB8C1CC).copy(alpha = 0.10f)

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
        isOnline = uiState.isOnline,
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
    isOnline: Boolean,
    hasHaltedCoins: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCoinClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var haltedDismissed by remember { mutableStateOf(false) }
    var networkDismissed by remember { mutableStateOf(false) }

    // Offline: hide the search bar and show the "no connection" Toast at the bottom
    // (per design). With no cached coins the list area shows a centered "No available
    // coins" message; with a cache it shows the list with every row disabled.
    val showSearch = isOnline

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

            if (showSearch) {
                SearchField(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }

            when {
                isLoading -> {
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
                }

                displayItems.isEmpty() -> {
                    // Empty list area. Check the rendered list (displayItems), not the full
                    // dataset, so this also covers a search that filters every coin out.
                    // Show a search-specific message when filtering is the cause, otherwise
                    // the generic "No available coins" (offline with no cache / empty list).
                    val emptyMessage = if (query.isEmpty()) {
                        R.string.maya_no_available_coins
                    } else {
                        R.string.maya_no_search_results
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Text(
                            text = stringResource(emptyMessage),
                            style = MyTheme.Typography.TitleSmall,
                            color = MyTheme.Colors.textSecondary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                else -> {
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
        }

        // No-connection toast, pinned at the bottom (per design): no-wifi icon, message,
        // and a dismiss button. Shown whenever offline, over both the empty and list states.
        if (!isOnline && !networkDismissed) {
            Toast(
                text = stringResource(R.string.maya_no_connection_toast),
                imageResource = ToastImageResource.NoInternet.resourceId,
                showDismissButton = true,
                onDismiss = { networkDismissed = true },
                onActionClick = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 25.dp, vertical = 8.dp)
            )
        }

        // The halted-coins toast is about per-asset trading halts, not connectivity;
        // suppress it while offline so it doesn't compete with the network toast.
        if (hasHaltedCoins && !haltedDismissed && isOnline) {
            Toast(
                text = stringResource(R.string.maya_halted_coins_toast),
                actionText = stringResource(android.R.string.ok),
                imageResource = ToastImageResource.Warning.resourceId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 5.dp, vertical = 8.dp)
            ) {
                haltedDismissed = true
            }
        }
    }
}

@Composable
private fun CoinRow(
    item: CoinPickerItem,
    onCoinClick: (String) -> Unit
) {
    val state = when {
        item.isHalted -> CoinSelectState.HaltedChain
        !item.isEnabled -> CoinSelectState.Disabled
        else -> CoinSelectState.Active
    }
    // Single-provider assets show the network statically; both-provider assets show
    // "Multiple networks" until the background quote resolves a preferred network, then
    // show it with a trailing "*" to mark it as calculated.
    val network = item.routeLabelId?.let { labelId ->
        stringResource(labelId) + if (item.routeCalculated) "*" else ""
    }

    CoinSelect(
        name = if (item.nameId != 0) stringResource(item.nameId) else item.currencyCode,
        symbol = if (item.codeId != 0) stringResource(item.codeId) else item.asset,
        coinIcon = { CoinIcon(item.iconUrls) },
        state = state,
        price = item.price,
        network = network,
        haltedLabel = stringResource(R.string.maya_halted_label),
        onClick = { onCoinClick(item.asset) }
    )
}

/**
 * Coin icon that tries each candidate URL in [iconUrls] in order, advancing to the
 * next source whenever one fails to load. The neutral coin placeholder is shown while
 * loading and as the final fallback once every source has failed (or when there are
 * no candidates).
 */
@Composable
private fun CoinIcon(iconUrls: List<String>) {
    var index by remember(iconUrls) { mutableStateOf(0) }
    val placeholder = painterResource(R.drawable.ic_coin_placeholder)
    val isLast = index >= iconUrls.lastIndex
    AsyncImage(
        model = iconUrls.getOrNull(index),
        contentDescription = null,
        placeholder = placeholder,
        // Only surface the placeholder on error once the last source has failed;
        // intermediate failures advance to the next URL instead.
        error = if (isLast) placeholder else null,
        fallback = placeholder,
        onError = { if (!isLast) index++ },
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
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
            iconUrls = emptyList(),
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
            iconUrls = emptyList(),
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
            iconUrls = emptyList(),
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
            iconUrls = emptyList(),
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
            iconUrls = emptyList(),
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
        isOnline = true,
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
        isOnline = true,
        hasHaltedCoins = false,
        searchQuery = "",
        onSearchChange = {},
        onCoinClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaCryptoCurrencyPickerScreenOfflinePreview() {
    // Offline: no search bar, "No available coins" empty state, and the no-connection toast.
    MayaCryptoCurrencyPickerScreenContent(
        items = emptyList(),
        isLoading = false,
        isOnline = false,
        hasHaltedCoins = false,
        searchQuery = "",
        onSearchChange = {},
        onCoinClick = {},
        onBackClick = {}
    )
}
