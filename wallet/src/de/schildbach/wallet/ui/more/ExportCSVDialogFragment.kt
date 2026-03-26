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

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.common.base.Charsets
import de.schildbach.wallet.Constants
import de.schildbach.wallet.transactions.TransactionExporter
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.compose_views.createExportCSVDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Shows the Export CSV bottom sheet dialog and manages the export operation.
 *
 * Figma Node ID: 31265:8935
 *
 * The [isLoading] flag controls whether the dialog shows a loading state and
 * prevents the user from dismissing it while an export operation is in progress.
 */
class ExportCSVDialogFragment {
    private var isLoading: Boolean by mutableStateOf(false)
    private var transactionExporter: TransactionExporter? = null
    private lateinit var bottomSheet: ComposeBottomSheet

    companion object {
        private val log = LoggerFactory.getLogger(ExportCSVDialogFragment::class.java)
    }

    fun show(activity: FragmentActivity, transactionExporter: TransactionExporter, onDismiss: () -> Unit) {
        this.transactionExporter = transactionExporter

        bottomSheet = createExportCSVDialog(
            isLoading = { isLoading },
            onExportClick = { startExport(activity, onDismiss) }
        )
        bottomSheet.show(activity.supportFragmentManager, "export_csv_dialog")
    }

    private fun startExport(activity: FragmentActivity, onDismiss: () -> Unit) {
        val exporter = transactionExporter ?: return
        if (isLoading) return

        activity.lifecycleScope.launch {
            try {
                isLoading = true
                bottomSheet.dialog?.setCancelable(false)
                bottomSheet.dialog?.setCanceledOnTouchOutside(false)

                withContext(Dispatchers.IO) {
                    exporter.initMetadataMap()
                }

                val csvContent = withContext(Dispatchers.IO) {
                    exporter.exportString()
                }

                val cacheDir = activity.cacheDir
                val reportDir = File(cacheDir, "report")
                reportDir.mkdirs()

                val file = File.createTempFile("transaction-history.", ".csv", reportDir)
                withContext(Dispatchers.IO) {
                    OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { writer ->
                        writer.write(csvContent)
                    }
                }

                isLoading = false
                startSendIntent(activity, file)
                bottomSheet.dismiss()
                onDismiss()
            } catch (e: Exception) {
                log.error("Failed to export CSV", e)
                isLoading = false
                bottomSheet.dialog?.setCancelable(true)
                bottomSheet.dialog?.setCanceledOnTouchOutside(true)
                bottomSheet.dismiss()
                onDismiss()
            }
        }
    }

    private fun startSendIntent(activity: FragmentActivity, csvFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.file_attachment",
                csvFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    Constants.REPORT_SUBJECT_BEGIN + activity.getString(de.schildbach.wallet_test.R.string.report_transaction_history_title)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getString(de.schildbach.wallet_test.R.string.report_transaction_history_mail_intent_chooser)
                )
            )
            log.info("invoked chooser for exporting transaction history")
        } catch (e: Exception) {
            log.error("export transaction history failed", e)
        }
    }
}