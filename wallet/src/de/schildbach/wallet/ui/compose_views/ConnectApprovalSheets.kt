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

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import de.schildbach.wallet.ui.more.connections.AppConnectPresets
import de.schildbach.wallet.ui.more.connections.ConnectionsViewModel
import de.schildbach.wallet.ui.more.connections.formatDash
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

enum class ConnectLimit { FUNDING, EXPIRATION }

/**
 * Picks the identity funding limit or the expiration period for the pending approval. Shown from
 * [ApproveConnectionDialog], whose parent fragment owns the request, so it restores like the sheet.
 *
 * Figma Node IDs: 40059:39680 (funding limits), 40060:40019 (expiration)
 */
class ConnectLimitPickerDialog : OffsetDialogFragment(R.layout.dialog_compose_container) {
    companion object {
        private const val ARG_LIMIT = "limit"

        fun newInstance(limit: ConnectLimit) = ConnectLimitPickerDialog().apply {
            arguments = bundleOf(ARG_LIMIT to limit.name)
        }
    }

    override val backgroundStyle = R.style.PrimaryBackground
    private val viewModel: ConnectionsViewModel by viewModels({ requireParentFragment().requireParentFragment() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val limit = ConnectLimit.valueOf(requireArguments().getString(ARG_LIMIT)!!)
        DialogComposeContainerBinding.bind(view).composeContainer.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    val request = uiState.pendingRequest
                    LaunchedEffect(request == null || request.recovering) {
                        if (request == null || request.recovering) dismissAllowingStateLoss()
                    }
                    request ?: return@DashWalletTheme
                    Box(Modifier.navigationBarsPadding()) {
                        if (limit == ConnectLimit.FUNDING) {
                            ConnectLimitPickerContent(
                                title = stringResource(R.string.dash_connect_funding_limits_title),
                                options = AppConnectPresets.budgets.map { formatDash(it) },
                                showDashIcon = true,
                                selected = request.budgetPreset
                            ) { select(it, request.lifetimePreset) }
                        } else {
                            ConnectLimitPickerContent(
                                title = stringResource(R.string.dash_connect_expiration_title),
                                options = AppConnectPresets.lifetimeDays.map {
                                    stringResource(R.string.dash_connect_days, it.toInt())
                                },
                                showDashIcon = false,
                                selected = request.lifetimePreset
                            ) { select(request.budgetPreset, it) }
                        }
                    }
                }
            }
        }
    }

    private fun select(budget: Int, lifetime: Int) {
        viewModel.selectLimits(budget, lifetime)
        dismiss()
    }
}

@Composable
internal fun ConnectLimitPickerContent(
    title: String,
    options: List<String>,
    showDashIcon: Boolean,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 40.dp)
    ) {
        // Leaves room for the close button the container layout draws at the top end.
        Text(
            text = title,
            style = MyTheme.Typography.SubheadMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 70.dp, vertical = 10.dp)
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.backgroundSecondary)
                .padding(6.dp)
                .selectableGroup()
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selected
                val textColor = if (isSelected) colors.dashBlue else colors.textPrimary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(option, style = MyTheme.Typography.BodyLarge, color = textColor)
                    if (showDashIcon) {
                        Icon(
                            painter = painterResource(R.drawable.ic_dash_d_black),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Box(Modifier.weight(1f))
                    RadioIndicator(isSelected)
                }
            }
        }
    }
}

/** Matches the indicator drawn by the common DashRadioButton. */
@Composable
private fun RadioIndicator(selected: Boolean) {
    val colors = LocalDashColors.current
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(if (selected) 6.dp else 1.5.dp, if (selected) colors.dashBlue else colors.lightGray, CircleShape)
    )
}

enum class ConnectInfo(val title: Int, val message: Int) {
    FUNDING_LIMIT(R.string.dash_connect_funding_info_title, R.string.dash_connect_funding_info_message),
    SPENDABLE_BALANCE(R.string.dash_connect_spendable_info_title, R.string.dash_connect_spendable_info_message)
}

/**
 * Explains a term used on the approve sheet.
 *
 * Figma Node IDs: 40061:59617 (identity funding limits), 40062:59666 (spendable Dash)
 */
class ConnectInfoDialog : ComposeBottomSheet() {
    companion object {
        private const val ARG_INFO = "info"

        fun newInstance(info: ConnectInfo) = ConnectInfoDialog().apply {
            arguments = bundleOf(ARG_INFO to info.name)
        }
    }

    @Composable
    override fun Content() {
        ConnectInfoContent(ConnectInfo.valueOf(requireArguments().getString(ARG_INFO)!!)) { dismiss() }
    }
}

@Composable
internal fun ConnectInfoContent(info: ConnectInfo, onDismiss: () -> Unit) {
    val colors = LocalDashColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 30.dp, bottom = 40.dp)
    ) {
        Text(
            text = stringResource(info.title),
            style = MyTheme.Typography.HeadlineMediumBold,
            color = colors.textPrimary
        )
        Text(
            text = stringResource(info.message),
            style = MyTheme.Typography.BodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 6.dp, bottom = 40.dp)
        )
        DashButton(
            text = stringResource(R.string.dash_connect_got_it),
            style = Style.TintedGray,
            onClick = onDismiss
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ConnectLimitPickerPreview() {
    DashWalletTheme {
        Box(Modifier.background(LocalDashColors.current.backgroundPrimary)) {
            ConnectLimitPickerContent("Identity funding limits", listOf("0.1", "0.5", "2", "5"), true, 1) {}
        }
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ConnectInfoPreview() {
    DashWalletTheme {
        ConnectInfoContent(ConnectInfo.FUNDING_LIMIT) {}
    }
}
