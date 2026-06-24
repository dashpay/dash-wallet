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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.util.Qr
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.R as CommonR

@Composable
fun DEXReceiveScreen(
    viewModel: DEXReceiveViewModel,
    onBackClick: () -> Unit,
    onCopyClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DEXReceiveScreenContent(
        coinCode = uiState.coinCode,
        address = uiState.address,
        uri = uiState.uri,
        isLoading = uiState.isLoading,
        errorMessage = uiState.errorMessage,
        onBackClick = onBackClick,
        onCopyClick = onCopyClick
    )
}

@Composable
private fun DEXReceiveScreenContent(
    coinCode: String,
    address: String,
    uri: String,
    isLoading: Boolean,
    errorMessage: String?,
    onBackClick: () -> Unit,
    onCopyClick: (String) -> Unit
) {
    // The QR encodes the payment URI when present, otherwise the plain address.
    val qrContent = uri.ifBlank { address }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        NavBarBack(onBackClick = onBackClick)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Heading sits directly below the nav bar: Figma's content `pt-116px` is just the
                // height of the (overlaid) status + nav bar that NavBarBack already occupies, so only
                // the 10dp `safe-area/top` remains as real padding (matches DEXRefundAddressScreen).
                .padding(top = 10.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            org.dash.wallet.common.ui.components.TopIntro(
                heading = stringResource(R.string.dex_receive_heading, coinCode),
                text = stringResource(R.string.dex_receive_description),
                modifier = Modifier.fillMaxWidth()
            )

            // White card with QR + URI row. shadows/xs: #B8C1CC ~10% alpha, y=5, blur=20.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color(0xFFB8C1CC),
                        spotColor = Color(0xFFB8C1CC)
                    )
                    .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                    .padding(top = 40.dp, bottom = 20.dp, start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    QrArea(content = qrContent, isLoading = isLoading || qrContent.isBlank())

                    UriRow(
                        uri = uri.ifBlank { address },
                        onCopyClick = { onCopyClick(uri.ifBlank { address }) }
                    )
                }
            }
        }
    }
}

/** White, rounded box holding the 200dp QR, or a centered spinner while the address is loading. */
@Composable
private fun QrArea(content: String, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(content) {
                if (isLoading || content.isBlank()) null else Qr.qrBitmap(content)
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(200.dp)
                )
            } else {
                CircularProgressIndicator(color = MyTheme.Colors.dashBlue)
            }
        }
    }
}

/** Full-width row: "URI" label + value on the left, a tinted-gray copy button on the right. */
@Composable
private fun UriRow(uri: String, onCopyClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Footnote (13sp, text/secondary): closest existing token is MyTheme.Caption (13sp).
            Text(
                text = stringResource(R.string.dex_receive_uri_label),
                style = MyTheme.Caption,
                color = MyTheme.Colors.textSecondary
            )
            // Subhead (15sp, text/primary): no 15sp token exists; closest is BodyMedium (14sp).
            Text(
                text = uri,
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textPrimary
            )
        }

        // btn-m tinted-gray: bg rgba(176,182,188,0.1), rounded 14dp, padding 16/10.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x1AB0B6BC))
                .clickable(onClick = onCopyClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(CommonR.drawable.ic_copy),
                contentDescription = stringResource(R.string.dex_receive_uri_label),
                tint = MyTheme.Colors.textPrimary,
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
        isLoading = true,
        errorMessage = null,
        onBackClick = {},
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
        isLoading = false,
        errorMessage = null,
        onBackClick = {},
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
        isLoading = false,
        errorMessage = "No route found for this amount",
        onBackClick = {},
        onCopyClick = {}
    )
}