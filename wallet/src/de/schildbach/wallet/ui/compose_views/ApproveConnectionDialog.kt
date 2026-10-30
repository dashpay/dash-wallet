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
import android.text.format.DateUtils
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.viewModels
import de.schildbach.wallet.ui.more.connections.AppCapability
import de.schildbach.wallet.ui.more.connections.AppCapabilityKind
import de.schildbach.wallet.ui.more.connections.ConnectionRequest
import de.schildbach.wallet.ui.more.connections.ConnectionsViewModel
import de.schildbach.wallet.ui.more.connections.formatDash
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.SystemMessage
import org.dash.wallet.common.ui.components.SystemMessageStyle
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

/**
 * Restorable approval sheet; the parent fragment owns the pending request and operation.
 *
 * Figma Node ID: 40033:49941
 */
class ApproveConnectionDialog : OffsetDialogFragment(R.layout.dialog_compose_container) {
    override val backgroundStyle = R.style.SecondaryBackground
    override val forceExpand = true
    private val viewModel: ConnectionsViewModel by viewModels({ requireParentFragment() })

    private companion object {
        const val CHILD_SHEET = "approve_connection_child_sheet"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = !viewModel.uiState.value.requiresRevocation &&
            viewModel.approveResult.value !is ConnectionsViewModel.ApproveResult.Loading
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(org.dash.wallet.common.R.id.collapse_button)?.setOnClickListener {
            viewModel.closePendingRequest()
        }
        DialogComposeContainerBinding.bind(view).composeContainer.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    // The expanded sheet is full height but offset by the top margin, so only the
                    // rest is visible; cap the content there to keep the buttons on screen.
                    val marginTop = dimensionResource(org.dash.wallet.common.R.dimen.offset_dialog_margin_top)
                    BoxWithConstraints(Modifier.navigationBarsPadding()) {
                        ApprovalContent(Modifier.heightIn(max = maxHeight - marginTop))
                    }
                }
            }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        viewModel.denyPendingRequest()
        super.onCancel(dialog)
    }

    override fun onStart() {
        super.onStart()
        if (!viewModel.isWalletUnlocked) {
            dialog?.hide()
            dismissAllowingStateLoss()
        }
    }

    private fun showSheet(sheet: androidx.fragment.app.DialogFragment) {
        if (childFragmentManager.findFragmentByTag(CHILD_SHEET) == null) {
            sheet.show(childFragmentManager, CHILD_SHEET)
        }
    }

    @Composable
    private fun ApprovalContent(modifier: Modifier) {
        val uiState by viewModel.uiState.collectAsState()
        val approveResult by viewModel.approveResult.collectAsState()
        val isLoading = approveResult is ConnectionsViewModel.ApproveResult.Loading

        LaunchedEffect(isLoading, uiState.requiresRevocation, uiState.pendingRequest) {
            isCancelable = !isLoading && !uiState.requiresRevocation
            dialog?.setCanceledOnTouchOutside(isCancelable)
            view?.findViewById<View>(org.dash.wallet.common.R.id.collapse_button)?.isEnabled = !isLoading
            if (uiState.pendingRequest == null) dismissAllowingStateLoss()
        }

        uiState.pendingRequest?.let { request ->
            ApproveConnectionContent(
                request = request,
                modifier = modifier,
                isLoading = isLoading,
                requiresRevocation = uiState.requiresRevocation,
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
                    }
                },
                onGroupScopeChanged = viewModel::approveGroupScopeChange,
                onChangeFundingLimit = { showSheet(ConnectLimitPickerDialog.newInstance(ConnectLimit.FUNDING)) },
                onChangeExpiration = { showSheet(ConnectLimitPickerDialog.newInstance(ConnectLimit.EXPIRATION)) },
                onFundingLimitInfo = { showSheet(ConnectInfoDialog.newInstance(ConnectInfo.FUNDING_LIMIT)) },
                onSpendableBalanceInfo = { showSheet(ConnectInfoDialog.newInstance(ConnectInfo.SPENDABLE_BALANCE)) }
            )
        }
    }
}

/**
 * The Compose content for the approve-connection bottom sheet.
 *
 * Figma Node ID: 40033:49941
 */
@Composable
internal fun ApproveConnectionContent(
    request: ConnectionRequest,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    requiresRevocation: Boolean = false,
    /** Shown above the buttons when the approval failed; null while idle or in progress. */
    errorMessage: String? = null,
    onApprove: () -> Unit = {},
    onDeny: () -> Unit = {},
    onGroupScopeChanged: (Boolean) -> Unit = {},
    onChangeFundingLimit: () -> Unit = {},
    onChangeExpiration: () -> Unit = {},
    onFundingLimitInfo: () -> Unit = {},
    onSpendableBalanceInfo: () -> Unit = {}
) {
    val colors = LocalDashColors.current
    // The name/url are resolved from Platform (or known branding) where possible; the raw
    // QR label is an UNAUTHENTICATED fallback (e.g. "Login to Yappr").
    val appName = request.appLabel.ifBlank { stringResource(R.string.dash_connect_unknown_app) }
    val limitsEditable = !isLoading && !request.recovering
    // The top spacer stays outside the scroll so content never slides under the drag indicator.
    Column(modifier = modifier.fillMaxWidth().padding(top = 30.dp)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                // Lets the View-based bottom sheet see this scroll, so dragging down scrolls the
                // terms back up instead of dismissing the sheet (which denies the request).
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
                    text = stringResource(R.string.dash_connect_approve_title, appName),
                    style = MyTheme.Typography.HeadlineMediumBold,
                    color = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
                // Only a resolved website is shown; the unverified QR label is already the title.
                if (request.appUrl.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(painterResource(R.drawable.ic_connect_web), contentDescription = null)
                        Text(
                            text = request.appUrl,
                            style = MyTheme.Typography.SubheadMedium.copy(fontSize = 17.sp, lineHeight = 22.sp),
                            color = colors.dashBlue
                        )
                    }
                }
            }

            if (request.firstConnection) {
                SystemMessage(
                    title = stringResource(R.string.dash_connect_first_connection_warning, appName),
                    style = SystemMessageStyle.Yellow,
                    icon = { Image(painterResource(R.drawable.ic_connect_warning), contentDescription = null) }
                )
            }

            ConnectCard(filled = true) {
                if (request.appContractId.isNotBlank()) {
                    DetailRow(stringResource(R.string.dash_connect_app_id), request.appContractId)
                }
                if (request.requestedAt > 0) {
                    DetailRow(
                        stringResource(R.string.dash_connect_requested),
                        DateUtils.getRelativeTimeSpanString(
                            request.requestedAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                        ).toString()
                    )
                }
                DetailRow(stringResource(R.string.dash_connect_username), request.walletUsername)
                DetailRow(stringResource(R.string.dash_connect_identity), truncateMiddle(request.walletIdentityId))
            }

            if (request.capabilities.isNotEmpty()) {
                ConnectCard {
                    LimitRow(
                        label = stringResource(R.string.dash_connect_funding_limit),
                        value = request.fundingLimit?.let {
                            stringResource(R.string.dash_connect_funding_limit_value, formatDash(it))
                        } ?: stringResource(R.string.dash_connect_funding_limit_unlimited),
                        enabled = limitsEditable,
                        onInfo = onFundingLimitInfo,
                        onChange = onChangeFundingLimit
                    )
                    LimitRow(
                        label = stringResource(R.string.dash_connect_expires),
                        value = request.expiresAt?.let { formatDate(it) }.orEmpty(),
                        enabled = limitsEditable,
                        onChange = onChangeExpiration
                    )
                }
            }

            CollapsibleCard(stringResource(R.string.dash_connect_able_to_do, appName)) {
                PermissionRow(R.drawable.ic_connections_check_circle, stringResource(R.string.dash_connect_approve_see_username))
                if (request.capabilities.isEmpty()) {
                    PermissionRow(
                        R.drawable.ic_connections_check_circle,
                        stringResource(R.string.dash_connect_approve_verify_identity)
                    )
                }
                request.capabilities.forEach { capability ->
                    PermissionRow(R.drawable.ic_connections_check_circle, capabilityText(capability, appName))
                }
            }

            CollapsibleCard(stringResource(R.string.dash_connect_not_access, appName), filled = true) {
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

            if (request.requiresGroupReapproval) {
                Text(
                    stringResource(R.string.dash_connect_group_scope_changed),
                    style = MyTheme.Typography.BodyMedium,
                    color = colors.red
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = request.groupScopeChangeApproved,
                        onCheckedChange = onGroupScopeChanged,
                        enabled = !isLoading
                    )
                    Text(
                        stringResource(R.string.dash_connect_group_scope_approve),
                        style = MyTheme.Typography.BodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
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

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.dash_connect_approve),
                style = Style.FilledBlue,
                isEnabled = !isLoading && (!request.requiresGroupReapproval || request.groupScopeChangeApproved),
                isLoading = isLoading,
                onClick = onApprove
            ),
            secondaryButton = SheetButton(
                text = stringResource(if (requiresRevocation) R.string.dash_connect_revoke_access else R.string.dash_connect_deny),
                style = Style.TintedGray,
                isEnabled = !isLoading,
                onClick = onDeny
            ),
            horizontalPadding = 40.dp,
            spacing = 10.dp
        )
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

@Preview(showBackground = true, widthDp = 420, heightDp = 1700)
@Composable
internal fun AppConnectApprovalPreview() {
    DashWalletTheme {
        ApproveConnectionContent(
            previewRequest.copy(
                permissionText = "App ID: Hj6B9qRVHcVB97LZGEQvKm7zFsCP85cuSkEAssc1XWL7",
                requestedAt = System.currentTimeMillis() - 14_000,
                firstConnection = true,
                fundingLimit = BigInteger("50000000000"),
                expiresAt = System.currentTimeMillis() + 365 * 86_400_000L,
                capabilities = listOf(
                    AppCapability(AppCapabilityKind.ACT_IN_APP, tokens = true, budget = BigInteger("50000000000"),
                        expiresAt = System.currentTimeMillis() + 365 * 86_400_000L),
                    AppCapability(AppCapabilityKind.EDIT_DASHPAY_PROFILE, budget = BigInteger("50000000000"),
                        expiresAt = System.currentTimeMillis() + 365 * 86_400_000L),
                    AppCapability(AppCapabilityKind.RECEIVE_ENCRYPTED)
                )
            )
        )
    }
}
