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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MenuItem
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.Toast
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.components.TopIntro
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.segmented_picker.SegmentedPickerStyle
import org.dash.wallet.common.util.Qr

/**
 * Shielded "Payments" hub — Figma 1693:15911 (tabs from 1905:2995 Receive,
 * 1684:13169 Internal, 1684:12990 Send) plus the balance cards from the
 * "More" frame 1691:15460.
 */
@Composable
fun ShieldedHomeScreen(
    onBackClick: () -> Unit,
    onInternalTransferClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onSendToAddressClick: () -> Unit,
    onCopyAddress: (String) -> Unit,
    onShareAddress: (String) -> Unit
) {
    val viewModel: ShieldedHomeViewModel = hiltViewModel()
    ShieldedHomeScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onInternalTransferClick = onInternalTransferClick,
        onScanQrClick = onScanQrClick,
        onSendToAddressClick = onSendToAddressClick,
        onCopyAddress = onCopyAddress,
        onShareAddress = onShareAddress
    )
}

@Composable
fun ShieldedHomeScreen(
    uiStateFlow: StateFlow<ShieldedHomeUIState>,
    onBackClick: () -> Unit = {},
    onInternalTransferClick: () -> Unit = {},
    onScanQrClick: () -> Unit = {},
    onSendToAddressClick: () -> Unit = {},
    onCopyAddress: (String) -> Unit = {},
    onShareAddress: (String) -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    ShieldedHomeScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onInternalTransferClick = onInternalTransferClick,
        onScanQrClick = onScanQrClick,
        onSendToAddressClick = onSendToAddressClick,
        onCopyAddress = onCopyAddress,
        onShareAddress = onShareAddress
    )
}

private const val TAB_RECEIVE = 0
private const val TAB_INTERNAL = 1
private const val TAB_SEND = 2

@Composable
private fun ShieldedHomeScreenContent(
    uiState: ShieldedHomeUIState,
    onBackClick: () -> Unit = {},
    onInternalTransferClick: () -> Unit = {},
    onScanQrClick: () -> Unit = {},
    onSendToAddressClick: () -> Unit = {},
    onCopyAddress: (String) -> Unit = {},
    onShareAddress: (String) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_RECEIVE) }

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

            TopIntro(heading = stringResource(R.string.shielded_balance_title))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Balance cards (Figma 1693:15608 "HStack" of balancePrev)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    BalanceCard(
                        title = stringResource(R.string.shielded_wallet_name),
                        amountText = uiState.walletBalance.toDisplayString(),
                        fiatText = uiState.walletBalanceFiat,
                        icon = R.drawable.ic_dash_d_black,
                        modifier = Modifier.weight(1f)
                    )
                    BalanceCard(
                        title = stringResource(R.string.shielded_balance_card_title),
                        amountText = uiState.shieldedBalance.toDisplayString(),
                        fiatText = uiState.shieldedBalanceFiat,
                        icon = R.drawable.ic_dash_d_black,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Receive | Internal | Send (Figma SegmentedControls 1684:13003)
                SegmentedPicker(
                    options = listOf(
                        SegmentedOption(stringResource(R.string.shielded_tab_receive)),
                        SegmentedOption(stringResource(R.string.shielded_tab_internal)),
                        SegmentedOption(stringResource(R.string.shielded_tab_send))
                    ),
                    selectedIndex = selectedTab,
                    onOptionSelected = { _, index -> selectedTab = index },
                    style = SegmentedPickerStyle(cornerRadius = 16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(44.dp)
                )

                when (selectedTab) {
                    TAB_RECEIVE -> ReceiveTab(
                        address = uiState.shieldedAddress,
                        onCopyAddress = onCopyAddress,
                        onShareAddress = onShareAddress
                    )
                    TAB_INTERNAL -> InternalTab(onInternalTransferClick = onInternalTransferClick)
                    TAB_SEND -> SendTab(
                        onScanQrClick = onScanQrClick,
                        onSendToAddressClick = onSendToAddressClick
                    )
                }

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

/** One balance card (Figma "balancePrev" 1693:15852). */
@Composable
private fun BalanceCard(
    title: String,
    amountText: String,
    fiatText: String?,
    icon: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(16.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MyTheme.Typography.TitleSmallSemibold,
            color = MyTheme.Colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MyTheme.Colors.backgroundPrimary, RoundedCornerShape(12.dp))
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = amountText,
                style = MyTheme.Typography.TitleMediumSemibold,
                color = MyTheme.Colors.textPrimary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        }
        fiatText?.let {
            Text(
                text = it,
                style = MyTheme.Typography.BodySmall,
                color = MyTheme.Colors.textTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Receive tab — Figma 1905:2995: QR, address + copy, Share address. */
@Composable
private fun ReceiveTab(
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

/** Internal tab — Figma 1684:13169. */
@Composable
private fun InternalTab(onInternalTransferClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Menu {
            Text(
                text = stringResource(R.string.shielded_internal_transfer_to_from),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            MenuItem(
                title = stringResource(R.string.shielded_balance_title),
                icon = R.drawable.ic_shielded_balance,
                action = onInternalTransferClick
            )
        }
    }
}

/** Send tab — Figma 1684:12990. */
@Composable
private fun SendTab(
    onScanQrClick: () -> Unit,
    onSendToAddressClick: () -> Unit
) {
    Menu {
        MenuItem(
            title = stringResource(R.string.shielded_scan_qr),
            icon = R.drawable.ic_scan_qr,
            action = onScanQrClick
        )
        MenuItem(
            title = stringResource(R.string.shielded_send_to_address),
            icon = R.drawable.ic_send_to_address,
            action = onSendToAddressClick
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Home – Receive tab")
@Composable
private fun ShieldedHomePreview() {
    ShieldedHomeScreenContent(
        uiState = ShieldedHomeUIState(
            shieldedReady = true,
            readyCheckDone = true,
            shieldedBalance = Dash.parse("115.5"),
            shieldedBalanceFiat = "$5,775.00",
            walletBalance = Dash.parse("2.00"),
            walletBalanceFiat = "$100.00",
            shieldedAddress = "tdash1qyz3xpruchngctwfvczq5wpfg7rpytqvwka"
        )
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "Home – not ready")
@Composable
private fun ShieldedHomeNotReadyPreview() {
    ShieldedHomeScreenContent(
        uiState = ShieldedHomeUIState(
            shieldedReady = false,
            readyCheckDone = true,
            shieldedBalance = Dash.ZERO,
            walletBalance = Dash.parse("2.00")
        )
    )
}
