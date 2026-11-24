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
import de.schildbach.wallet.AppForegroundMonitor
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.balanceOwnerId
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ContextPropagatingThreadFactory
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.lastKnownBalance
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * The "you've still got Dash on this device" reminder, shown on a reboot ([BootstrapReceiver])
 * or an app update ([PackageReplacedReceiver]) when the user hasn't opened the app for a while.
 *
 * The amount is what the home header shows, from persisted last-known values, not the dashj
 * wallet's own balance: after the cutover to the SDK the dashj wallet is held and its balance
 * is frozen. A wallet load in a receiver process may start the SDK pipelines, but the
 * reminder does not wait for them to publish a balance. It uses the persisted values the
 * app shows before the live balance arrives (the home header's L1 figure, and the shielded
 * card's balance).
 * - Transparent (L1): [WalletUIConfig.LAST_TOTAL_BALANCE], written by the dashj balance
 *   observer before the cutover and by the SDK pipeline after it.
 * - Shielded: zero when shielding is disabled, otherwise
 *   [DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS], written only from a READY runtime and
 *   removed while a local shielded spend reconciles; unknown when absent.
 *
 * Each stored value carries the id of the wallet that wrote it, and counts only when that is
 * the installed wallet ([lastKnownBalance]): a wallet restored from the recovery screen,
 * which installs without a reset, never shows the previous wallet's amount. A value an
 * earlier version stored has no id and counts as unknown until the installed wallet writes
 * its own.
 *
 * A part that cannot be known never makes the amount smaller: the reminder is shown without an amount
 * when the known parts are positive, and not shown at all when they are not. The amount is
 * also left out when Autohide Balance is on or cannot be read, and when shielded funds are
 * part of it: notification text can be read by notification listeners and on the lock screen.
 */
class InactivityReminder @Inject constructor(
    private val application: WalletApplication,
    private val config: Configuration,
    private val walletData: WalletData,
    private val walletUIConfig: WalletUIConfig,
    private val dashPayConfig: DashPayConfig,
    private val applicationScope: CoroutineScope
) {
    /** What the reminder should show. */
    sealed class Content {
        data class WithAmount(val balance: Coin) : Content()
        object AmountHidden : Content()
    }

    /** Why [maybeShow] did not post, when the reason may pass. */
    private enum class Outcome { DONE, BALANCES_UNKNOWN }

    /**
     * Shows the reminder when it is due. When it is due but no part of the balance is known
     * to be funded and a part is unknown (the first update: the stored balances an earlier
     * version wrote carry no wallet id), it tries again as each balance is stored as this
     * wallet's, for up to [RETRY_WINDOW_MS] and only while the app is in the background.
     */
    @WorkerThread
    fun maybeShow() {
        if (showIfDue() == Outcome.BALANCES_UNKNOWN) retryWhenBalancesStored()
    }

    private fun retryWhenBalancesStored() {
        if (!retrying.compareAndSet(false, true)) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(RETRY_WINDOW_MS) {
                    combine(
                        walletUIConfig.observe(WalletUIConfig.LAST_TOTAL_BALANCE_WALLET),
                        dashPayConfig.observe(DashPayConfig.LAST_SHIELDED_BALANCE_WALLET),
                        AppForegroundMonitor.isForeground
                    ) { transparentOwner, shieldedOwner, foreground ->
                        Triple(transparentOwner, shieldedOwner, foreground)
                    }
                        .distinctUntilChanged()
                        // Opened: the user is back in the app, so no reminder is due.
                        .takeWhile { (_, _, foreground) -> !foreground }
                        // The first check also covers a balance stored since the skipped one.
                        .firstOrNull { showIfDue() != Outcome.BALANCES_UNKNOWN }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("inactivity reminder retry failed", e)
            } finally {
                retrying.set(false)
            }
        }
    }

    @WorkerThread
    private fun showIfDue(): Outcome {
        // Outside the lock: it may wait for a deferred wallet load.
        val wallet = walletData.wallet
        val walletId = try {
            wallet?.balanceOwnerId()
        } catch (e: Exception) {
            log.warn("cannot fingerprint the wallet; its stored balances are unknown", e)
            null
        }
        // The reads and the post are one critical section with [cancel], so a reminder is
        // either posted before Reset Wallet cancels it, or sees the reset and is not posted.
        synchronized(postLock) {
            if (!application.mayActOnWalletInBackground()) {
                log.info("wallet reset in progress, needs recovery or no wallet file: not showing inactivity reminder")
                return Outcome.DONE
            }
            // Replaced since it was read (an install publishes the new wallet, then cancels
            // under this lock): its stored balances are not the installed wallet's.
            if (wallet != null && application.loadedWalletOrNull() !== wallet) {
                log.info("wallet replaced while preparing the inactivity reminder: not showing it")
                return Outcome.DONE
            }
            return show(walletExists = wallet != null, walletId = walletId)
        }
    }

    private fun show(walletExists: Boolean, walletId: String?): Outcome {
        val isTimeToRemind = config.isTimeToRemindBalance
        val transparent = readTransparentBalance(walletId)
        val shielded = readShieldedBalance(walletId)
        val content = decide(
            isTimeToRemind = isTimeToRemind,
            walletExists = walletExists,
            transparentBalance = transparent,
            shieldedBalance = shielded,
            hideBalance = readHideBalance()
        ) ?: return if (mayBecomeKnown(isTimeToRemind, walletId, transparent, shielded)) {
            log.info("inactivity reminder due, but its balances are not known yet: waiting for them")
            Outcome.BALANCES_UNKNOWN
        } else {
            Outcome.DONE
        }

        log.info("detected balance, showing inactivity notification")
        val text = when (content) {
            is Content.WithAmount -> application.getString(
                R.string.notification_inactivity_message,
                config.format.format(org.dash.wallet.common.money.Coin.valueOf(content.balance.value))
            )
            Content.AmountHidden -> application.getString(R.string.notification_inactivity_message_hidden)
        }
        post(text)
        return Outcome.DONE
    }

    /** Null when unknown: never written, another wallet's or an earlier version's, or unreadable. */
    private fun readTransparentBalance(walletId: String?): Coin? = try {
        runBlocking { walletUIConfig.snapshotPreservingErrors() }
            .lastKnownBalance(
                WalletUIConfig.LAST_TOTAL_BALANCE,
                WalletUIConfig.LAST_TOTAL_BALANCE_WALLET,
                walletId
            )
            ?.let(Coin::valueOf)
    } catch (e: Exception) {
        log.warn("cannot read the last known transparent balance", e)
        null
    }

    /**
     * Zero when shielding is disabled or unset, as on the home header. When it is enabled,
     * the last balance persisted from a READY runtime; null (unknown) when there is none,
     * or the flag or the balance is unreadable.
     */
    private fun readShieldedBalance(walletId: String?): Coin? = try {
        val enabled = Constants.SUPPORTS_PLATFORM &&
            runBlocking { dashPayConfig.getPreservingErrors(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } == true
        if (!enabled) {
            Coin.ZERO
        } else {
            runBlocking { dashPayConfig.snapshotPreservingErrors() }
                .lastKnownBalance(
                    DashPayConfig.LAST_SHIELDED_BALANCE_DUFFS,
                    DashPayConfig.LAST_SHIELDED_BALANCE_WALLET,
                    walletId
                )
                ?.let(Coin::valueOf)
        }
    } catch (e: Exception) {
        log.warn("cannot read the shielded balance, treating it as unknown", e)
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

        /** How long a due reminder waits for its balances to be stored as this wallet's. */
        internal const val RETRY_WINDOW_MS = 60 * 60_000L

        /** One wait at a time, process-wide. */
        private val retrying = AtomicBoolean(false)

        /**
         * Whether a reminder [decide] skipped may be shown once a balance is stored: it is
         * due, the wallet is identified, and a part of its balance is still unknown.
         */
        fun mayBecomeKnown(isTimeToRemind: Boolean, walletId: String?, transparent: Coin?, shielded: Coin?): Boolean =
            isTimeToRemind && walletId != null && (transparent == null || shielded == null)

        /**
         * Removes the reminder, under the same lock as posting. Called by Reset Wallet after
         * the wipe flag is raised, and by the dismiss actions after they update the reminder
         * settings: a reminder already posted goes, and one being prepared either posts first
         * (and is removed here) or reads the new state and is not posted.
         */
        @JvmStatic
        fun cancel(context: Context) {
            synchronized(postLock) {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(Constants.NOTIFICATION_ID_INACTIVITY)
            }
        }

        /**
         * Removes a posted reminder, then runs [hideAmounts] (the Autohide Balance write),
         * both under the posting lock: no reminder carrying an amount is posted between the
         * two, and one being prepared reads the new setting. Removing first means a process
         * death before the write leaves no amount on screen. Blocks; off the main thread.
         */
        @WorkerThread
        @JvmStatic
        fun <T> cancelThen(context: Context, hideAmounts: () -> T): T =
            synchronized(postLock) {
                cancel(context)
                hideAmounts()
            }

        private val cancelExecutor: Executor =
            Executors.newSingleThreadExecutor(ContextPropagatingThreadFactory("inactivity-reminder-cancel"))

        /**
         * [cancel] on a background thread, for callers on the main thread: a reminder being
         * prepared holds the lock across its preference reads. The caller must already have
         * made the reminder ineligible (raised the wipe flag, written the setting), so one
         * that posts before this runs is still removed by it, and none posts after.
         */
        @JvmStatic
        fun cancelInBackground(context: Context) {
            val appContext = context.applicationContext ?: context
            cancelExecutor.execute {
                try {
                    cancel(appContext)
                } catch (e: Exception) {
                    log.warn("could not cancel the inactivity reminder", e)
                }
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
            // Shielded funds stay out of the text: it reaches notification listeners and
            // the lock screen, and the reminder setting never asked to share them.
            val holdsShielded = shieldedBalance?.isPositive ?: true
            return if (hideBalance || !amountComplete || holdsShielded) {
                Content.AmountHidden
            } else {
                Content.WithAmount(known)
            }
        }
    }
}
