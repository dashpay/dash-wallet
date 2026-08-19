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

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.DarkPreviewTheme
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.SystemMessage
import org.dash.wallet.common.ui.components.SystemMessageStyle
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.util.Qr
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

@Composable
fun DEXReceiveScreen(
    viewModel: DEXReceiveViewModel,
    onBackClick: () -> Unit,
    onBackHomeClick: () -> Unit,
    onCopyClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DEXReceiveScreenContent(
        coinCode = uiState.coinCode,
        address = uiState.address,
        uri = uiState.uri,
        memo = uiState.memo,
        isLoading = uiState.isLoading,
        errorMessageRes = uiState.errorMessageRes,
        isOnline = uiState.isOnline,
        onBackClick = onBackClick,
        onBackHomeClick = onBackHomeClick,
        onCopyClick = onCopyClick
    )
}

@Composable
private fun DEXReceiveScreenContent(
    coinCode: String,
    address: String,
    uri: String,
    memo: String,
    isLoading: Boolean,
    @StringRes errorMessageRes: Int?,
    isOnline: Boolean,
    onBackClick: () -> Unit,
    onBackHomeClick: () -> Unit,
    onCopyClick: (String) -> Unit
) {
    // The QR encodes the payment URI when present, otherwise the plain address.
    val qrContent = uri.ifBlank { address }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalDashColors.current.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavBarBack(onBackClick = onBackClick)

            // Scrollable content fills the space above the pinned bottom button.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Heading sits directly below the nav bar: Figma's content `pt-116px` is just the
                // height of the (overlaid) status + nav bar that NavBarBack already occupies, so
                // TopIntro's built-in padding (10dp `safe-area/top`, 20dp sides, 20dp below) covers
                // the remaining insets and the gap to the card (matches DEXRefundAddressScreen).
                // No subtitle here per Figma — the equivalent copy now lives in the feature row
                // below the card instead (dex_receive_description).
                TopIntro(heading = stringResource(R.string.dex_receive_heading, coinCode))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // White card: QR + URI row + expiry warning, each section supplying its own
                    // padding (matches Figma, which sets no gap on the card itself — see 35042:51782).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(20.dp),
                                ambientColor = Color(0xFFB8C1CC),
                                spotColor = Color(0xFFB8C1CC)
                            )
                            .background(LocalDashColors.current.backgroundSecondary, RoundedCornerShape(20.dp)),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (errorMessageRes != null) {
                            Text(
                                // coinCode is the format arg; messages without a placeholder ignore it.
                                text = stringResource(errorMessageRes, coinCode),
                                style = MyTheme.Body2Regular,
                                color = LocalDashColors.current.red,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 32.dp)
                            )
                        } else {
                            QrArea(content = qrContent, isLoading = isLoading || qrContent.isBlank())

                            LabeledCopyRow(
                                label = stringResource(R.string.dex_receive_uri_label),
                                value = uri.ifBlank { address },
                                onCopyClick = { onCopyClick(uri.ifBlank { address }) }
                            )

                            // Some chains require a memo/tag alongside the deposit; without it the
                            // funds can't be matched to the swap. The QR only encodes address +
                            // amount, so the memo gets its own copyable row and a red warning.
                            if (memo.isNotBlank()) {
                                LabeledCopyRow(
                                    label = stringResource(R.string.dex_receive_memo_label),
                                    value = memo,
                                    onCopyClick = { onCopyClick(memo) }
                                )
                                Text(
                                    text = stringResource(R.string.dex_receive_memo_warning),
                                    style = MyTheme.Body2Regular,
                                    color = LocalDashColors.current.red,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                )
                            }

                            // Expiry warning, inside the card below the address (Figma 38669:7486).
                            SystemMessage(
                                title = stringResource(R.string.dex_receive_expiry_title),
                                description = stringResource(
                                    R.string.dex_receive_expiry_message,
                                    coinCode
                                ),
                                style = SystemMessageStyle.Yellow,
                                iconRes = CommonR.drawable.ic_warning_triangle,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }

                    // Feature row explaining the post-deposit flow (Figma 38669:27847) — the same
                    // copy that used to sit under the heading as a plain subtitle.
                    DepositInfoRow(text = stringResource(R.string.dex_receive_description))
                }
            }

            // Pinned bottom "Back home" button (btn-l tinted-gray). Figma insets the button an extra
            // 40dp inside the 20dp safe area, so it sits narrower than the cards above.
            DashButton(
                text = stringResource(R.string.dex_receive_back_home),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onBackHomeClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 60.dp, vertical = 16.dp)
            )
        }

        // No-connection toast, pinned at the bottom. Not dismissable — it stays visible for as long
        // as the device is offline.
        if (!isOnline) {
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
 * Icon + text row explaining what happens after the deposit is confirmed (Figma 38669:27832),
 * sitting below the card. The extra end padding (on top of the screen's 20dp side inset) matches
 * Figma's wider right margin so the paragraph doesn't run the full screen width.
 */
@Composable
private fun DepositInfoRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(LocalDashColors.current.gray, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(CommonR.drawable.ic_arrow_downward_blue_24dp),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = text,
            style = MyTheme.Typography.BodyMedium,
            color = LocalDashColors.current.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(top = 10.dp)
        )
    }
}

/** 160dp QR centered in a 30dp vertical band (Figma "wrap.qr", 35042:51784), or a spinner while loading. */
@Composable
private fun QrArea(content: String, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Stays white in dark mode too: QR modules need a light background to scan reliably.
            .background(Color.White)
            .padding(vertical = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(content, isLoading) {
                if (isLoading || content.isBlank()) null else Qr.qrBitmap(content)
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    // The QR bitmap is the raw module grid (tiny); scaling it to 160dp with the
                    // default (smoothing) filter blurs the modules. None = nearest-neighbour, so the
                    // squares stay crisp (mirrors Qr.themeAwareDrawable's isFilterBitmap = false).
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(160.dp)
                )
            } else {
                CircularProgressIndicator(color = LocalDashColors.current.dashBlue)
            }
        }
    }
}

/** Full-width row: label + single-line value on the left, a tinted-gray copy button on the right. */
@Composable
private fun LabeledCopyRow(label: String, value: String, onCopyClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Footnote (13sp, text/secondary)
            Text(
                text = label,
                style = MyTheme.Typography.Footnote,
                color = LocalDashColors.current.textSecondary
            )
            // Subhead (15sp, text/primary)
            Text(
                text = value,
                style = MyTheme.Typography.Subhead,
                color = LocalDashColors.current.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // btn-m tinted-gray: bg rgba(176,182,188,0.1), rounded 14dp, padding 16/10.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LocalDashColors.current.gray.copy(alpha = 0.10f))
                .clickable(onClick = onCopyClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(CommonR.drawable.ic_copy),
                contentDescription = label,
                tint = LocalDashColors.current.textPrimary,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXReceiveScreenLoadingPreview() {
    DEXReceiveScreenContent(
        coinCode = "BTC",
        address = "",
        uri = "",
        memo = "",
        isLoading = true,
        errorMessageRes = null,
        isOnline = true,
        onBackClick = {},
        onBackHomeClick = {},
        onCopyClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXReceiveScreenLoadedPreview() {
    DEXReceiveScreenContent(
        coinCode = "BTC",
        address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        uri = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        memo = "",
        isLoading = false,
        errorMessageRes = null,
        isOnline = true,
        onBackClick = {},
        onBackHomeClick = {},
        onCopyClick = {}
    )
}

/**
 * Loaded state in dark mode, to check the expiry [SystemMessage] card: its background is the
 * translucent YellowAlpha10, which has to tint the dark card enough for the title (textPrimary)
 * and description (textSecondary) to stay readable.
 */
@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXReceiveScreenLoadedDarkPreview() {
    DarkPreviewTheme {
        DEXReceiveScreenContent(
            coinCode = "BTC",
            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
            uri = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
            memo = "",
            isLoading = false,
            errorMessageRes = null,
            isOnline = true,
            onBackClick = {},
            onBackHomeClick = {},
            onCopyClick = {}
        )
    }
}

// Mirrors a real Galaxy S22 (SM-S901U): 1080x2340px @ 480dpi (xxhdpi, scale 3.0) measures to
// exactly 360x780dp — confirmed via `adb shell wm size` / `wm density`.
@Preview(
    name = "Galaxy S22 @ 1.25x font",
    showBackground = true,
    device = "spec:width=360dp,height=780dp,dpi=480",
    fontScale = 1.25f,
    showSystemUi = true
)
@Composable
private fun DEXReceiveScreenGalaxyS22Preview() {
    DEXReceiveScreenContent(
        coinCode = "BTC",
        address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        uri = "bitcoin:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
        memo = "",
        isLoading = false,
        errorMessageRes = null,
        isOnline = true,
        onBackClick = {},
        onBackHomeClick = {},
        onCopyClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXReceiveScreenMemoPreview() {
    DEXReceiveScreenContent(
        coinCode = "XRP",
        address = "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh",
        uri = "rEb8TK3gBgk5auZkwc6sHnwrGVJH8DuaLh",
        memo = "2043055709",
        isLoading = false,
        errorMessageRes = null,
        isOnline = true,
        onBackClick = {},
        onBackHomeClick = {},
        onCopyClick = {}
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 760)
@Composable
private fun DEXReceiveScreenErrorPreview() {
    DEXReceiveScreenContent(
        coinCode = "BTC",
        address = "",
        uri = "",
        memo = "",
        isLoading = false,
        errorMessageRes = R.string.dex_error_no_route,
        isOnline = true,
        onBackClick = {},
        onBackHomeClick = {},
        onCopyClick = {}
    )
}
