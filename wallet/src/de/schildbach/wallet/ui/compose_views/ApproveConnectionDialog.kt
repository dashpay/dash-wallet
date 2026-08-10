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

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet.ui.more.connections.ConnectionRequest
import de.schildbach.wallet.ui.more.connections.ConnectionsViewModel
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style

/**
 * Creates the "Approve connection to …?" bottom sheet dialog (MO-945).
 *
 * Figma Node ID: 5799:51156
 */
fun createApproveConnectionDialog(
    viewModel: ConnectionsViewModel,
    onDismiss: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = true
    ) { dialog ->
        val uiState by viewModel.uiState.collectAsState()
        val approveResult by viewModel.approveResult.collectAsState()
        val isLoading = approveResult is ConnectionsViewModel.ApproveResult.Loading

        DisposableEffect(Unit) {
            onDispose {
                // Clear any error so a later scan doesn't open the sheet showing a stale failure.
                viewModel.resetApproveResult()
                onDismiss()
            }
        }

        LaunchedEffect(approveResult) {
            when (approveResult) {
                is ConnectionsViewModel.ApproveResult.Loading -> {
                    dialog.dialog?.setCancelable(false)
                    dialog.dialog?.setCanceledOnTouchOutside(false)
                }
                is ConnectionsViewModel.ApproveResult.Success -> {
                    dialog.dismiss()
                    viewModel.resetApproveResult()
                }
                is ConnectionsViewModel.ApproveResult.Error -> {
                    // Re-enable interaction but KEEP the result: it carries the message shown
                    // below. Retrying moves it to Loading, and dismissing clears it above —
                    // resetting here would blank the reason before the user could read it.
                    dialog.dialog?.setCancelable(true)
                    dialog.dialog?.setCanceledOnTouchOutside(true)
                }
                is ConnectionsViewModel.ApproveResult.Idle -> Unit
            }
        }

        uiState.pendingRequest?.let { request ->
            ApproveConnectionContent(
                request = request,
                isLoading = isLoading,
                errorMessage = (approveResult as? ConnectionsViewModel.ApproveResult.Error)?.let {
                    stringResource(R.string.dash_connect_error, it.message ?: "")
                },
                onApprove = {
                    if (!isLoading) {
                        viewModel.approvePendingRequest()
                    }
                },
                onDeny = {
                    if (!isLoading) {
                        viewModel.denyPendingRequest()
                        dialog.dismiss()
                    }
                }
            )
        }
    }
}

/**
 * The Compose content for the approve-connection bottom sheet.
 *
 * Figma Node ID: 5799:51156
 */
@Composable
internal fun ApproveConnectionContent(
    request: ConnectionRequest,
    isLoading: Boolean = false,
    /** Shown above the buttons when the approval failed; null while idle or in progress. */
    errorMessage: String? = null,
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp) // space for drag indicator and close button
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 60.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Heading and app website — left-aligned per Figma 5799:51163 / subtitle 5799:51165.
            // The name/url are resolved from Platform (or known branding) where possible; the raw
            // QR label is an UNAUTHENTICATED fallback (e.g. "Login to Yappr").
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.dash_connect_approve_title,
                        request.appLabel.ifBlank { stringResource(R.string.dash_connect_unknown_app) }
                    ),
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = request.appUrl.ifBlank {
                        request.appLabel.ifBlank { stringResource(R.string.dash_connect_unknown_app) }
                    },
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // "This app will be able to:"
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.dash_connect_approve_will_be_able),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.textPrimary
                )
                PermissionRow(
                    icon = R.drawable.ic_connections_check_circle,
                    text = stringResource(R.string.dash_connect_approve_see_username)
                )
                PermissionRow(
                    icon = R.drawable.ic_connections_check_circle,
                    text = stringResource(R.string.dash_connect_approve_verify_identity)
                )
            }

            // Username / Identity details
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = colors.extraLightGray,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DetailRow(
                    label = stringResource(R.string.dash_connect_username),
                    value = request.walletUsername
                )
                DetailRow(
                    label = stringResource(R.string.dash_connect_identity),
                    value = truncateMiddle(request.walletIdentityId)
                )
            }

            // "This app will NOT have access to:"
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.dash_connect_approve_no_access),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.textPrimary
                )
                PermissionRow(
                    icon = R.drawable.ic_connections_xmark_circle,
                    text = stringResource(R.string.dash_connect_approve_no_private_keys)
                )
                PermissionRow(
                    icon = R.drawable.ic_connections_xmark_circle,
                    text = stringResource(R.string.dash_connect_approve_no_withdraw_funds)
                )
            }

            // Why the approval failed. Without this the sheet just becomes interactive again
            // and the user has no idea what went wrong.
            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.red,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.dash_connect_approve),
                style = Style.FilledBlue,
                isEnabled = !isLoading,
                isLoading = isLoading,
                onClick = onApprove
            ),
            secondaryButton = SheetButton(
                text = stringResource(R.string.dash_connect_deny),
                style = Style.TintedGray,
                isEnabled = !isLoading,
                onClick = onDeny
            ),
            horizontalPadding = 60.dp,
            spacing = 20.dp
        )
    }
}

@Composable
private fun PermissionRow(
    @DrawableRes icon: Int,
    text: String
) {
    val colors = LocalDashColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MyTheme.Typography.BodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String
) {
    val colors = LocalDashColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            style = MyTheme.Typography.BodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MyTheme.Typography.BodyMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Truncates a long identifier in the middle, keeping [prefix] leading and [suffix] trailing
 * characters (e.g. "5DbLwAx…7zUo8"). Short strings are returned unchanged.
 */
private fun truncateMiddle(value: String, prefix: Int = 7, suffix: Int = 5): String {
    return if (value.length <= prefix + suffix + 1) {
        value
    } else {
        "${value.take(prefix)}…${value.takeLast(suffix)}"
    }
}

private val previewRequest = ConnectionRequest(
    appLabel = "Yappr",
    appUrl = "yap.pr",
    appContractId = "EWR695MsqPUuW8EnTbYzD4KybNQD5n7CUDWydJYNg63F",
    walletUsername = "john.doe",
    walletIdentityId = "5DbLwAxEWR695MsqP4KybNQD5n7CUDWydJYNg63FzUo8"
)

@Preview(showBackground = true)
@Composable
private fun ApproveConnectionContentPreview() {
    ApproveConnectionContent(request = previewRequest)
}

@Preview(showBackground = true)
@Composable
private fun ApproveConnectionContentLoadingPreview() {
    ApproveConnectionContent(request = previewRequest, isLoading = true)
}

@Preview(showBackground = true)
@Composable
private fun ApproveConnectionContentErrorPreview() {
    ApproveConnectionContent(
        request = previewRequest,
        errorMessage = "Could not complete the DashConnect request: state transition was not accepted"
    )
}
