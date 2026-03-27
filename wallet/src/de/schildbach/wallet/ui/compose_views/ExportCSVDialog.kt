/*
 * Copyright (c) 2026 Dash Core Group.
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.more.ToolsViewModel
import org.dash.wallet.common.util.findFragmentActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("ExportCSVDialog")

/**
 * Creates the Export CSV bottom sheet dialog with all business logic.
 *
 * Figma Node ID: 31265:8935
 */
fun createExportCSVDialog(
    viewModel: ToolsViewModel,
    onDismiss: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false
    ) { dialog ->
        val context = LocalContext.current
        val activity = remember(context) { context.findFragmentActivity() }
        val exportResult by viewModel.exportCsvResult.collectAsState()
        val isLoading = exportResult is ToolsViewModel.ExportCsvResult.Loading

        DisposableEffect(Unit) {
            onDispose { onDismiss() }
        }

        LaunchedEffect(exportResult) {
            when (val result = exportResult) {
                is ToolsViewModel.ExportCsvResult.Loading -> {
                    dialog.dialog?.setCancelable(false)
                    dialog.dialog?.setCanceledOnTouchOutside(false)
                }
                is ToolsViewModel.ExportCsvResult.Success -> {
                    if (!activity.isDestroyed) {
                        startSendIntent(activity, result.file)
                        dialog.dismiss()
                    }
                    viewModel.resetExportCsvResult()
                }
                is ToolsViewModel.ExportCsvResult.Error -> {
                    dialog.dialog?.setCancelable(true)
                    dialog.dialog?.setCanceledOnTouchOutside(true)
                    if (!activity.isDestroyed) {
                        AdaptiveDialog.create(
                            null,
                            activity.getString(R.string.report_transaction_history_title),
                            activity.getString(R.string.report_transaction_history_dialog_export_csv_failed),
                            activity.getString(R.string.button_close)
                        ).showAsync(activity)
                        dialog.dismiss()
                    }
                    viewModel.resetExportCsvResult()
                }
                is ToolsViewModel.ExportCsvResult.Idle -> Unit
            }
        }

        ExportCSVContent(
            isLoading = isLoading,
            onExportClick = {
                if (isLoading) return@ExportCSVContent
                viewModel.exportCsv(activity.cacheDir)
            }
        )
    }
}

private fun startSendIntent(activity: FragmentActivity, csvFile: File) {
    try {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.file_attachment", csvFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, Constants.REPORT_SUBJECT_BEGIN + activity.getString(R.string.report_transaction_history_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.report_transaction_history_mail_intent_chooser)))
        log.info("invoked chooser for exporting transaction history")
    } catch (e: Exception) {
        log.error("export transaction history failed", e)
    }
}

/**
 * The Compose content for the Export CSV bottom sheet.
 *
 * Figma Node ID: 31265:8935
 */
@Composable
internal fun ExportCSVContent(
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