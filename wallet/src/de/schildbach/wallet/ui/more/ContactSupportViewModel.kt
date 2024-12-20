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
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.AbstractBlockChain
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.ScriptException
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.TreeSet
import java.util.zip.GZIPOutputStream
import javax.inject.Inject


@HiltViewModel
class ContactSupportViewModel @Inject constructor(
    private val configuration: Configuration,
    private val application: WalletApplication,
    walletDataProvider: WalletDataProvider,
    private val packageInfoProvider: PackageInfoProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(ContactSupportViewModel::class.java)
        private const val MAX_LOGS_SIZE = 20 * 1024 * 1024
        private const val MAX_WALLET_DUMP_SIZE = 4// * 1024 * 1024

    }

    var isCrash: Boolean = false
    val wallet: Wallet? = walletDataProvider.wallet
    var contextualData: String? = null
    var stackTrace: String? = null
    suspend fun createReport(
        userIssueDescription: String,
        collectDeviceInfo: Boolean,
        collectInstalledPackages: Boolean,
        collectApplicationLog: Boolean,
        collectWalletDump: Boolean
    ): Pair<String, ArrayList<Uri>> = withContext(Dispatchers.IO) {
        val text = StringBuilder()
        val attachments = ArrayList<Uri>()
        val cacheDir = application.cacheDir
        val reportDir = File(cacheDir, "report")
        reportDir.mkdir()

        text.append(userIssueDescription).append('\n')

        try {
            val contextualData: CharSequence? = collectContextualData()
            if (contextualData != null) {
                text.append("\n\n\n=== contextual data ===\n\n")
                text.append(contextualData)
            }
        } catch (x: IOException) {
            text.append(x.toString()).append('\n')
        }

        try {
            text.append("\n\n\n=== application info ===\n\n")
            val applicationInfo: CharSequence = collectApplicationInfo()
            text.append(applicationInfo)
        } catch (x: IOException) {
            text.append(x.toString()).append('\n')
        }

        try {
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
            try {
                text.append("\n\n\n=== device info ===\n\n")
                val deviceInfo: CharSequence = collectDeviceInfo()
                text.append(deviceInfo)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (collectInstalledPackages) {
            try {
                text.append("\n\n\n=== installed packages ===\n\n")
                CrashReporter.appendInstalledPackages(text, application)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (collectApplicationLog) {
            val logDir = File(application.filesDir, "log")
            var totalLogsSize = 0L
            if (logDir.exists()) {
                val sortedLogFiles = logDir.listFiles()
                sortedLogFiles?.let {
                    it.sortByDescending { file -> file.lastModified() }
                    for (logFile in sortedLogFiles) {
                        if (logFile.isFile() && logFile.length() > 0 && totalLogsSize < MAX_LOGS_SIZE) {
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

        if (collectWalletDump) {
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
                                val buffer = ByteArray(1024)
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

        text.append("\n\nPUT ADDITIONAL COMMENTS TO THE TOP. DOWN HERE NOBODY WILL NOTICE.")

        return@withContext Pair(text.toString(), attachments)
    }

    fun subject(): CharSequence {
        @Suppress("ktlint:standard:wrapping")
        return (Constants.REPORT_SUBJECT_BEGIN + packageInfoProvider.versionName + " "
                + if (isCrash) Constants.REPORT_SUBJECT_CRASH else Constants.REPORT_SUBJECT_ISSUE)
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
                false, // don't include transactions here
                true,
                null
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