/*
 * Copyright (c) 2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.compose_views

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import de.schildbach.wallet.ui.more.tools.ZenLedgerViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.openCustomTab

/**
 * Creates the main ZenLedger bottom sheet dialog.
 *
 * Figma Node ID: 32543:9120
 *
 * Shows the ZenLedger icon, title, description, a link to zenledger.io, and
 * an "Export all transactions" primary action button. Blocks dismissal while
 * [ZenLedgerViewModel.sendTransactionInformation] is running.
 */
fun createZenLedgerDialog(
    activity: FragmentActivity,
    viewModel: ZenLedgerViewModel
): ComposeBottomSheet {
    var isLoading by mutableStateOf(false)

    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false
    ) { dialog ->
        ZenLedgerContent(
            isLoading = isLoading,
            onExportClick = {
                activity.lifecycleScope.launch {
                    if (viewModel.isSynced()) {
                        val confirmed = AdaptiveDialog.create(
                            null,
                            activity.getString(R.string.zenledger_export_title),
                            activity.getString(R.string.zenledger_export_permission),
                            activity.getString(R.string.button_cancel),
                            activity.getString(R.string.permission_allow)
                        ).showAsync(activity) == true

                        if (confirmed) {
                            isLoading = true
                            dialog.dialog?.setCancelable(false)
                            dialog.dialog?.setCanceledOnTouchOutside(false)

                            if (viewModel.sendTransactionInformation() && viewModel.signUpUrl != null) {
                                activity.openCustomTab(viewModel.signUpUrl!!)
                                dialog.dismiss()
                            } else {
                                isLoading = false
                                dialog.dialog?.setCancelable(true)
                                dialog.dialog?.setCanceledOnTouchOutside(true)
                                AdaptiveDialog.create(
                                    null,
                                    activity.getString(R.string.zenledger_export_title),
                                    activity.getString(R.string.zenledger_export_error),
                                    activity.getString(R.string.button_close)
                                ).showAsync(activity)
                            }
                        }
                    } else {
                        AdaptiveDialog.create(
                            null,
                            activity.getString(R.string.chain_syncing),
                            activity.getString(R.string.chain_syncing_default_message),
                            activity.getString(R.string.button_close)
                        ).showAsync(activity)
                    }
                }
            },
            onLinkClick = {
                activity.startActivity(Intent.parseUri(activity.getString(R.string.zenledger_export_url), 0))
            }
        )
    }
}

@Composable
internal fun ZenLedgerContent(
    isLoading: Boolean = false,
    onExportClick: () -> Unit,
    onLinkClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp), // space for drag indicator and close button
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ZenLedger icon (PNG asset)
        Image(
            painter = painterResource(R.drawable.ic_zenledger_dash),
            contentDescription = null,
            modifier = Modifier
                .padding(vertical = 20.dp)
                .size(95.dp)
        )

        // Title and description
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.zenledger_export_subtitle),
                style = MyTheme.Typography.HeadlineSmallBold,
                color = MyTheme.Colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.zenledger_export_description),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            // zenledger.io clickable link with external link icon
            Row(
                modifier = Modifier
                    .clickable { onLinkClick() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.zenledger_export_link),
                    style = MyTheme.Caption,
                    color = MyTheme.Colors.dashBlue,
                    textAlign = TextAlign.Center
                )
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_open_link),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MyTheme.Colors.dashBlue
                )
            }

            Spacer(modifier = Modifier.height(36.dp))
        }

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.zenledger_export_all_tx),
                style = Style.FilledBlue,
                isEnabled = !isLoading,
                isLoading = isLoading,
                onClick = onExportClick
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ZenLedgerContentPreview() {
    ZenLedgerContent(
        onExportClick = {},
        onLinkClick = {}
    )
}