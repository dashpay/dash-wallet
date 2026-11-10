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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.app.DatePickerDialog
import de.schildbach.wallet.ui.more.connections.AppConnectLimits
import java.math.BigInteger
import java.text.DecimalFormatSymbols
import java.util.Calendar
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.Size
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardCompose
import org.dash.wallet.common.ui.enter_amount.processAmountKeyInput
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
                                selected = request.budgetPreset,
                                customLabel = stringResource(R.string.dash_connect_custom_limit),
                                onCustom = ::showCustomLimit
                            ) {
                                viewModel.selectFundingLimit(AppConnectPresets.budgets[it])
                                dismiss()
                            }
                        } else {
                            ConnectLimitPickerContent(
                                title = stringResource(R.string.dash_connect_expiration_title),
                                options = AppConnectPresets.lifetimeDays.map {
                                    stringResource(R.string.dash_connect_days, it.toInt())
                                },
                                showDashIcon = false,
                                selected = request.lifetimePreset,
                                customLabel = stringResource(R.string.dash_connect_set_date),
                                onCustom = { showDatePicker(request.blockTime, request.expiresAt) }
                            ) {
                                viewModel.selectExpiry(request.blockTime + AppConnectPresets.lifetimeDays[it] * AppConnectLimits.DAY)
                                dismiss()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Opens the keypad sheet beside this one, under the approve sheet, then closes this picker. */
    private fun showCustomLimit() {
        if (parentFragmentManager.findFragmentByTag(ConnectCustomLimitDialog.TAG) == null) {
            ConnectCustomLimitDialog().show(parentFragmentManager, ConnectCustomLimitDialog.TAG)
        }
        dismiss()
    }

    /** Any day from tomorrow to a year after the request; the connection expires at the end of that day. */
    private fun showDatePicker(blockTime: Long, current: Long?) {
        val latest = blockTime + AppConnectLimits.MAX_LIFETIME
        val initial = Calendar.getInstance().apply { timeInMillis = current ?: latest }
        DatePickerDialog(requireContext(), { _, year, month, day ->
            val endOfDay = Calendar.getInstance().apply {
                set(year, month, day, 23, 59, 59)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            viewModel.selectExpiry(minOf(endOfDay, latest))
            dismiss()
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = blockTime + AppConnectLimits.DAY
            datePicker.maxDate = latest
        }.show()
    }
}

/**
 * Enters a custom funding limit for the pending approval, above 0 and at most 5 DASH.
 *
 * Figma Node IDs: 40103:38451 (empty), 40103:38643 (entered), 40103:38713 (above the maximum)
 */
class ConnectCustomLimitDialog : OffsetDialogFragment(R.layout.dialog_compose_container) {
    companion object {
        const val TAG = "connect_custom_limit"
    }

    override val backgroundStyle = R.style.SecondaryBackground
    private val viewModel: ConnectionsViewModel by viewModels({ requireParentFragment().requireParentFragment() })

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
                    var amount by rememberSaveable { mutableStateOf("0") }
                    Box(Modifier.navigationBarsPadding()) {
                        ConnectCustomLimitContent(amount, onKey = { amount = processAmountKeyInput(amount, it, 8) }) { credits ->
                            viewModel.selectFundingLimit(credits)
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}

/** The entered amount in credits, or null when it is not a number. */
private fun creditsOf(amount: String): BigInteger? =
    amount.toBigDecimalOrNull()?.movePointRight(11)?.toBigInteger()

@Composable
internal fun ConnectCustomLimitContent(amount: String, onKey: (String) -> Unit, onSubmit: (BigInteger) -> Unit) {
    val colors = LocalDashColors.current
    val credits = creditsOf(amount)
    val overMax = credits != null && credits > AppConnectLimits.MAX_BUDGET
    val valid = credits != null && credits.signum() > 0 && !overMax
    val amountColor = if (overMax) colors.red else colors.textPrimary
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.dash_connect_custom_limit_title),
            style = MyTheme.Typography.SubheadMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 70.dp, vertical = 10.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp)
                .heightIn(min = 92.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(amount, style = MyTheme.Typography.HeadlineLargeMedium, color = amountColor)
                Icon(
                    painter = painterResource(R.drawable.ic_dash_d_black),
                    contentDescription = null,
                    tint = amountColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (overMax) {
                Text(
                    text = stringResource(R.string.dash_connect_custom_limit_max, formatDash(AppConnectLimits.MAX_BUDGET)),
                    style = MyTheme.Typography.Footnote,
                    color = colors.red,
                    textAlign = TextAlign.Center
                )
            }
        }
        NumericKeyboardCompose(
            decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator,
            bottomSlot = {
                DashButton(
                    text = stringResource(R.string.dash_connect_custom_limit_submit),
                    style = Style.FilledBlue,
                    size = Size.Large,
                    isEnabled = valid,
                    onClick = { credits?.takeIf { valid }?.let(onSubmit) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, end = 40.dp, top = 20.dp)
                )
            },
            onKeyInput = onKey
        )
    }
}

@Composable
internal fun ConnectLimitPickerContent(
    title: String,
    options: List<String>,
    showDashIcon: Boolean,
    selected: Int,
    customLabel: String,
    onCustom: () -> Unit,
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
        Text(
            text = customLabel,
            style = MyTheme.Typography.BodyLarge,
            color = colors.textPrimary,
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.backgroundSecondary)
                .padding(6.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onCustom)
                .padding(14.dp)
        )
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
            ConnectLimitPickerContent("Identity funding limits", listOf("0.1", "0.5", "2", "5"), true, 1,
                "Set custom limit", {}) {}
        }
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ConnectCustomLimitPreview() {
    DashWalletTheme {
        ConnectCustomLimitContent("6", {}) {}
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ConnectInfoPreview() {
    DashWalletTheme {
        ConnectInfoContent(ConnectInfo.FUNDING_LIMIT) {}
    }
}
