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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.components.DashButton
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
    onConnectionQrClick: (DAppConnection) -> Unit = {}
) {
    val viewModel: ConnectionsViewModel = hiltViewModel()

    ConnectionsScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onScanClick = onScanClick,
        onConnectionClick = onConnectionClick,
        onConnectionQrClick = onConnectionQrClick
    )
}

@Composable
fun ConnectionsScreen(
    uiStateFlow: StateFlow<ConnectionsUIState>,
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onConnectionQrClick: (DAppConnection) -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()

    ConnectionsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onScanClick = onScanClick,
        onConnectionClick = onConnectionClick,
        onConnectionQrClick = onConnectionQrClick
    )
}

@Composable
private fun ConnectionsScreenContent(
    uiState: ConnectionsUIState,
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onConnectionQrClick: (DAppConnection) -> Unit = {}
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

        if (uiState.connections.isEmpty()) {
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
                            onQrClick = { onConnectionQrClick(connection) }
                        )
                    }
                }

                connectionsHint(uiState.connections)?.let { hint ->
                    Text(
                        text = hint,
                        style = MyTheme.Typography.BodyMedium,
                        color = MyTheme.Colors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionsEmptyState(
    onScanClick: () -> Unit,
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
            heading = stringResource(R.string.dash_connect_empty_heading),
            body = stringResource(R.string.dash_connect_empty_message),
            actions = {
                DashButton(
                    text = stringResource(R.string.dash_connect_scan_qr),
                    style = Style.FilledBlue,
                    size = Size.Large,
                    onClick = onScanClick,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .width(200.dp)
                )
            }
        )
    }
}

@Composable
private fun ConnectionListItem(
    connection: DAppConnection,
    onClick: () -> Unit,
    onQrClick: () -> Unit
) {
    val statusText = stringResource(
        when (connection.status) {
            ConnectionStatus.APPROVED -> R.string.dash_connect_status_approved
            ConnectionStatus.ACTIVE -> R.string.dash_connect_status_active
            ConnectionStatus.DISCONNECTED -> R.string.dash_connect_status_disconnected
        }
    )
    val dotColor = when (connection.status) {
        ConnectionStatus.ACTIVE -> MyTheme.Colors.green
        else -> MyTheme.Colors.dashBlue
    }
    val showQr = connection.status != ConnectionStatus.ACTIVE
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, H:mm", Locale.getDefault()) }

    ListItem(
        title = connection.name,
        subtitle = connection.url,
        onClick = onClick,
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (showQr) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable { onQrClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_connections_qr),
                            contentDescription = stringResource(R.string.dash_connect_scan_qr),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

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
            }
        }
    )
}

/**
 * Hint shown below the connections list. With several connections the hint of
 * the state requiring the most attention is shown (approved > disconnected > active).
 */
@Composable
private fun connectionsHint(connections: List<DAppConnection>): String? {
    connections.firstOrNull { it.status == ConnectionStatus.APPROVED }?.let {
        return stringResource(R.string.dash_connect_hint_complete_login, it.name)
    }
    connections.firstOrNull { it.status == ConnectionStatus.DISCONNECTED }?.let {
        return stringResource(R.string.dash_connect_hint_log_back_in, it.name)
    }
    connections.firstOrNull { it.status == ConnectionStatus.ACTIVE }?.let {
        return stringResource(R.string.dash_connect_hint_active)
    }
    return null
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
