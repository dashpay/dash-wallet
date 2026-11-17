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

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Coin
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * The "you've still got Dash on this device" reminder, shown on a reboot ([BootstrapReceiver])
 * or an app update ([PackageReplacedReceiver]) when the user hasn't opened the app for a while.
 *
 * The amount is the persisted last-known total balance ([WalletUIConfig.LAST_TOTAL_BALANCE]),
 * the figure the home screen shows on a cold start, not the dashj wallet's own balance:
 * after the cutover to the SDK the dashj wallet is held and its balance is frozen, and the
 * SDK pipeline does not run in a receiver process. Before the cutover the dashj balance
 * observer writes that key, after it the SDK pipeline does.
 *
 * The amount is left out when Autohide Balance is on, or when that setting cannot be read.
 */
class InactivityReminder @Inject constructor(
    private val application: WalletApplication,
    private val config: Configuration,
    private val walletData: WalletData,
    private val walletUIConfig: WalletUIConfig
) {
    /** What the reminder should show. */
    sealed class Content {
        data class WithAmount(val balance: Coin) : Content()
        object AmountHidden : Content()
    }

    @WorkerThread
    fun maybeShow() {
        val content = decide(
            isTimeToRemind = config.isTimeToRemindBalance,
            walletExists = walletData.wallet != null,
            lastTotalBalance = readLastTotalBalance(),
            hideBalance = readHideBalance()
        ) ?: return

        log.info("detected balance, showing inactivity notification")
        val text = when (content) {
            is Content.WithAmount -> application.getString(
                R.string.notification_inactivity_message,
                config.format.format(org.dash.wallet.common.money.Coin.valueOf(content.balance.value))
            )
            Content.AmountHidden -> application.getString(R.string.notification_inactivity_message_hidden)
        }
        post(text)
    }

    /** Null when the balance is unknown: never written, or unreadable. */
    private fun readLastTotalBalance(): Coin? = try {
        runBlocking { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_TOTAL_BALANCE) }
            ?.let(Coin::valueOf)
    } catch (e: Exception) {
        log.warn("cannot read the last known balance, not showing the inactivity reminder", e)
        null
    }

    /** Fails closed: an unreadable setting hides the amount. */
    private fun readHideBalance(): Boolean = try {
        runBlocking { walletUIConfig.getPreservingErrors(WalletUIConfig.AUTO_HIDE_BALANCE) } ?: false
    } catch (e: Exception) {
        log.warn("cannot read the autohide balance setting, hiding the amount", e)
        true
    }

    private fun post(text: String) {
        val notification = NotificationCompat.Builder(application, Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(application.getColor(R.color.fg_network_significant))
            .setSmallIcon(R.drawable.ic_dash_d_white)
            .setContentTitle(application.getString(R.string.notification_inactivity_title))
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    application,
                    0,
                    OnboardingActivity.createIntent(application),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .addAction(
                dismissAction(BootstrapReceiver.ACTION_DISMISS, R.string.notification_inactivity_action_dismiss)
            )
            .addAction(
                dismissAction(
                    BootstrapReceiver.ACTION_DISMISS_FOREVER,
                    R.string.notification_inactivity_action_dismiss_forever
                )
            )
            .build()

        application.getSystemService(NotificationManager::class.java)
            .notify(Constants.NOTIFICATION_ID_INACTIVITY, notification)
    }

    private fun dismissAction(action: String, titleRes: Int): NotificationCompat.Action {
        val intent = Intent(application, BootstrapReceiver::class.java).setAction(action)
        return NotificationCompat.Action.Builder(
            0,
            application.getString(titleRes),
            PendingIntent.getBroadcast(application, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        ).build()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(InactivityReminder::class.java)

        /** Null means no reminder. */
        fun decide(
            isTimeToRemind: Boolean,
            walletExists: Boolean,
            lastTotalBalance: Coin?,
            hideBalance: Boolean
        ): Content? {
            if (!isTimeToRemind) {
                return null
            }
            if (!walletExists) {
                // with version 7.0 and above it is possible to have the app installed without a wallet
                log.info("wallet does not exist, not showing inactivity warning")
                return null
            }
            if (lastTotalBalance == null || !lastTotalBalance.isPositive) {
                return null
            }
            return if (hideBalance) Content.AmountHidden else Content.WithAmount(lastTotalBalance)
        }
    }
}
