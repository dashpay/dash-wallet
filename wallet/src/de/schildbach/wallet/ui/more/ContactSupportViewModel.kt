/*
 * Copyright (c) 2024. Dash Core Group.
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

import android.net.Uri
import android.os.PowerManager
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import com.google.common.base.Charsets
import com.google.common.base.Stopwatch
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.service.DashjDiagnosticSyncState
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService
import de.schildbach.wallet.service.platform.sdk.ParityReport
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

enum class ReportGenerationStatus {
    NotStarted,
    ContextualInfo,
    ApplicationInfo,
    StackTrace,
    DeviceInfo,
    Packages,
    Logs,
    WalletDump,
    BackgroundTraces,
    Finishing,
    Complete
}

@HiltViewModel
class ContactSupportViewModel @Inject constructor(
    private val configuration: Configuration,
    private val application: WalletApplication,
    walletDataProvider: WalletData,
    private val packageInfoProvider: PackageInfoProvider,
    private val transactionMetadataDocumentDao: TransactionMetadataDocumentDao,
    private val dashPayConfig: DashPayConfig,
    private val dashjDiagnosticSyncState: DashjDiagnosticSyncState,
    private val l1ShadowSyncService: L1ShadowSyncService,
    private val sdkService: DashSdkService
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(ContactSupportViewModel::class.java)
        private const val MAX_LOGS_SIZE = 12 * 1024 * 1024
        private const val MAX_WALLET_DUMP_SIZE = 4 * 1024 * 1024
        private const val MAX_WALLET_LOG_SIZE = 4 * 1024 * 1024
    }

    val wallet: Wallet? = walletDataProvider.wallet
    var contextualData: String? = null
    var stackTrace: String? = null
    var isCrash: Boolean = false
    private val _status = MutableStateFlow(ReportGenerationStatus.NotStarted)
    val status = _status.asStateFlow()

    suspend fun createReport(
        userIssueDescription: String,
        collectDeviceInfo: Boolean,
        collectInstalledPackages: Boolean,
        collectApplicationLog: Boolean,
        collectWalletDump: Boolean
    ): Pair<String, ArrayList<Uri>> = withContext(Dispatchers.IO) {
        log.info("createReport({})", collectWalletDump)
        val text = StringBuilder()
        val attachments = ArrayList<Uri>()
        val cacheDir = application.cacheDir
        val reportDir = File(cacheDir, "report")
        reportDir.mkdir()
        val watch = Stopwatch.createStarted()
        text.append(userIssueDescription).append('\n')

        try {
            _status.value = ReportGenerationStatus.ContextualInfo
            val contextualData: CharSequence? = collectContextualData()
            if (contextualData != null) {
                text.append("\n\n\n=== contextual data ===\n\n")
                text.append(contextualData)
            }
        } catch (x: IOException) {
            text.append(x.toString()).append('\n')
        }

        try {
            _status.value = ReportGenerationStatus.ApplicationInfo
            text.append("\n\n\n=== application info ===\n\n")
            val applicationInfo: CharSequence = collectApplicationInfo()
            text.append(applicationInfo)
        } catch (x: IOException) {
            text.append(x.toString()).append('\n')
        }

        try {
            _status.value = ReportGenerationStatus.StackTrace
            val stackTrace: CharSequence? = collectStackTrace()
            if (stackTrace != null) {
                text.append("\n\n\n=== stack trace ===\n\n")
                text.append(stackTrace)
            }
        } catch (x: IOException) {
            text.append("\n\n\n=== stack trace ===\n\n")
            text.append(x.toString()).append('\n')
        }

        if (collectDeviceInfo) {
            _status.value = ReportGenerationStatus.DeviceInfo
            try {
                text.append("\n\n\n=== device info ===\n\n")
                val deviceInfo: CharSequence = collectDeviceInfo()
                text.append(deviceInfo)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (collectInstalledPackages) {
            _status.value = ReportGenerationStatus.Packages
            try {
                text.append("\n\n\n=== installed packages ===\n\n")
                CrashReporter.appendInstalledPackages(text, application)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (collectApplicationLog) {
            _status.value = ReportGenerationStatus.Logs
            val logDir = File(application.filesDir, "log")
            var totalLogsSize = 0L
            if (logDir.exists()) {
                val sortedLogFiles = logDir.listFiles()
                sortedLogFiles?.let { it ->
                    it.sortByDescending { file -> file.lastModified() }
                    for (logFile in sortedLogFiles) {
                        if (logFile.isFile() && logFile.length() > 0 && totalLogsSize < MAX_LOGS_SIZE) {
                            // Check if it's wallet.log and larger than 4 MB
                            if (logFile.name == "wallet.log" && logFile.length() > MAX_WALLET_LOG_SIZE) {
                                // Compress the wallet.log file
                                val compressedFile = File(reportDir, "wallet.log.gz")
                                try {
                                    FileInputStream(logFile).use { fis ->
                                        GZIPOutputStream(FileOutputStream(compressedFile)).use { gzipOS ->
                                            val buffer = ByteArray(4096)
                                            var len: Int
                                            while (fis.read(buffer).also { count -> len = count } != -1) {
                                                gzipOS.write(buffer, 0, len)
                                            }
                                            log.info("wallet.log compressed successfully to $compressedFile")
                                            attachments.add(
                                                FileProvider.getUriForFile(
                                                    application,
                                                    application.packageName + ".file_attachment", compressedFile
                                                )
                                            )
                                            totalLogsSize += compressedFile.length()
                                        }
                                    }
                                } catch (e: IOException) {
                                    log.error("Failed to compress wallet.log", e)
                                }
                            } else {
                                attachments.add(
                                    FileProvider.getUriForFile(
                                        application,
                                        application.packageName + ".file_attachment", logFile
                                    )
                                )
                                totalLogsSize += logFile.length()
                            }
                        }
                    }
                }
            }
        }

        if (collectWalletDump) {
            _status.value = ReportGenerationStatus.WalletDump
            log.info("createReport - collecting wallet dump")
            try {
                val walletDump: CharSequence = collectWalletDump()
                val file = File.createTempFile("wallet-dump.", ".txt", reportDir)
                val writer: Writer = OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)
                writer.write(walletDump.toString())
                writer.close()
                if(file.length() > MAX_WALLET_DUMP_SIZE) {
                    // compress the wallet dump
                    val compressedFile = File(file.absolutePath + ".gz")
                    try {
                        FileInputStream(file).use { fis ->
                            GZIPOutputStream(FileOutputStream(compressedFile)).use { gzipOS ->
                                val buffer = ByteArray(4096)
                                var len: Int
                                while (fis.read(buffer).also { len = it } != -1) {
                                    gzipOS.write(buffer, 0, len)
                                }
                                log.info("wallet dump compressed successfully to $compressedFile")
                                attachments.add(
                                    FileProvider.getUriForFile(application, application.packageName + ".file_attachment", compressedFile)
                                )
                            }
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                } else {
                    attachments.add(
                        FileProvider.getUriForFile(application, application.packageName + ".file_attachment", file)
                    )
                }
            } catch (x: IOException) {
                log.info("problem writing attachment", x)
            }
        }

        try {
            _status.value = ReportGenerationStatus.BackgroundTraces
            val savedBackgroundTraces = File.createTempFile("background-traces.", ".txt", reportDir)
            if (CrashReporter.collectSavedBackgroundTraces(savedBackgroundTraces)) {
                attachments.add(
                    FileProvider.getUriForFile(
                        application, application.packageName + ".file_attachment",
                        savedBackgroundTraces
                    )
                )
            }
            savedBackgroundTraces.deleteOnExit()
        } catch (x: IOException) {
            log.info("problem writing attachment", x)
        }
        // only for development
        if (BuildConfig.DEBUG) {
            try {
                val txMetadataEntries = File.createTempFile("tx-metadata-documents.", ".txt", reportDir)
                val listDocs = transactionMetadataDocumentDao.load()
                try {
                    FileWriter(txMetadataEntries).use { writer ->
                        writer.write("Transaction Metadata Documents\n")
                        listDocs.forEach {
                            writer.write("${it.id}, ${it.txId}, memo=${it.memo}, rate=${it.rate} ${it.currencyCode}, taxCat=${it.taxCategory}, service=${it.service}")
                            writer.write("\n")
                        }
                    }
                } catch (e: IOException) {
                    log.info("io error: ", e)
                }
                if (listDocs.isNotEmpty()) {
                    attachments.add(
                        FileProvider.getUriForFile(
                            application, application.packageName + ".file_attachment",
                            txMetadataEntries
                        )
                    )
                }
                txMetadataEntries.deleteOnExit()
            } catch (x: IOException) {
                log.info("problem writing attachment", x)
            }
        }

        // dashJ ↔ Kotlin SDK parity diagnostic: attach only when the Tools
        // "dashj sync (diagnostic)" toggle is on, or the diagnostic ran at
        // some point this launch (parity history exists). Fully additive —
        // with the flag off and never used, no file is added.
        try {
            val diagnosticEnabled = dashPayConfig.getDashjSyncDiagnostic()
            val parityHistory = dashjDiagnosticSyncState.parityHistory()
            if (diagnosticEnabled || parityHistory.isNotEmpty()) {
                val parityLogFile = File(reportDir, "dashJ-kotlin-parity-log.txt")
                FileWriter(parityLogFile).use { writer ->
                    writer.write(buildDashjKotlinParityLog(diagnosticEnabled, parityHistory))
                }
                attachments.add(
                    FileProvider.getUriForFile(
                        application, application.packageName + ".file_attachment",
                        parityLogFile
                    )
                )
            }
        } catch (x: Exception) {
            log.info("problem writing the dashJ-kotlin parity log attachment", x)
        }

        text.append("\n\nPUT ADDITIONAL COMMENTS TO THE TOP. DOWN HERE NOBODY WILL NOTICE.")
        log.info("create report: {}", watch)
        _status.value = ReportGenerationStatus.Finishing
        return@withContext Pair(text.toString(), attachments)
    }

    fun subject(): CharSequence {
        @Suppress("ktlint:standard:wrapping")
        return (Constants.REPORT_SUBJECT_BEGIN + packageInfoProvider.versionName + " "
                + if (isCrash) Constants.REPORT_SUBJECT_CRASH else Constants.REPORT_SUBJECT_ISSUE)
    }

    /**
     * The content of the `dashJ-kotlin-parity-log.txt` support-log attachment:
     * the current diagnostic state (percent + verdict), the SDK wallet's
     * unspent/total TXO counts (500-input standard-tx cap check), the latest
     * [ParityReport] from the L1 shadow harness, and the recent parity
     * history recorded by [DashjDiagnosticSyncState.recordParity].
     */
    private fun buildDashjKotlinParityLog(
        diagnosticEnabled: Boolean,
        parityHistory: List<DashjDiagnosticSyncState.ParityHistoryEntry>
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        fun formatReport(r: ParityReport): String =
            "reportTime=${dateFormat.format(Date(r.timestampMs))}" +
                " estimated sdk=${r.sdkDuffs} dashj=${r.dashjDuffs}" +
                " confirmed sdk=${r.sdkConfirmedDuffs} dashj=${r.dashjAvailableDuffs}" +
                " tx sdk=${r.sdkTxCount} dashj=${r.dashjTxCount}" +
                " sdkSynced=${r.sdkSynced}"

        val snapshot = dashjDiagnosticSyncState.state.value
        val latest = l1ShadowSyncService.latestParity.value
        val text = StringBuilder()
        text.append("=== dashJ / Kotlin SDK parity log ===\n")
        text.append("generated: ").append(dateFormat.format(Date())).append('\n')
        text.append("network: ").append(Constants.NETWORK_PARAMETERS.id).append('\n')
        text.append("app version: ").append(packageInfoProvider.versionName).append('\n')
        text.append("dashj version: ")
            .append(de.schildbach.wallet_test.BuildConfig.DASHJ_VERSION).append('\n')
        text.append("SDK AAR version: ")
            .append(de.schildbach.wallet_test.BuildConfig.DASH_SDK_VERSION).append('\n')
        text.append("diagnostic enabled now: ").append(diagnosticEnabled).append('\n')
        text.append("diagnostic state: active=").append(snapshot.active)
            .append(" percent=").append(snapshot.percent)
            .append(" verdict=").append(snapshot.parity)
            .append(" stage=").append(snapshot.stageName)
            .append('\n')
        // TXO counts straight from the SDK's own Room DB (dash-sdk.db).
        // Support-relevant because a standard transaction is capped at 500
        // inputs: a wallet whose UNSPENT-TXO count approaches/exceeds 500
        // cannot spend its full balance in one standard tx, which this line
        // lets support spot without any SDK change. The AAR's TxoDao has no
        // unspent-count query, so this is a raw COUNT over the same `txos`
        // table the DAO reads (isSpent = 0 mirrors observeUnspent). Blocking
        // is fine — createReport runs on Dispatchers.IO. Fail-soft: the line
        // is simply omitted when the SDK DB is not started or the query fails.
        try {
            sdkService.databaseOrNull()?.let { db ->
                fun countQuery(sql: String): Long? =
                    db.query(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                val unspent = countQuery("SELECT COUNT(*) FROM txos WHERE isSpent = 0")
                val total = countQuery("SELECT COUNT(*) FROM txos")
                if (unspent != null && total != null) {
                    text.append("unspent TXOs: ").append(unspent)
                        .append(" (total: ").append(total).append(")\n")
                }
            }
        } catch (x: Exception) {
            log.info("problem reading the SDK TXO counts for the parity log", x)
        }

        text.append("\n--- latest parity report ---\n")
        text.append(latest?.let { formatReport(it) } ?: "none").append('\n')

        text.append("\n--- parity history (oldest first, up to 50 entries) ---\n")
        if (parityHistory.isEmpty()) {
            text.append("none\n")
        } else {
            parityHistory.forEach { entry ->
                text.append(dateFormat.format(Date(entry.recordedAtMs)))
                    .append(" percent=").append(entry.percent)
                    .append(" verdict=").append(entry.verdict)
                entry.report?.let { text.append(' ').append(formatReport(it)) }
                text.append('\n')
            }
        }
        return text.toString()
    }

    @Throws(IOException::class)
    private fun collectApplicationInfo(): CharSequence {
        val applicationInfo = java.lang.StringBuilder()
        CrashReporter.appendApplicationInfo(
            applicationInfo,
            packageInfoProvider,
            configuration,
            wallet,
            application.getSystemService(
                PowerManager::class.java
            )
        )
        return applicationInfo
    }

    @Throws(IOException::class)
    private fun collectDeviceInfo(): CharSequence {
        val deviceInfo = java.lang.StringBuilder()
        CrashReporter.appendDeviceInfo(deviceInfo, application)
        return deviceInfo
    }

    // taken from Wallet.toStringHelper (private)
    private fun toStringHelper(
        builder: java.lang.StringBuilder,
        transactionMap: MutableMap<Sha256Hash, Transaction>,
        chain: AbstractBlockChain?,
        sortOrder: Comparator<Transaction>?
    ) {
        val txns: MutableCollection<Transaction>
        if (sortOrder != null) {
            txns = TreeSet(sortOrder)
            txns.addAll(transactionMap.values)
        } else {
            txns = transactionMap.values
        }
        for (tx in txns) {
            try {
                builder.append(tx.getValue(wallet).toFriendlyString())
                builder.append(" total value (sends ")
                builder.append(tx.getValueSentFromMe(wallet).toFriendlyString())
                builder.append(" and receives ")
                builder.append(tx.getValueSentToMe(wallet).toFriendlyString())
                builder.append(")\n")
            } catch (e: ScriptException) {
                // Ignore and don't print this line.
            }
            if (tx.hasConfidence()) builder.append("  confidence: ").append(tx.confidence).append('\n')
            builder.append(tx.toString(chain, "  "))
        }
    }

    private fun collectWalletDump(): CharSequence {
        return wallet?.let {
            org.bitcoinj.core.Context.propagate(it.context)
            val walletDump = it.toString(
                false,
                false,
                null,
                false, // don't include transactions here
                true,
                null,
                false
            )
            // only include pending and dead transactions
            val txDump = StringBuilder()
            txDump.append("\n\n")
            txDump.append("\n>>> PENDING:\n")
            toStringHelper(
                txDump,
                it.getTransactionPool(WalletTransaction.Pool.PENDING),
                null,
                Transaction.SORT_TX_BY_UPDATE_TIME
            )
            txDump.append("\n>>> DEAD:\n")
            toStringHelper(
                txDump,
                it.getTransactionPool(WalletTransaction.Pool.DEAD),
                null,
                Transaction.SORT_TX_BY_UPDATE_TIME
            )
            walletDump + txDump
        } ?: "No wallet loaded"
    }

    @Throws(IOException::class)
    private fun collectStackTrace(): CharSequence? {
        return stackTrace
    }

    @Throws(IOException::class)
    private fun collectContextualData(): CharSequence? {
        return contextualData
    }
}