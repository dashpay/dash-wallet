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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.util.CrashReporter
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import javax.inject.Inject

@HiltViewModel
class ContactSupportViewModel @Inject constructor(
    private val configuration: Configuration,
    private val application: WalletApplication,
    private val walletDataProvider: WalletDataProvider,
    private val packageInfoProvider: PackageInfoProvider
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(ContactSupportViewModel::class.java)
    }

    var isCrash: Boolean = false
    val wallet: Wallet? = walletDataProvider.wallet
    var contextualData: String? = null
    var stackTrace: String? = null
    suspend fun createReport(
        viewDescription: String,
        viewCollectDeviceInfo: Boolean,
        viewCollectInstalledPackages: Boolean,
        viewCollectApplicationLog: Boolean,
        viewCollectWalletDump: Boolean
    ): Pair<String, ArrayList<Uri>> {
        val text = StringBuilder()
        val attachments = ArrayList<Uri>()
        val cacheDir = application.cacheDir
        val reportDir = File(cacheDir, "report")
        reportDir.mkdir()

        text.append(viewDescription).append('\n')

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

        if (viewCollectDeviceInfo) {
            try {
                text.append("\n\n\n=== device info ===\n\n")
                val deviceInfo: CharSequence = collectDeviceInfo()
                text.append(deviceInfo)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (viewCollectInstalledPackages) {
            try {
                text.append("\n\n\n=== installed packages ===\n\n")
                CrashReporter.appendInstalledPackages(text, application)
            } catch (x: IOException) {
                text.append(x.toString()).append('\n')
            }
        }

        if (viewCollectApplicationLog) {
            val logDir = File(application.filesDir, "log")
            if (logDir.exists()) for (logFile in logDir.listFiles()) if (logFile.isFile() && logFile.length() > 0) attachments.add(
                FileProvider.getUriForFile(
                    application,
                    application.packageName + ".file_attachment", logFile
                )
            )
        }

        if (viewCollectWalletDump) {
            try {
                val walletDump: CharSequence = collectWalletDump()
                if (walletDump != null) {
                    val file = File.createTempFile("wallet-dump.", ".txt", reportDir)
                    val writer: Writer = OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)
                    writer.write(walletDump.toString())
                    writer.close()
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

        return Pair(text.toString(), attachments)
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

    private fun collectWalletDump(): CharSequence {
        return wallet?.let {
            org.bitcoinj.core.Context.propagate(it.context)
            val walletDump = it.toString(
                false,
                false,
                true,
                null
            )
            it.getTransactionPool(WalletTransaction.Pool.DEAD)
            walletDump
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