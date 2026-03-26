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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.compose_views

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style

/**
 * Creates the Export CSV bottom sheet dialog.
 *
 * Figma Node ID: 31265:8935
 *
 * Shows a title, description of what will be exported, and two buttons:
 * a Cancel button and an Export button. While exporting is in progress,
 * the Export button shows a loading indicator and both buttons are disabled.
 *
 * @param isLoading whether the export operation is currently in progress
 * @param onExportClick called when the user taps "Export transactions"
 * @return ComposeBottomSheet instance ready to be shown
 */
fun createExportCSVDialog(
    isLoading: () -> Boolean = { false },
    onExportClick: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { _ ->
            ExportCSVContent(
                isLoading = isLoading(),
                onExportClick = onExportClick
            )
        }
    )
}

/**
 * The Compose content for the Export CSV bottom sheet.
 *
 * Figma Node ID: 31265:8935
 */
@Composable
fun ExportCSVContent(
    isLoading: Boolean = false,
    onExportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp), // space for drag indicator and close button
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_export_csv),
            contentDescription = null,
            modifier = Modifier
                .padding(bottom = 30.dp)
                .size(95.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.report_transaction_history_dialog_title),
                style = MyTheme.Typography.HeadlineLargeBold,
                color = MyTheme.Colors.textPrimary
            )

            Text(
                text = stringResource(R.string.report_transaction_history_body_1),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary
            )

            Text(
                text = stringResource(R.string.report_transaction_history_body_2),
                style = MyTheme.Typography.BodyMedium,
                color = MyTheme.Colors.textSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.report_transaction_history_export),
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
private fun ExportCSVContentPreview() {
    ExportCSVContent(
        isLoading = false,
        onExportClick = {},
    )
}

@Preview(showBackground = true)
@Composable
private fun ExportCSVContentLoadingPreview() {
    ExportCSVContent(
        isLoading = true,
        onExportClick = {},
    )
}