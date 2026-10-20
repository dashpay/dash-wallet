/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.compose_views

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import de.schildbach.wallet.ui.more.connections.ConnectionsViewModel
import de.schildbach.wallet.ui.more.connections.TokenPurchaseStatus
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogComposeContainerBinding
import org.dash.wallet.common.ui.components.*
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

class ApproveTokenPurchaseDialog : OffsetDialogFragment(R.layout.dialog_compose_container) {
    override val forceExpand = true
    override val backgroundStyle = R.style.SecondaryBackground
    private val viewModel: ConnectionsViewModel by viewModels({ requireParentFragment() })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = viewModel.uiState.value.purchaseStatus != TokenPurchaseStatus.SUBMITTING
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(org.dash.wallet.common.R.id.collapse_button)?.setOnClickListener {
            viewModel.dismissTokenPurchase()
        }
        DialogComposeContainerBinding.bind(view).composeContainer.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DashWalletTheme { PurchaseContent() } }
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        viewModel.dismissTokenPurchase()
        super.onCancel(dialog)
    }

    @Composable private fun PurchaseContent() {
        val state by viewModel.uiState.collectAsState()
        val loading = state.purchaseStatus == TokenPurchaseStatus.SUBMITTING
        val finished = state.purchaseStatus in setOf(TokenPurchaseStatus.SUBMITTED, TokenPurchaseStatus.OUTCOME_UNKNOWN)
        val colors = LocalDashColors.current
        LaunchedEffect(loading, state.pendingPurchase) {
            isCancelable = !loading
            dialog?.setCanceledOnTouchOutside(!loading)
            view?.findViewById<View>(org.dash.wallet.common.R.id.collapse_button)?.isEnabled = !loading
            if (state.pendingPurchase == null) dismissAllowingStateLoss()
        }
        state.pendingPurchase?.let { purchase ->
            Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState()).padding(top = 60.dp, bottom = 20.dp)) {
                Column(Modifier.padding(horizontal = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.dash_st_purchase_title), style = MyTheme.Typography.HeadlineMediumBold,
                        color = colors.textPrimary)
                    Text(stringResource(R.string.dash_st_purchase_quantity, purchase.quantity, purchase.tokenName),
                        style = MyTheme.Typography.BodyMedium, color = colors.textPrimary)
                    Text(stringResource(R.string.dash_st_purchase_cost, purchase.maximumDash),
                        style = MyTheme.Typography.BodyMedium, color = colors.textPrimary)
                    Text(stringResource(R.string.dash_st_purchase_identity, purchase.ownerId),
                        style = MyTheme.Typography.BodyMedium, color = colors.textSecondary)
                    Text(stringResource(R.string.dash_st_purchase_contract, purchase.contractId),
                        style = MyTheme.Typography.BodyMedium, color = colors.textSecondary)
                    Text(stringResource(R.string.dash_st_purchase_token, purchase.tokenId),
                        style = MyTheme.Typography.BodyMedium, color = colors.textSecondary)
                    Text(stringResource(R.string.dash_st_purchase_warning), style = MyTheme.Typography.BodyMedium,
                        color = colors.textSecondary)
                    if (state.purchaseStatus == TokenPurchaseStatus.SUBMITTED) {
                        Text(stringResource(R.string.dash_st_purchase_submitted), style = MyTheme.Typography.BodyMedium,
                            color = colors.textPrimary)
                    }
                    state.purchaseError?.let { Text(it, style = MyTheme.Typography.BodyMedium, color = colors.red) }
                }
                Spacer(Modifier.height(20.dp))
                SheetButtonGroup(
                    primaryButton = SheetButton(text = stringResource(if (finished) R.string.button_close else R.string.dash_st_purchase_approve),
                        style = Style.FilledBlue, isEnabled = !loading, isLoading = loading,
                        onClick = { if (finished) viewModel.dismissTokenPurchase() else viewModel.approveTokenPurchase() }),
                    secondaryButton = if (finished) null else SheetButton(text = stringResource(android.R.string.cancel),
                        style = Style.TintedGray, isEnabled = !loading, onClick = viewModel::dismissTokenPurchase),
                    horizontalPadding = 40.dp, spacing = 20.dp)
            }
        }
    }
}
