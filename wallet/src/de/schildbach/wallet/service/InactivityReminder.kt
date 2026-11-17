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
import android.content.Context
import android.content.Intent
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
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
 * The amount is what the home header shows, from persisted last-known values, not the dashj
 * wallet's own balance: after the cutover to the SDK the dashj wallet is held and its balance
 * is frozen. A wallet load in a receiver process may start the SDK pipelines, but the
 * reminder does not wait for them to publish a balance; the persisted values are the same
 * ones the home header seeds itself from on a cold start.
 * - Transparent (L1): [WalletUIConfig.LAST_TOTAL_BALANCE], written by the dashj balance
 *   observer before the cutover and by the SDK pipeline after it.
 * - Shielded: [DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS] when shielding is enabled, zero
 *   when it is not.
 *
 * A part that cannot be known (never written, unreadable, or a shielded spend still
 * reconciling) never makes the amount smaller: the reminder is shown without an amount
 * when the known parts are positive, and not shown at all when they are not. The amount is
 * also left out when Autohide Balance is on, or when that setting cannot be read.
 */
class InactivityReminder @Inject constructor(
    private val application: WalletApplication,
    private val config: Configuration,
    private val walletData: WalletData,
    private val walletUIConfig: WalletUIConfig,
    private val dashPayConfig: DashPayConfig
) {
    /** What the reminder should show. */
    sealed class Content {
        data class WithAmount(val balance: Coin) : Content()
        object AmountHidden : Content()
    }

    @WorkerThread
    fun maybeShow() {
        // Outside the lock: it may wait for a deferred wallet load.
        val walletExists = walletData.wallet != null
        // The reads and the post are one critical section with [cancel], so a reminder is
        // either posted before Reset Wallet cancels it, or sees the reset and is not posted.
        synchronized(postLock) {
            if (application.isWipeInProgress || !application.walletFileExists()) {
                log.info("wallet reset in progress or no wallet file, not showing inactivity reminder")
                return
            }
            show(walletExists)
        }
    }

    private fun show(walletExists: Boolean) {
        val content = decide(
            isTimeToRemind = config.isTimeToRemindBalance,
            walletExists = walletExists,
            transparentBalance = readTransparentBalance(),
            shieldedBalance = readShieldedBalance(),
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

    /** Null when unknown: never written, or unreadable. */
    private fun readTransparentBalance(): Coin? = try {
        runBlocking { walletUIConfig.getPreservingErrors(WalletUIConfig.LAST_TOTAL_BALANCE) }
            ?.let(Coin::valueOf)
    } catch (e: Exception) {
        log.warn("cannot read the last known transparent balance", e)
        null
    }

    /**
     * Zero when shielding is disabled, as on the home header. Null when unknown: the flag or
     * the balance is unreadable, or no balance is persisted (never synced, or a spend is
     * still reconciling).
     */
    private fun readShieldedBalance(): Coin? = try {
        runBlocking {
            val enabled = Constants.SUPPORTS_PLATFORM &&
                dashPayConfig.getPreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
            if (enabled) {
                dashPayConfig.getPreservingErrors(DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS)?.let(Coin::valueOf)
            } else {
                Coin.ZERO
            }
        }
    } catch (e: Exception) {
        log.warn("cannot read the last known shielded balance", e)
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
        private val postLock = Any()

        /**
         * Removes the reminder. Called by Reset Wallet, after the wipe flag is raised: a
         * reminder already posted for the old wallet goes, and one being prepared sees the
         * flag and is not posted.
         */
        @JvmStatic
        fun cancel(context: Context) {
            synchronized(postLock) {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(Constants.NOTIFICATION_ID_INACTIVITY)
            }
        }

        /** Null means no reminder. */
        fun decide(
            isTimeToRemind: Boolean,
            walletExists: Boolean,
            transparentBalance: Coin?,
            shieldedBalance: Coin?,
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
            val parts = listOf(transparentBalance, shieldedBalance)
            val known = parts.filterNotNull().fold(Coin.ZERO, Coin::add)
            if (!known.isPositive) {
                // no known funds: nothing to remind about, even if a part is unknown
                return null
            }
            val amountComplete = parts.none { it == null }
            return if (hideBalance || !amountComplete) Content.AmountHidden else Content.WithAmount(known)
        }
    }
}
