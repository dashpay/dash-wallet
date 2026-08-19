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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.dash.wallet.common.ui.components.ActionItem
import org.dash.wallet.common.ui.components.DarkPreviewTheme
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBackTitle
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.SystemMessage
import org.dash.wallet.common.ui.components.SystemMessageStyle
import org.dash.wallet.common.ui.components.TextField
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

/** One row of the "Paste address from" card: a connected exchange, or one that can be connected. */
data class AddressSourceUIState(
    val id: String,
    /** Resolved display name, e.g. "Uphold". */
    val name: String,
    @DrawableRes val icon: Int,
    /** Deposit address when connected; null/empty shows the "Log in" action instead. */
    val address: String?,
    /**
     * Set when this exchange is connected but can't hold the asset on the selected network
     * (Figma 39439:35111): the row is shown disabled with this message in a yellow system-message
     * card below it, and neither the address nor the "Log in" action is offered.
     */
    val unsupportedMessage: String? = null
)

/**
 * UI state for the Maya "Enter address" screen (Figma node 24007:13081).
 *
 * The host fragment owns the state and keeps the original address-input behavior
 * (validation, paste/scan, exchange sources) from the view-based AddressInputFragment.
 */
data class MayaAddressInputUIState(
    /** Toolbar title, e.g. "Convert DASH to BTC" (passed as a nav argument). */
    val title: String = "",
    /** Field placeholder, e.g. "BTC Address" (passed as a nav argument). */
    val fieldLabel: String = "",
    /** Address as currently entered. */
    val address: String = "",
    /** Connected/connectable exchange address sources. */
    val addressSources: List<AddressSourceUIState> = emptyList(),
    /** Address found in the clipboard; null hides the Clipboard row. */
    val clipboardAddress: String? = null,
    /** Inline validation / quote error shown under the address field; null hides it. */
    val errorMessage: String? = null,
    /** True while the bootstrap quote (address validation against the route) is in flight. */
    val isLoading: Boolean = false,
    /** True when the entered address is non-empty. */
    val continueEnabled: Boolean = false
)

@Composable
fun MayaAddressInputScreen(
    state: MayaAddressInputUIState,
    onBackClick: () -> Unit,
    onAddressChanged: (String) -> Unit,
    onScanClick: () -> Unit,
    onSourceClick: (AddressSourceUIState) -> Unit,
    onClipboardClick: () -> Unit,
    onContinueClick: () -> Unit,
    autoFocus: Boolean = true
) {
    // The old view-based screen opened the soft keyboard on entry (KeyboardUtil.showSoftKeyboard);
    // focusing the field on first composition preserves that behavior.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) {
            focusRequester.requestFocus()
        }
    }

    // No imePadding() here: the host activity uses adjustResize, so the window already shrinks
    // above the keyboard — padding again would lift the button a second keyboard-height up,
    // covering the field.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
    ) {
        NavBarBackTitle(
            title = state.title,
            onBackClick = onBackClick
        )

        // Horizontal insets are applied per-child (not on this column) because the sources card's
        // Menu component supplies its own 20dp horizontal padding.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp)
        ) {
            // Address field (design-system TextField): "BTC Address" is a permanent small label
            // above the text line (innerLabel) — always visible, never a disappearing hint.
            // Scan/clear icon + inline error; shows both the address-format error and the
            // swap-route (quote) error.
            TextField(
                value = state.address,
                onValueChange = onAddressChanged,
                innerLabel = state.fieldLabel,
                message = state.errorMessage,
                isError = state.errorMessage != null,
                enabled = !state.isLoading,
                trailingIcon = CommonR.drawable.ic_scan_qr,
                onTrailingIconClick = onScanClick,
                focusRequester = focusRequester,
                onImeAction = onContinueClick,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // "Paste address from" card: connected exchanges (or their "Log in" action) and the
            // clipboard, when it holds a valid address for this currency.
            if (state.addressSources.isNotEmpty() || state.clipboardAddress != null) {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    Menu {
                        Text(
                            text = stringResource(R.string.maya_paste_address_from),
                            style = MyTheme.Typography.Footnote,
                            color = LocalDashColors.current.textSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        )

                        state.addressSources.forEach { source ->
                            val connected = !source.address.isNullOrEmpty()
                            val unsupported = source.unsupportedMessage != null
                            // An unsupported source offers nothing to tap: no address to paste and
                            // no point connecting again, so the whole row is disabled (dimmed,
                            // icon and label alike) and the trailing button is hidden.
                            ActionItem(
                                title = source.name,
                                enabled = !unsupported,
                                subtitle = source.address?.takeIf { it.isNotEmpty() },
                                subtitleMiddleEllipsis = true,
                                icon = source.icon,
                                trailingButtonText = if (connected || unsupported) {
                                    null
                                } else {
                                    stringResource(CommonR.string.input_log_in)
                                },
                                onTrailingButtonClick = if (connected || unsupported) {
                                    null
                                } else {
                                    ({ onSourceClick(source) })
                                },
                                onClick = { onSourceClick(source) }
                            )

                            // Yellow system message explaining why the exchange above is unusable,
                            // e.g. "Coinbase doesn't support USDC on the TRON network"
                            // (Figma SystemMessage, node 39439:35535). Inset from the Menu card
                            // content edge like in Figma.
                            source.unsupportedMessage?.let { message ->
                                SystemMessage(
                                    description = message,
                                    style = SystemMessageStyle.Yellow,
                                    iconRes = CommonR.drawable.ic_warning_triangle,
                                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                )
                            }
                        }

                        state.clipboardAddress?.let { clipboardAddress ->
                            ActionItem(
                                title = stringResource(R.string.maya_clipboard),
                                subtitle = clipboardAddress,
                                subtitleMiddleEllipsis = true,
                                icon = R.drawable.ic_maya_clipboard,
                                onClick = onClipboardClick
                            )
                        }
                    }
                }
            }
        }

        // Pinned bottom Continue button; shows a spinner while the bootstrap quote validates the
        // address against the swap route.
        DashButton(
            text = stringResource(CommonR.string.button_continue),
            style = Style.FilledBlue,
            size = Size.Large,
            isEnabled = state.continueEnabled && !state.isLoading,
            isLoading = state.isLoading,
            onClick = onContinueClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaAddressInputScreenPreview() {
    MayaAddressInputScreen(
        state = MayaAddressInputUIState(
            title = "Convert DASH to BTC",
            fieldLabel = "BTC Address",
            addressSources = listOf(
                AddressSourceUIState(
                    id = "uphold",
                    name = "Uphold",
                    icon = CommonR.drawable.ic_dash_blue_filled,
                    address = "XsQwPTRMtjzJmccAzYcCzNVbG1UsBGffNc"
                ),
                AddressSourceUIState(
                    id = "coinbase",
                    name = "Coinbase",
                    icon = CommonR.drawable.ic_dash_blue_filled,
                    address = null
                )
            ),
            clipboardAddress = "XbBzWvnvSyWFbYXFtjkWwuPApbfDD263uC"
        ),
        onBackClick = {},
        onAddressChanged = {},
        onScanClick = {},
        onSourceClick = {},
        onClipboardClick = {},
        onContinueClick = {},
        autoFocus = false
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaAddressInputScreenErrorPreview() {
    MayaAddressInputScreen(
        state = MayaAddressInputUIState(
            title = "Convert DASH to BTC",
            fieldLabel = "BTC Address",
            address = "not-a-valid-address",
            errorMessage = "Not a valid BTC Address or URL request",
            continueEnabled = true
        ),
        onBackClick = {},
        onAddressChanged = {},
        onScanClick = {},
        onSourceClick = {},
        onClipboardClick = {},
        onContinueClick = {},
        autoFocus = false
    )
}

/**
 * State behind the two "unsupported network" previews: Coinbase is connected but only holds USDC on
 * Ethereum, so its row is dimmed and unusable and a yellow system message says why, instead of the
 * row silently vanishing (Figma 39439:35111).
 */
private fun unsupportedNetworkPreviewState() = MayaAddressInputUIState(
    title = "Enter Address",
    fieldLabel = "USDC address (TRON)",
    addressSources = listOf(
        AddressSourceUIState(
            id = "uphold",
            name = "Uphold",
            icon = CommonR.drawable.ic_dash_blue_filled,
            address = null
        ),
        AddressSourceUIState(
            id = "coinbase",
            name = "Coinbase",
            icon = CommonR.drawable.ic_dash_blue_filled,
            address = null,
            unsupportedMessage = "Coinbase doesn't support USDC on the TRON network"
        )
    )
)

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaAddressInputScreenUnsupportedNetworkPreview() {
    MayaAddressInputScreen(
        state = unsupportedNetworkPreviewState(),
        onBackClick = {},
        onAddressChanged = {},
        onScanClick = {},
        onSourceClick = {},
        onClipboardClick = {},
        onContinueClick = {},
        autoFocus = false
    )
}

/**
 * Same state in dark mode. Worth its own preview because the warning card's background is the
 * translucent YellowAlpha10: it has to tint the dark card enough for textSecondary
 * (WhiteAlpha80) to stay readable.
 */
@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun MayaAddressInputScreenUnsupportedNetworkDarkPreview() {
    DarkPreviewTheme {
        MayaAddressInputScreen(
            state = unsupportedNetworkPreviewState(),
            onBackClick = {},
            onAddressChanged = {},
            onScanClick = {},
            onSourceClick = {},
            onClipboardClick = {},
            onContinueClick = {},
            autoFocus = false
        )
    }
}
