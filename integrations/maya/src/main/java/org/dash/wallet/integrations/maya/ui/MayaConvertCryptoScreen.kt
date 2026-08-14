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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.EnterAmount
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integrations.maya.R
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * UI state for the Maya "Convert Dash to <crypto>" enter-amount screen (Figma node 24021:10970).
 *
 * All amount strings arrive pre-formatted by the host fragment, which keeps the original
 * view-based formatting logic (ConvertViewViewModel formats + GenericUtils helpers).
 */
data class MayaConvertCryptoUIState(
    val title: String = "",
    /** Amount as typed / formatted in the currently picked currency. */
    val displayAmount: String = "0",
    /** Picker options in anchoring order: DASH, fiat, destination crypto. */
    val currencyOptions: List<String> = emptyList(),
    val selectedCurrencyIndex: Int = 0,
    /** Formatted DASH balance of this wallet, e.g. "0.05". */
    val dashBalance: String = "0",
    /** Formatted fiat equivalent of the balance, e.g. "0.00 US$". */
    val fiatBalance: String? = null,
    /** Display name of the destination coin, e.g. "Bitcoin". */
    val toCurrencyName: String = "",
    /** Destination address the converted funds are sent to. */
    val toAddress: String = "",
    /** Candidate icon URLs for the destination coin (tried in order). */
    val toIconUrls: List<String> = emptyList(),
    /** Pre-formatted receive amount line, e.g. "≈ 0.0053 BTC"; null hides the block. */
    val receiveAmount: String? = null,
    /** Pre-formatted route line, e.g. "using NEAR network"; null hides it. */
    val networkLabel: String? = null,
    /** Inline validation / swap error shown under the amount; null hides it. */
    val errorMessage: String? = null,
    /** True while a quote request is in flight — blocks all input. */
    val isProcessing: Boolean = false,
    val isOnline: Boolean = true,
    /** True when the entered amount is non-zero and input isn't hard-disabled. */
    val continueEnabled: Boolean = false
)

@Composable
fun MayaConvertCryptoScreen(
    state: MayaConvertCryptoUIState,
    onBackClick: () -> Unit,
    onMaxClick: () -> Unit,
    onCurrencySelected: (Int) -> Unit,
    onKeyInput: (String) -> Unit,
    onContinueClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavBarBackTitle(
                title = state.title,
                onBackClick = onBackClick
            )

            // Horizontal insets are applied per-child (not on this column) because the direction
            // card's Menu component supplies its own 20dp horizontal padding.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Amount input bar (design-system EnterAmount): Max button on the left, the typed
                // amount in the middle and the DASH / fiat / crypto picker on the right.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EnterAmount(
                        primaryAmount = state.displayAmount,
                        currencyCodes = state.currencyOptions,
                        selectedCurrencyIndex = state.selectedCurrencyIndex,
                        locale = Locale.getDefault(),
                        showMaxButton = true,
                        showBalanceButton = false,
                        showPrimaryChevron = false,
                        showSecondary = false,
                        showCurrencyPicker = true,
                        onMaxClick = onMaxClick,
                        onCurrencyPickerSelect = { _, index -> onCurrencySelected(index) }
                    )

                    // Min/max/balance errors that were shown in the old full-width red banner
                    // are surfaced inline under the amount, like the DEX enter-amount screen.
                    state.errorMessage?.let { error ->
                        Text(
                            text = error,
                            style = MyTheme.Body2Regular,
                            color = LocalDashColors.current.red,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                ConvertDirectionCard(
                    dashBalance = state.dashBalance,
                    fiatBalance = state.fiatBalance,
                    toCurrencyName = state.toCurrencyName,
                    toAddress = state.toAddress,
                    toIconUrls = state.toIconUrls
                )

                // "Receive amount / ~ 0.0053 BTC / using NEAR network" block; only visible once
                // a non-zero amount has been entered.
                state.receiveAmount?.let { receiveAmount ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.you_will_receive),
                            style = MyTheme.OverlineCaptionRegular,
                            color = LocalDashColors.current.textTertiary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = receiveAmount,
                            style = MyTheme.Body2Medium,
                            color = LocalDashColors.current.textPrimary,
                            textAlign = TextAlign.Center
                        )
                        state.networkLabel?.let { network ->
                            Text(
                                text = network,
                                style = MyTheme.OverlineCaptionRegular,
                                color = LocalDashColors.current.textTertiary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Numeric keypad with the Get quote button in its bottom slot, flush to the bottom.
            NumericKeyboardCompose(
                modifier = Modifier.fillMaxWidth(),
                // Inert (dimmed) while offline — a quote can't be fetched without a connection —
                // and while a quote is in flight, so the submitted amount can't drift.
                enabled = state.isOnline && !state.isProcessing,
                // Matches the separator the fragment inserts for the "." key (device locale).
                decimalSeparator = remember {
                    DecimalFormatSymbols.getInstance(GenericUtils.getDeviceLocale()).decimalSeparator
                },
                onKeyInput = onKeyInput,
                bottomSlot = {
                    DashButton(
                        text = stringResource(R.string.get_quote),
                        style = Style.FilledBlue,
                        size = Size.Large,
                        isEnabled = state.continueEnabled && state.isOnline && !state.isProcessing,
                        isLoading = state.isProcessing,
                        onClick = onContinueClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, start = 20.dp, end = 20.dp)
                    )
                }
            )
        }

        // No-connection toast, pinned at the bottom; stays visible while the device is offline
        // since the whole screen is disabled until connectivity returns.
        if (!state.isOnline) {
            Toast(
                text = stringResource(R.string.maya_no_connection_toast),
                imageResource = ToastImageResource.NoInternet.resourceId,
                onActionClick = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 25.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * The conversion direction: the Dash wallet (with its balance) in its own white card, then the
 * destination coin + address in a second card 5dp below it, with a direction (arrow-down) badge
 * floating over the seam between the two. Matches Figma node 38680:47497 — two separate [Menu]
 * cards, not one card with an internal divider.
 */
@Composable
private fun ConvertDirectionCard(
    dashBalance: String,
    fiatBalance: String?,
    toCurrencyName: String,
    toAddress: String,
    toIconUrls: List<String>
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // From: Dash Wallet with its balance.
            Menu {
                MenuItem(
                    title = stringResource(R.string.dash),
                    subtitle = stringResource(R.string.dash_wallet_name),
                    icon = R.drawable.ic_dash_blue_filled,
                    dashAmount = dashBalance,
                    dashIcon = R.drawable.ic_dash_d_black,
                    fiatAmount = fiatBalance
                )
            }

            // To: destination coin + address, truncated from the center so both ends stay
            // checkable on one line (the common way of displaying a crypto address).
            Menu {
                MenuItem(
                    title = toCurrencyName,
                    subtitle = toAddress,
                    subtitleMaxLines = 1,
                    subtitleMiddleEllipsis = true,
                    customIcon = { CoinIcon(iconUrls = toIconUrls) }
                )
            }
        }

        // Direction badge: a white rounded-square button ringed by a screen-background-colored
        // border, so it reads as inset into the gap between the two cards.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp)
                .background(LocalDashColors.current.backgroundSecondary, RoundedCornerShape(10.dp))
                .border(5.dp, LocalDashColors.current.backgroundPrimary, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_downward_blue_24dp),
                contentDescription = null,
                // The drawable's own path fill is already the design's blue; tinting it would
                // override that with an unrelated color.
                tint = Color.Unspecified,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * Coin icon that tries each candidate URL in [iconUrls] in order, advancing to the next source
 * whenever one fails to load; falls back to the neutral coin placeholder.
 */
@Composable
private fun CoinIcon(iconUrls: List<String>) {
    var index by remember(iconUrls) { mutableIntStateOf(0) }
    val placeholder = painterResource(R.drawable.ic_coin_placeholder)
    val isLast = index >= iconUrls.lastIndex
    AsyncImage(
        model = iconUrls.getOrNull(index),
        contentDescription = null,
        placeholder = placeholder,
        error = if (isLast) placeholder else null,
        fallback = placeholder,
        onError = { if (!isLast) index++ },
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
    )
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 850, fontScale = 1.25f)
@Composable
private fun MayaConvertCryptoScreenPreview() {
    MayaConvertCryptoScreen(
        state = MayaConvertCryptoUIState(
            title = "Convert",
            displayAmount = "0.06",
            currencyOptions = listOf("DASH", "USD", "BTC"),
            selectedCurrencyIndex = 2,
            dashBalance = "4.00",
            fiatBalance = "$140.00",
            toCurrencyName = "Bitcoin",
            toAddress = "XbBzWvnvSyWFbYXFtjkWwuPApbfDD263uC",
            receiveAmount = "~ 0.0053 BTC",
            networkLabel = "using NEAR network",
            continueEnabled = true
        ),
        onBackClick = {},
        onMaxClick = {},
        onCurrencySelected = {},
        onKeyInput = {},
        onContinueClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 850)
@Composable
private fun MayaConvertCryptoScreenErrorPreview() {
    MayaConvertCryptoScreen(
        state = MayaConvertCryptoUIState(
            title = "Convert",
            displayAmount = "0.5",
            currencyOptions = listOf("DASH", "USD", "BTC"),
            selectedCurrencyIndex = 0,
            dashBalance = "0.05",
            fiatBalance = "1.20 US$",
            toCurrencyName = "Bitcoin",
            toAddress = "XbBzWvnvSyWFbYXFtjkWwuPApbfDD263uC",
            errorMessage = "Max $1.20",
            continueEnabled = false
        ),
        onBackClick = {},
        onMaxClick = {},
        onCurrencySelected = {},
        onKeyInput = {},
        onContinueClick = {}
    )
}
