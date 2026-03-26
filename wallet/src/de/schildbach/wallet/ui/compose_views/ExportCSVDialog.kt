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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.common.base.Charsets
import de.schildbach.wallet.Constants
import de.schildbach.wallet.transactions.TransactionExporter
import de.schildbach.wallet_test.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.SheetButton
import org.dash.wallet.common.ui.components.SheetButtonGroup
import org.dash.wallet.common.ui.components.Style
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

private val log = LoggerFactory.getLogger("ExportCSVDialog")

/**
 * Creates the Export CSV bottom sheet dialog with all business logic.
 *
 * Figma Node ID: 31265:8935
 */
fun createExportCSVDialog(
    activity: FragmentActivity,
    transactionExporter: TransactionExporter,
    onDismiss: () -> Unit = {}
): ComposeBottomSheet {
    var isLoading by mutableStateOf(false)

    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false
    ) { dialog ->
        ExportCSVContent(
            isLoading = isLoading,
            onExportClick = {
                if (isLoading) return@ExportCSVContent
                activity.lifecycleScope.launch {
                    try {
                        isLoading = true
                        dialog.dialog?.setCancelable(false)
                        dialog.dialog?.setCanceledOnTouchOutside(false)

                        withContext(Dispatchers.IO) { transactionExporter.initMetadataMap() }
                        val csvContent = withContext(Dispatchers.IO) { transactionExporter.exportString() }

                        val reportDir = File(activity.cacheDir, "report").also { it.mkdirs() }
                        val file = File.createTempFile("transaction-history.", ".csv", reportDir)
                        withContext(Dispatchers.IO) {
                            OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { it.write(csvContent) }
                        }

                        isLoading = false
                        startSendIntent(activity, file)
                        dialog.dismiss()
                        onDismiss()
                    } catch (e: Exception) {
                        log.error("Failed to export CSV", e)
                        isLoading = false
                        dialog.dialog?.setCancelable(true)
                        dialog.dialog?.setCanceledOnTouchOutside(true)
                        dialog.dismiss()
                        onDismiss()
                    }
                }
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