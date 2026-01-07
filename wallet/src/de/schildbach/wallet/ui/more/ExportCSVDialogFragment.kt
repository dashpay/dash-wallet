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

package de.schildbach.wallet.ui.more

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.common.base.Charsets
import de.schildbach.wallet.Constants
import de.schildbach.wallet.transactions.TransactionExporter
import de.schildbach.wallet_test.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.ui.components.ButtonData
import org.dash.wallet.common.ui.components.ModalDialog
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Dialog fragment for exporting transaction history to CSV with progress indication.
 */
class ExportCSVDialogFragment : DialogFragment() {
    private var dismissFunction: (() -> Unit)? = null
    private var transactionExporter: TransactionExporter? = null

    companion object {
        private val log = LoggerFactory.getLogger(ExportCSVDialogFragment::class.java)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                ExportCSVDialog(
                    transactionExporter = transactionExporter,
                    onDismissRequest = {
                        dismiss()
                        dismissFunction?.invoke()
                    },
                    onExportComplete = { csvFile ->
                        startSendIntent(csvFile)
                        dismiss()
                        dismissFunction?.invoke()
                    },
                    onError = { error ->
                        log.error("Failed to export CSV", error)
                        dismiss()
                        dismissFunction?.invoke()
                    }
                )
            }
        }
    }

    private fun startSendIntent(csvFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.file_attachment",
                csvFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    Constants.REPORT_SUBJECT_BEGIN + getString(R.string.report_transaction_history_title)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(
                    intent,
                    getString(R.string.report_transaction_history_mail_intent_chooser)
                )
            )
            log.info("invoked chooser for exporting transaction history")
        } catch (e: Exception) {
            log.error("export transaction history failed", e)
        }
    }

    fun show(activity: FragmentActivity, transactionExporter: TransactionExporter, dismiss: () -> Unit) {
        this.transactionExporter = transactionExporter
        this.dismissFunction = dismiss
        super.show(activity.supportFragmentManager, "export_csv_dialog")
    }
}

@Composable
fun ExportCSVDialog(
    transactionExporter: TransactionExporter?,
    onDismissRequest: () -> Unit,
    onExportComplete: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    var isLoading by remember { mutableStateOf(false) }
    // var loadingMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val handleExport = {
        if (transactionExporter != null && !isLoading) {
            scope.launch {
                try {
                    isLoading = true
                    //loadingMessage = context.getString(R.string.loading_transaction_metadata)

                    // Initialize metadata map (may take time)
                    withContext(Dispatchers.IO) {
                        transactionExporter.initMetadataMap()
                    }

                    // Generate CSV
                    //loadingMessage = "Generating CSV..."
                    val csvContent = withContext(Dispatchers.IO) {
                        transactionExporter.exportString()
                    }

                    // Write to file
                    val cacheDir = context.cacheDir
                    val reportDir = File(cacheDir, "report")
                    reportDir.mkdirs()

                    val file = File.createTempFile("transaction-history.", ".csv", reportDir)
                    withContext(Dispatchers.IO) {
                        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { writer ->
                            writer.write(csvContent)
                        }
                    }

                    isLoading = false
                    onExportComplete(file)
                } catch (e: Exception) {
                    isLoading = false
                    onError(e)
                }
            }
        }
    }

    ModalDialog(
        showDialog = true,
        onDismissRequest = onDismissRequest,
        heading = stringResource(R.string.report_transaction_history_title),
        textBlocks = listOf(
            stringResource(R.string.report_transaction_history_message)
        ),
        content = /*if (isLoading) {
            {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MyTheme.Colors.dashBlue
                    )
                    Text(
                        text = loadingMessage,
                        style = MyTheme.Body2Regular,
                        color = MyTheme.Colors.textSecondary
                    )
                }
            }
        } else*/ null,
        buttons = listOf(
            ButtonData(
                label = stringResource(R.string.button_cancel),
                onClick = onDismissRequest,
                style = Style.PlainBlack,
                enabled = !isLoading
            ),
            ButtonData(
                label = stringResource(R.string.report_transaction_history_export),
                onClick = handleExport,
                style = Style.FilledBlue,
                enabled = !isLoading,
                progress = isLoading
            )
        )
    )
}

@Preview
@Composable
fun ExportCSVDialogPreview() {
    ExportCSVDialog(
        transactionExporter = null,
        onDismissRequest = {},
        onExportComplete = {},
        onError = {}
    )
}