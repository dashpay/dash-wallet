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

package de.schildbach.wallet.ui.more.connections

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashSwitch
import org.dash.wallet.common.ui.components.ListEmptyState
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DashConnect "Connections" screen (Figma: 5787:50880 empty state;
 * 5775:51511 / 5775:51483 / 5775:51451 list states).
 */
@Composable
fun ConnectionsScreen(
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onConnectionQrClick: (DAppConnection) -> Unit = {},
    onConnectionToggle: (DAppConnection, Boolean) -> Unit = { _, _ -> }
) {
    val viewModel: ConnectionsViewModel = hiltViewModel()

    ConnectionsScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onScanClick = onScanClick,
        onConnectionClick = onConnectionClick,
        onConnectionQrClick = onConnectionQrClick,
        onConnectionToggle = onConnectionToggle
    )
}

@Composable
fun ConnectionsScreen(
    uiStateFlow: StateFlow<ConnectionsUIState>,
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onConnectionQrClick: (DAppConnection) -> Unit = {},
    onConnectionToggle: (DAppConnection, Boolean) -> Unit = { _, _ -> }
) {
    val uiState by uiStateFlow.collectAsState()

    ConnectionsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onScanClick = onScanClick,
        onConnectionClick = onConnectionClick,
        onConnectionQrClick = onConnectionQrClick,
        onConnectionToggle = onConnectionToggle
    )
}

@Composable
private fun ConnectionsScreenContent(
    uiState: ConnectionsUIState,
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onConnectionQrClick: (DAppConnection) -> Unit = {},
    onConnectionToggle: (DAppConnection, Boolean) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        NavBarBack(onBackClick = onBackClick)

        TopIntro(
            heading = stringResource(R.string.dash_connect_connections_title)
        )

        if (uiState.featureUnavailable) {
            ConnectionsUnavailableState(modifier = Modifier.weight(1f))
        } else if (uiState.connections.isEmpty()) {
            ConnectionsEmptyState(
                onScanClick = onScanClick,
                modifier = Modifier.weight(1f)
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Menu {
                    uiState.connections.forEach { connection ->
                        ConnectionListItem(
                            connection = connection,
                            onClick = { onConnectionClick(connection) },
                            onQrClick = { onConnectionQrClick(connection) },
                            onToggle = { enabled -> onConnectionToggle(connection, enabled) }
                        )
                        // Blue-tint banner attached inside the card, under an APPROVED connection
                        // awaiting login (Figma 5802:51517 SystemMessage): prompts the login scan.
                        if (connection.status == ConnectionStatus.APPROVED) {
                            ScanToCompleteBanner(onScanClick = onScanClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionsUnavailableState(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        ListEmptyState(
            icon = {
                Image(
                    painter = painterResource(R.drawable.ic_connections_empty),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .size(80.dp)
                )
            },
            heading = stringResource(R.string.dash_connect_testnet_only_heading),
            body = stringResource(R.string.dash_connect_testnet_only_message)
        )
    }
}

@Composable
private fun ConnectionsEmptyState(
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Icon + heading + body inside a white rounded card (Figma 5787:51009 / 5787:50880),
    // with the Scan QR button in its own centered wrap below the card.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MyTheme.Colors.backgroundSecondary, RoundedCornerShape(20.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(40.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_connections_empty),
                contentDescription = null,
                modifier = Modifier.size(80.dp)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.dash_connect_empty_heading),
                    style = MyTheme.Typography.TitleMediumSemibold,
                    color = MyTheme.Colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.dash_connect_empty_message),
                    style = MyTheme.Typography.BodyMedium,
                    color = MyTheme.Colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        DashButton(
            text = stringResource(R.string.dash_connect_scan_qr),
            style = Style.FilledBlue,
            size = Size.Large,
            onClick = onScanClick,
            modifier = Modifier
                .padding(top = 40.dp, start = 80.dp, end = 80.dp)
                .width(200.dp)
        )
    }
}

/**
 * Blue-tint SystemMessage banner (Figma 5802:51517): info icon + prompt text + a small
 * filled-blue "Scan QR" button. Shown for APPROVED connections awaiting login.
 */
@Composable
private fun ScanToCompleteBanner(
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // No outer horizontal inset: the banner lives inside the Menu card, which already
            // insets its children — matching Figma where the banner is attached to the card.
            .background(MyTheme.Colors.dashBlue5, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_info_blue),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = stringResource(R.string.dash_connect_scan_to_complete_banner),
            style = MyTheme.Typography.BodyMedium,
            color = MyTheme.Colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        DashButton(
            text = stringResource(R.string.dash_connect_scan_qr),
            style = Style.FilledBlue,
            size = Size.Small,
            stretch = false,
            onClick = onScanClick
        )
    }
}

@Composable
private fun ConnectionListItem(
    connection: DAppConnection,
    onClick: () -> Unit,
    onQrClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val statusText = stringResource(
        when (connection.status) {
            ConnectionStatus.APPROVED -> R.string.dash_connect_status_approved
            ConnectionStatus.ACTIVE -> R.string.dash_connect_status_active
            ConnectionStatus.DISCONNECTED -> R.string.dash_connect_status_disconnected
        }
    )
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, H:mm", Locale.getDefault()) }
    val dotColor = if (connection.status == ConnectionStatus.ACTIVE) {
        MyTheme.Colors.green
    } else {
        MyTheme.Colors.dashBlue
    }

    ListItem(
        title = connection.name,
        subtitle = connection.url.ifBlank { null },
        onClick = onClick,
        trailingContent = {
            // Figma 5799:51367 active row: status (dot + text) + date column, and — for an
            // active connection — a toggle to its right whose OFF transition disconnects.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                        Text(
                            text = statusText,
                            style = MyTheme.Typography.BodySmall,
                            color = MyTheme.Colors.textPrimary
                        )
                    }
                    Text(
                        text = dateFormat.format(Date(connection.updatedAt)),
                        style = MyTheme.Typography.BodySmall,
                        color = MyTheme.Colors.textTertiary
                    )
                }
                if (connection.status == ConnectionStatus.ACTIVE) {
                    DashSwitch(
                        checked = true,
                        onCheckedChange = { enabled -> onToggle(enabled) }
                    )
                }
            }
        }
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────

private fun previewConnection(status: ConnectionStatus) = DAppConnection(
    id = "1",
    name = "Yappr",
    url = "yappr.io",
    status = status,
    updatedAt = 1773132300000L // 10 Mar 2026, 9:45
)

@Composable
@Preview
private fun ConnectionsScreenEmptyPreview() {
    ConnectionsScreenContent(uiState = ConnectionsUIState())
}

@Composable
@Preview
private fun ConnectionsScreenApprovedPreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            connections = listOf(previewConnection(ConnectionStatus.APPROVED))
        )
    )
}

@Composable
@Preview
private fun ConnectionsScreenActivePreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            connections = listOf(previewConnection(ConnectionStatus.ACTIVE))
        )
    )
}

@Composable
@Preview
private fun ConnectionsScreenDisconnectedPreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            connections = listOf(previewConnection(ConnectionStatus.DISCONNECTED))
        )
    )
}

@Composable
@Preview(showBackground = true, backgroundColor = 0xFFF5F6F7)
private fun ScanToCompleteBannerPreview() {
    ScanToCompleteBanner(onScanClick = {})
}

@Composable
@Preview(showBackground = true)
private fun ConnectionListItemActivePreview() {
    Menu {
        ConnectionListItem(
            connection = previewConnection(ConnectionStatus.ACTIVE),
            onClick = {},
            onQrClick = {},
            onToggle = {}
        )
    }
}

@Composable
@Preview(showBackground = true)
private fun ConnectionListItemApprovedPreview() {
    Menu {
        ConnectionListItem(
            connection = previewConnection(ConnectionStatus.APPROVED),
            onClick = {},
            onQrClick = {},
            onToggle = {}
        )
    }
}
