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

package de.schildbach.wallet.ui.compose_views

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import de.schildbach.wallet.ui.more.connections.AppCapability
import de.schildbach.wallet.ui.more.connections.AppCapabilityKind
import de.schildbach.wallet.ui.more.connections.ConnectionDetailsState
import de.schildbach.wallet.ui.more.connections.ConnectionRecordsStatus
import de.schildbach.wallet.ui.more.connections.ConnectionStatus
import de.schildbach.wallet.ui.more.connections.ConnectionTerms
import de.schildbach.wallet.ui.more.connections.ConnectionsFragment
import de.schildbach.wallet.ui.more.connections.ConnectionsViewModel
import de.schildbach.wallet.ui.more.connections.DAppConnection
import de.schildbach.wallet.ui.more.connections.formatDash
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import java.math.BigInteger
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

/**
 * Details of an existing connection, opened from the Connections list. The parent fragment's
 * ViewModel owns which connection is shown, so the sheet restores like the approve sheet.
 *
 * Figma Node IDs: 40060:40438 (active), 40060:40645 (disconnected), 40060:40785 (expired)
 */
class ConnectionDetailsDialog : OffsetDialogFragment(R.layout.dialog_compose_container) {
    override val backgroundStyle = R.style.SecondaryBackground
    override val forceExpand = true
    private val viewModel: ConnectionsViewModel by viewModels({ requireParentFragment() })

    private companion object {
        const val INFO_SHEET = "connection_details_info_sheet"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(org.dash.wallet.common.R.id.collapse_button)?.setOnClickListener {
            viewModel.closeConnection()
        }
        DialogComposeContainerBinding.bind(view).composeContainer.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val marginTop = dimensionResource(org.dash.wallet.common.R.dimen.offset_dialog_margin_top)
                    BoxWithConstraints(Modifier.navigationBarsPadding()) {
                        DetailsContent(Modifier.heightIn(max = maxHeight - marginTop))
                    }
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        viewModel.closeConnection()
        super.onCancel(dialog)
    }

    override fun onStart() {
        super.onStart()
        if (!viewModel.isWalletUnlocked) {
            dialog?.hide()
            dismissAllowingStateLoss()
        }
    }

    private fun showInfo(info: ConnectInfo) {
        if (childFragmentManager.findFragmentByTag(INFO_SHEET) == null) {
            ConnectInfoDialog.newInstance(info).show(childFragmentManager, INFO_SHEET)
        }
    }

    @Composable
    private fun DetailsContent(modifier: Modifier) {
        val uiState by viewModel.uiState.collectAsState()
        val details = uiState.selectedConnection
        val connection = details?.let { selected -> uiState.connections.firstOrNull { it.id == selected.connectionId } }
        // A reload (e.g. on resume) empties the list while it loads, so absence proves nothing until it finishes.
        val reloading = uiState.recordsStatus == ConnectionRecordsStatus.LOADING

        // Closed elsewhere, or a finished read no longer has the connection (e.g. removed): nothing to show.
        LaunchedEffect(details == null, connection == null, reloading) {
            if (details == null || (connection == null && !reloading)) {
                viewModel.closeConnection()
                dismissAllowingStateLoss()
            }
        }
        if (details == null) return
        if (connection == null) {
            if (reloading) {
                Box(modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalDashColors.current.dashBlue)
                }
            }
            return
        }

        ConnectionDetailsContent(
            connection = connection,
            details = details,
            modifier = modifier,
            onFundingLimitInfo = { showInfo(ConnectInfo.FUNDING_LIMIT) },
            onSpendableBalanceInfo = { showInfo(ConnectInfo.SPENDABLE_BALANCE) },
            onDisconnect = {
                if (viewModel.isWalletUnlocked) {
                    if (connection.legacyOnly) viewModel.removeConnection(connection) else viewModel.disconnect(connection)
                    viewModel.closeConnection()
                }
            },
            onReconnect = {
                viewModel.closeConnection()
                (parentFragment as? ConnectionsFragment)?.launchScanner()
            }
        )
    }
}

@Composable
internal fun ConnectionDetailsContent(
    connection: DAppConnection,
    details: ConnectionDetailsState,
    modifier: Modifier = Modifier,
    onFundingLimitInfo: () -> Unit = {},
    onSpendableBalanceInfo: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onReconnect: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    val appName = connection.name
    // Pending connections may still hold live keys, so they read like active ones.
    val current = connection.status == ConnectionStatus.ACTIVE || connection.status == ConnectionStatus.APPROVED
    Column(modifier = modifier.fillMaxWidth().padding(top = 30.dp)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                // Lets the View-based bottom sheet see this scroll, so dragging down scrolls the
                // content back up instead of dismissing the sheet.
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = appName,
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
                if (connection.url.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(painterResource(R.drawable.ic_connect_web), contentDescription = null)
                        Text(
                            text = connection.url,
                            style = MyTheme.Typography.SubheadMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
                            color = colors.dashBlue
                        )
                    }
                }
            }

            StatusCard(connection.status)

            ConnectCard(filled = true) {
                DetailRow(stringResource(R.string.dash_connect_app_id), connection.id)
                if (details.username.isNotBlank()) {
                    DetailRow(stringResource(R.string.dash_connect_username), details.username)
                }
                if (details.identityId.isNotBlank()) {
                    DetailRow(stringResource(R.string.dash_connect_identity), truncateMiddle(details.identityId))
                }
            }

            // Removing an older connection only forgets it here; its Platform access, if any, stays.
            if (connection.legacyOnly) {
                Text(
                    text = stringResource(R.string.dash_connect_legacy_remove_warning),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.textSecondary
                )
            }

            when {
                details.isLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.dashBlue)
                }
                details.error != null -> Text(
                    text = stringResource(R.string.dash_connect_error, details.error),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.red
                )
                else -> details.terms?.let { terms ->
                    ConnectCard {
                        LimitRow(
                            label = stringResource(R.string.dash_connect_funding_limit),
                            value = terms.fundingLimit?.let {
                                stringResource(R.string.dash_connect_funding_limit_value, formatDash(it))
                            } ?: stringResource(R.string.dash_connect_funding_limit_unlimited),
                            onInfo = onFundingLimitInfo
                        )
                        // A disconnected connection ended when its keys were revoked, before they expired.
                        val ended = if (connection.status == ConnectionStatus.DISCONNECTED) {
                            R.string.dash_connect_disconnected_on to connection.updatedAt.takeIf { it > 0 }
                        } else {
                            (if (current) R.string.dash_connect_expires else R.string.dash_connect_expired_on) to
                                terms.expiresAt
                        }
                        ended.second?.let {
                            LimitRow(label = stringResource(ended.first), value = formatDate(it))
                        }
                    }
                    CollapsibleCard(
                        stringResource(
                            if (current) R.string.dash_connect_able_to_do else R.string.dash_connect_was_able_to_do,
                            appName
                        ),
                        initiallyExpanded = false
                    ) {
                        PermissionRow(
                            R.drawable.ic_connections_check_circle,
                            stringResource(R.string.dash_connect_approve_see_username)
                        )
                        terms.capabilities.forEach { capability ->
                            PermissionRow(R.drawable.ic_connections_check_circle, capabilityText(capability, appName))
                        }
                    }
                    CollapsibleCard(
                        stringResource(
                            if (current) R.string.dash_connect_not_access else R.string.dash_connect_was_not_access,
                            appName
                        ),
                        filled = true,
                        initiallyExpanded = false
                    ) {
                        PermissionRow(
                            R.drawable.ic_connections_xmark_circle,
                            stringResource(R.string.dash_connect_no_spendable_balance),
                            onInfo = onSpendableBalanceInfo
                        )
                        PermissionRow(
                            R.drawable.ic_connections_xmark_circle,
                            stringResource(R.string.dash_connect_no_recovery_phrase)
                        )
                    }
                }
            }
        }

        // Expired keys stay enabled on Platform until revoked, so Expired offers Disconnect too.
        // Reconnecting needs a new login request from the app, so it opens the scanner.
        val button = when {
            connection.legacyOnly -> SheetButton(
                text = stringResource(R.string.dash_connect_remove_local),
                style = Style.TintedRed,
                isEnabled = !details.isLoading,
                onClick = onDisconnect
            )
            connection.status == ConnectionStatus.DISCONNECTED -> SheetButton(
                text = stringResource(R.string.dash_connect_reconnect),
                style = Style.FilledBlue,
                onClick = onReconnect
            )
            else -> SheetButton(
                text = stringResource(R.string.dash_connect_disconnect_action),
                style = Style.TintedRed,
                isEnabled = !details.isLoading,
                onClick = onDisconnect
            )
        }
        SheetButtonGroup(primaryButton = button, horizontalPadding = 40.dp, spacing = 10.dp)
    }
}

/** Figma "List - Merchant details - Var 7": the status with its colored dot, green-tinted when active. */
@Composable
private fun StatusCard(status: ConnectionStatus) {
    val colors = LocalDashColors.current
    val active = status == ConnectionStatus.ACTIVE
    ConnectCard(filled = true, tint = if (active) colors.green.copy(alpha = 0.05f) else null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.dash_connect_status),
                style = MyTheme.Typography.LabelMedium,
                color = colors.textSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (active) colors.green else colors.lightGray, CircleShape)
                )
                Text(
                    text = stringResource(
                        when (status) {
                            ConnectionStatus.ACTIVE -> R.string.dash_connect_status_active
                            ConnectionStatus.APPROVED -> R.string.dash_connect_status_pending
                            ConnectionStatus.DISCONNECTED -> R.string.dash_connect_status_disconnected
                            ConnectionStatus.EXPIRED -> R.string.dash_connect_status_expired
                        }
                    ),
                    style = MyTheme.Typography.BodyLarge,
                    color = colors.textPrimary
                )
            }
        }
    }
}

private val previewConnection = DAppConnection(
    id = "D8benQGnycUTXbT8GRAaYf3GZMKVcWESiF9NyyW1Bi4e",
    name = "Yappr",
    url = "yap.pr",
    status = ConnectionStatus.ACTIVE,
    updatedAt = 0L
)

private val previewDetails = ConnectionDetailsState(
    connectionId = previewConnection.id,
    username = "test-connect-v12-1",
    identityId = "8tkTPCF36QsC4GwszmJ7z4T2Ay4C9pEAgQLm7nuz8eoP",
    terms = ConnectionTerms(
        capabilities = listOf(
            AppCapability(AppCapabilityKind.ACT_IN_APP, tokens = true, budget = BigInteger("50000000000"),
                expiresAt = 1_824_854_400_000L),
            AppCapability(AppCapabilityKind.RECEIVE_ENCRYPTED)
        ),
        fundingLimit = BigInteger("50000000000"),
        expiresAt = 1_824_854_400_000L
    ),
    isLoading = false
)

@Preview(showBackground = true, widthDp = 420, heightDp = 940)
@Composable
private fun ConnectionDetailsActivePreview() {
    DashWalletTheme { ConnectionDetailsContent(previewConnection, previewDetails) }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 940)
@Composable
private fun ConnectionDetailsExpiredPreview() {
    DashWalletTheme {
        ConnectionDetailsContent(previewConnection.copy(status = ConnectionStatus.EXPIRED), previewDetails)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 940)
@Composable
private fun ConnectionDetailsDisconnectedPreview() {
    DashWalletTheme {
        ConnectionDetailsContent(previewConnection.copy(status = ConnectionStatus.DISCONNECTED), previewDetails)
    }
}
