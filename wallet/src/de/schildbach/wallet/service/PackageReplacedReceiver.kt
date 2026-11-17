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
package de.schildbach.wallet.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.WorkerThread
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import org.bitcoinj.script.Script
import org.bitcoinj.utils.ContextPropagatingThreadFactory
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.Configuration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Handles `MY_PACKAGE_REPLACED` (an app update): upgrades the wallet and starts syncing.
 *
 * This is deliberately a separate receiver from [BootstrapReceiver], which handles
 * `BOOT_COMPLETED`. Apps targeting Android 15+ must not start a `dataSync` foreground
 * service (the type [BlockchainServiceImpl] declares) from a `BOOT_COMPLETED` receiver,
 * and Play checks this per receiver class: while both actions shared one class, the
 * update path's foreground-service start was reported as a boot-time start.
 * `MY_PACKAGE_REPLACED` is exempt from the background foreground-service-start
 * restrictions, so starting the service here is allowed.
 */
@AndroidEntryPoint
class PackageReplacedReceiver : BroadcastReceiver() {
    @Inject
    lateinit var application: WalletApplication

    @Inject
    lateinit var config: Configuration

    @Inject
    lateinit var walletData: WalletData

    @Inject
    lateinit var inactivityReminder: InactivityReminder

    override fun onReceive(context: Context, intent: Intent) {
        log.info("got broadcast: {}", intent)
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            log.warn("ignoring unexpected action {}", intent.action)
            return
        }
        val result = goAsync()
        executor.execute {
            try {
                org.bitcoinj.core.Context.propagate(Constants.CONTEXT)
                onAsyncReceive(context)
            } finally {
                result.finish()
            }
        }
    }

    @WorkerThread
    private fun onAsyncReceive(context: Context) {
        // An app update cold-starts the process in the background, so the wallet may
        // still be loading off the main thread; the upgrade mutates it, so wait for the
        // load to finish outright.
        application.awaitWalletReadyForMutation()
        maybeUpgradeWallet(walletData.wallet)

        if (application.wallet != null) {
            // An UPGRADE must actually start syncing. startBlockchainService() silently
            // no-ops here — this process runs at receiver importance, below the
            // IMPORTANCE_FOREGROUND its guard demands — so the 2026-09-16 emulator upgrade
            // test committed the cutover, bound the wallet and then scanned nothing at all,
            // with no foreground service to start the SDK L1 engine from.
            // MY_PACKAGE_REPLACED is exempt from the background FGS-start restrictions, so
            // start one directly, and fall back to the alarm if the platform still refuses.
            if (!application.startBlockchainServiceAfterUpgrade()) {
                scheduleDelayedBlockchainServiceStart(context)
            }
        }

        // if the app hasn't been used for a while and contains coins, maybe show reminder
        inactivityReminder.maybeShow()
        application.myPackageReplaced = true

        // reset the notification explainer flag
        config.setShowNotificationsExplainer(true)
    }

    @WorkerThread
    private fun maybeUpgradeWallet(wallet: Wallet?) {
        log.info("maybe upgrading wallet")

        if (wallet == null) {
            // with version 7.0 and above it is possible to have the app installed without a wallet
            log.info("wallet does not exist, not upgrading the wallet file")
            return
        }

        // Maybe upgrade wallet from basic to deterministic, and maybe upgrade to the latest script type
        if (wallet.isDeterministicUpgradeRequired(Script.ScriptType.P2PKH) && !wallet.isEncrypted) {
            // upgrade from v1 wallet to v4 wallet
            wallet.upgradeToDeterministic(Script.ScriptType.P2PKH, null)
        }

        // for upgrades from version 9.0.0 to 9.0.3, there is a bug that requires rescanning
        // the transactions for protx related items
        val extension = wallet.extensions[AuthenticationGroupExtension.EXTENSION_ID]
        if (extension is AuthenticationGroupExtension) {
            // reset will rescan existing transactions and rebuild the authentication usage info
            extension.rescanWallet()
        }

        // Maybe upgrade wallet to secure chain
        try {
            wallet.doMaintenance(null, false)
        } catch (x: Exception) {
            log.error("failed doing wallet maintenance", x)
        }
    }

    @WorkerThread
    private fun scheduleDelayedBlockchainServiceStart(context: Context) {
        log.info("scheduling delayed blockchain service start after the package replacement")
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val serviceIntent = Intent(context, DelayedServiceStartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            serviceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + DELAYED_START_MS,
            pendingIntent
        )
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(PackageReplacedReceiver::class.java)
        private const val DELAYED_START_MS = 5000L
        private val executor: Executor =
            Executors.newSingleThreadExecutor(ContextPropagatingThreadFactory("package-replaced"))
    }
}
