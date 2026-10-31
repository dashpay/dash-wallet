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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.StateFlow
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.ListEmptyState
import org.dash.wallet.common.ui.components.ListItem
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.NavBarBack
import org.dash.wallet.common.ui.components.NavBarBackPlus
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.common.ui.segmented_picker.SegmentedPicker
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopIntro

/**
 * DashConnect "Connections" screen (Figma: 5787:50880 empty state; 40042:59884 tabbed list).
 */
@Composable
fun ConnectionsScreen(
    uiStateFlow: StateFlow<ConnectionsUIState>,
    onRetryClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onTabSelected: (ConnectionsTab) -> Unit = {},
    onDismissSharedKeys: () -> Unit = {},
    onRevokeSharedKeys: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()

    ConnectionsScreenContent(
        uiState = uiState,
        onRetryClick = onRetryClick,
        onBackClick = onBackClick,
        onScanClick = onScanClick,
        onConnectionClick = onConnectionClick,
        onTabSelected = onTabSelected,
        onDismissSharedKeys = onDismissSharedKeys,
        onRevokeSharedKeys = onRevokeSharedKeys
    )
}

@Composable
private fun ConnectionsScreenContent(
    uiState: ConnectionsUIState,
    onRetryClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onConnectionClick: (DAppConnection) -> Unit = {},
    onTabSelected: (ConnectionsTab) -> Unit = {},
    onDismissSharedKeys: () -> Unit = {},
    onRevokeSharedKeys: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    val hasConnections = !uiState.featureUnavailable && uiState.recordsStatus == ConnectionRecordsStatus.READY &&
        uiState.connections.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
    ) {
        // Once there is a connection the empty state's Scan QR button is gone; + adds another.
        if (hasConnections) {
            NavBarBackPlus(onBackClick = onBackClick, onPlusClick = onScanClick)
        } else {
            NavBarBack(onBackClick = onBackClick)
        }

        TopIntro(
            heading = stringResource(R.string.dash_connect_connections_title)
        )

        if (uiState.featureUnavailable) {
            ConnectionsUnavailableState(modifier = Modifier.weight(1f))
        } else if (uiState.recordsStatus == ConnectionRecordsStatus.LOADING) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.dashBlue)
            }
        } else if (uiState.recordsStatus == ConnectionRecordsStatus.NO_IDENTITY) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.dash_connect_identity_required),
                    style = MyTheme.Typography.TitleMediumSemibold, color = colors.textPrimary,
                    textAlign = TextAlign.Center)
            }
        } else if (uiState.recordsStatus == ConnectionRecordsStatus.UNAVAILABLE) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(40.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.dash_connect_records_unavailable),
                    style = MyTheme.Typography.TitleMediumSemibold, color = colors.textPrimary)
                Text(stringResource(R.string.dash_connect_records_unavailable_message),
                    style = MyTheme.Typography.BodyMedium, color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 20.dp))
                DashButton(text = stringResource(R.string.dash_connect_records_retry),
                    style = Style.FilledBlue, size = Size.Large, onClick = onRetryClick)
            }
        } else if (uiState.connections.isEmpty()) {
            ConnectionsEmptyState(
                onScanClick = onScanClick,
                modifier = Modifier.weight(1f)
            )
        } else {
            val tabs = ConnectionsTab.entries
            SegmentedPicker(
                options = tabs.map { SegmentedOption(stringResource(it.title)) },
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .height(46.dp),
                selectedIndex = tabs.indexOf(uiState.selectedTab)
            ) { _, index -> onTabSelected(tabs[index]) }

            val shown = uiState.connections.filter { uiState.selectedTab.includes(it) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(uiState.selectedTab.emptyMessage),
                        style = MyTheme.Typography.BodyMedium,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp)
                    )
                } else {
                    Menu {
                        shown.forEach { connection ->
                            ConnectionListItem(connection = connection, onClick = { onConnectionClick(connection) })
                        }
                    }
                }
            }
        }

        val sharedKeys = uiState.sharedProfileKeys
        if (sharedKeys != null && !uiState.featureUnavailable && uiState.recordsStatus == ConnectionRecordsStatus.READY) {
            SharedProfileAccessMessage(
                keyCount = sharedKeys.keyIds.size,
                isRevoking = uiState.isRevokingSharedKeys,
                onDismiss = onDismissSharedKeys,
                onRevoke = onRevokeSharedKeys,
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp)
            )
        }
    }
}

/** Figma 40101:8829: enabled keys that can edit the DashPay profile but belong to no connection here. */
@Composable
private fun SharedProfileAccessMessage(
    keyCount: Int,
    isRevoking: Boolean,
    onDismiss: () -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalDashColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.backgroundSecondary)
            .padding(10.dp)
    ) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_connect_warning), contentDescription = null)
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(R.string.dash_connect_shared_profile_title),
                    style = MyTheme.Typography.FootnoteSemibold,
                    color = colors.textPrimary
                )
                Text(
                    text = pluralStringResource(R.plurals.dash_connect_shared_profile_message, keyCount, keyCount),
                    style = MyTheme.Typography.Footnote,
                    color = colors.textPrimary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashButton(
                    text = stringResource(R.string.dash_connect_shared_profile_dismiss),
                    style = Style.TintedBlue,
                    size = Size.Small,
                    isEnabled = !isRevoking,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                DashButton(
                    text = stringResource(R.string.dash_connect_shared_profile_revoke),
                    style = Style.FilledBlue,
                    size = Size.Small,
                    isEnabled = !isRevoking,
                    modifier = Modifier.weight(1f),
                    onClick = onRevoke
                )
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
            heading = stringResource(R.string.dash_connect_unavailable_heading),
            body = stringResource(R.string.dash_connect_unavailable_message)
        )
    }
}

@Composable
private fun ConnectionsEmptyState(
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Icon + heading + body inside a rounded card (Figma 5787:51009 / 5787:50880),
    // with the Scan QR button in its own centered wrap below the card.
    val colors = LocalDashColors.current
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
                .background(colors.backgroundSecondary, RoundedCornerShape(20.dp))
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
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.dash_connect_empty_message),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.textSecondary,
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

/** Figma 40058:22554 ("List - Merchant details"): the app name over its website. */
@Composable
private fun ConnectionListItem(
    connection: DAppConnection,
    onClick: () -> Unit
) {
    ListItem(
        title = connection.name,
        subtitle = connection.url.ifBlank { null },
        onClick = onClick
    )
}

@get:androidx.annotation.StringRes
private val ConnectionsTab.title: Int
    get() = when (this) {
        ConnectionsTab.ACTIVE -> R.string.dash_connect_status_active
        ConnectionsTab.DISCONNECTED -> R.string.dash_connect_status_disconnected
        ConnectionsTab.EXPIRED -> R.string.dash_connect_status_expired
    }

@get:androidx.annotation.StringRes
private val ConnectionsTab.emptyMessage: Int
    get() = when (this) {
        ConnectionsTab.ACTIVE -> R.string.dash_connect_tab_empty_active
        ConnectionsTab.DISCONNECTED -> R.string.dash_connect_tab_empty_disconnected
        ConnectionsTab.EXPIRED -> R.string.dash_connect_tab_empty_expired
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
    ConnectionsScreenContent(uiState = ConnectionsUIState(recordsStatus = ConnectionRecordsStatus.READY))
}

@Composable
@Preview
private fun ConnectionsScreenApprovedPreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            recordsStatus = ConnectionRecordsStatus.READY,
            connections = listOf(previewConnection(ConnectionStatus.APPROVED))
        )
    )
}

@Composable
@Preview
private fun ConnectionsScreenActivePreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            recordsStatus = ConnectionRecordsStatus.READY,
            connections = listOf(previewConnection(ConnectionStatus.ACTIVE))
        )
    )
}

@Composable
@Preview
private fun ConnectionsScreenDisconnectedPreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            recordsStatus = ConnectionRecordsStatus.READY,
            connections = listOf(previewConnection(ConnectionStatus.DISCONNECTED))
        )
    )
}

@Composable
@Preview
private fun ConnectionsScreenExpiredTabPreview() {
    ConnectionsScreenContent(
        uiState = ConnectionsUIState(
            recordsStatus = ConnectionRecordsStatus.READY,
            connections = listOf(previewConnection(ConnectionStatus.ACTIVE)),
            selectedTab = ConnectionsTab.EXPIRED
        )
    )
}

@Composable
@Preview(showBackground = true)
private fun ConnectionListItemPreview() {
    Menu {
        ConnectionListItem(connection = previewConnection(ConnectionStatus.ACTIVE), onClick = {})
    }
}
