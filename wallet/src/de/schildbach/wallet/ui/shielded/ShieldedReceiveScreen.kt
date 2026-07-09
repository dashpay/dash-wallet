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

package de.schildbach.wallet.ui.shielded

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.util.Qr

/**
 * Shielded receive screen — QR + copy/share of the bech32m shielded
 * address (content from the Receive tab of Figma 1905:2995). Currently
 * parked: reachable via [ShieldedBalanceActivity.createIntent] with
 * [ShieldedBalanceActivity.SCREEN_RECEIVE]; the redesigned payments
 * Receive tab shows only the standard wallet receive.
 */
@Composable
fun ShieldedReceiveScreen(
    viewModel: ShieldedReceiveViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onCopyAddress: (String) -> Unit,
    onShareAddress: (String) -> Unit
) {
    ShieldedReceiveScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onCopyAddress = onCopyAddress,
        onShareAddress = onShareAddress
    )
}

@Composable
fun ShieldedReceiveScreen(
    uiStateFlow: StateFlow<ShieldedReceiveUIState>,
    onBackClick: () -> Unit = {},
    onCopyAddress: (String) -> Unit = {},
    onShareAddress: (String) -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    ShieldedReceiveScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onCopyAddress = onCopyAddress,
        onShareAddress = onShareAddress
    )
}

@Composable
private fun ShieldedReceiveScreenContent(
    uiState: ShieldedReceiveUIState,
    onBackClick: () -> Unit = {},
    onCopyAddress: (String) -> Unit = {},
    onShareAddress: (String) -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavBase(
                leadingIcon = MyImages.MenuChevron,
                onLeadingClick = onBackClick,
                centralPart = false,
                trailingPart = false
            )

            TopIntro(heading = stringResource(R.string.shielded_tab_receive))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                ReceiveCard(
                    address = uiState.shieldedAddress,
                    onCopyAddress = onCopyAddress,
                    onShareAddress = onShareAddress
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // "Wait until the chain is fully synced…" (Figma 1733:16190 toast)
        if (uiState.readyCheckDone && !uiState.shieldedReady) {
            Toast(
                text = stringResource(R.string.shielded_error_not_ready),
                imageResource = ToastImageResource.Loading.resourceId,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 15.dp, vertical = 20.dp),
                onActionClick = {}
            )
        }
    }
}

/** QR + address + copy/share card (content of Figma 1905:3012). */
@Composable
private fun ReceiveCard(
    address: String?,
    onCopyAddress: (String) -> Unit,
    onShareAddress: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (address == null) {
            Text(
                text = stringResource(R.string.shielded_address_unavailable),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 40.dp)
            )
            return@Column
        }

        val qrBitmap = remember(address) { Qr.qrBitmap(address) }
        qrBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MyTheme.Colors.textPrimary, BlendMode.SrcIn),
                modifier = Modifier
                    .padding(top = 20.dp, bottom = 30.dp)
                    .size(200.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shielded_your_address),
                    style = MyTheme.Typography.BodySmall,
                    color = MyTheme.Colors.textTertiary
                )
                Text(
                    text = address,
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textPrimary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { onCopyAddress(address) }
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x1AB0B6BC))
                    .clickable { onCopyAddress(address) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(org.dash.wallet.common.R.drawable.ic_copy),
                    contentDescription = stringResource(R.string.shielded_copy_address),
                    tint = MyTheme.Colors.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        DashButton(
            text = stringResource(R.string.shielded_share_address),
            style = Style.TintedGray,
            size = Size.Medium,
            onClick = { onShareAddress(address) }
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Receive – ready")
@Composable
private fun ShieldedReceivePreview() {
    ShieldedReceiveScreenContent(
        uiState = ShieldedReceiveUIState(
            shieldedReady = true,
            readyCheckDone = true,
            shieldedAddress = "tdash1qyz3xpruchngctwfvczq5wpfg7rpytqvwka"
        )
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Receive – not ready")
@Composable
private fun ShieldedReceiveNotReadyPreview() {
    ShieldedReceiveScreenContent(
        uiState = ShieldedReceiveUIState(
            shieldedReady = false,
            readyCheckDone = true
        )
    )
}
