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

package de.schildbach.wallet.service.platform.sdk

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory

/**
 * The background half of the "SDK setup pending" surface: when the app is not
 * on screen and the SDK bind is blocked, this is the only thing that can tell
 * the user what the wallet is waiting for.
 *
 * Two forms (see [SdkBindRetryService.pendingNoticeFor]): the SETUP notice
 * ("finish the wallet update") for a wallet still finishing its first SDK
 * setup, or a blocker that needs the user; and the routine SYNC reminder
 * ("unlock your phone to sync"), posted once per process after a wallet that
 * has bound before has been unable to bind for an hour. Only the setup notice
 * is ongoing; the reminder is dismissible and cancels itself on tap.
 *
 * The SETUP notice is ONGOING, and that is load-bearing rather than cosmetic. The design assumed
 * the `ACTION_USER_PRESENT` receiver would heal a locked-keystore deferral
 * without the user. It cannot: the 2026-09-16 emulator upgrade test showed the
 * cached-app freezer suspending the wallet process 30 seconds after the
 * package-replaced broadcast, with the platform logging "Sending oneway calls
 * to frozen process" while two `USER_PRESENT` broadcasts went out. A frozen
 * process runs no context-registered receiver, so nothing retried until the
 * app was opened, and the bind then completed 1.6 seconds later. Joel's HONOR
 * device delivered zero `USER_PRESENT` broadcasts in ten hours for a related
 * OEM reason. So the notification, not the receiver, is what actually gets the
 * wallet bound — it must not be swiped away while the bind is still blocked.
 *
 * The setup notice is re-posted on every classified failure (see
 * [SdkBindRetryService]), so a dismissal on a platform that allows one is
 * repaired by the next retry. Both forms are cleared the moment the bind
 * succeeds or the app comes to the foreground.
 */
object SdkBindPendingNotification {
    private val log = LoggerFactory.getLogger(SdkBindPendingNotification::class.java)

    const val TAG = "sdk-bind-pending"
    private val NOTIFICATION_ID = TAG.hashCode()

    /**
     * @param routine a sync reminder for a wallet whose bind has worked before
     *   (see [SdkBindRetryService]): the sync wording, and dismissible, since
     *   nothing is stuck. Otherwise the setup wording, ongoing as above.
     */
    fun show(context: Context, blocker: SdkBindBlocker, routine: Boolean = false) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                log.warn("SDK-setup-pending notification NOT shown: notifications are disabled for this app")
                return
            }
            val texts = SdkBindPendingTexts.forBlocker(context, blocker, routine)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentIntent = launch?.let {
                PendingIntent.getActivity(
                    context, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID_GENERIC)
                .setSmallIcon(R.drawable.ic_dash_d_white_bottom)
                .setContentTitle(texts.title)
                .setContentText(texts.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texts.message))
                .setOngoing(!routine)
                .setAutoCancel(routine)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            log.warn("could not post the SDK-setup-pending notification", t)
        }
    }

    fun clear(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            log.warn("could not clear the SDK-setup-pending notification", t)
        }
    }
}

/** Title/message pairs per blocker, shared by the notification and the sheet. */
data class SdkBindPendingTexts(val title: String, val message: String) {
    companion object {
        /**
         * @param routine the sync-reminder wording for a wallet that has bound
         *   before (a locked or briefly refusing keystore, not an unfinished
         *   update). Ignored for the blockers that need the user.
         */
        fun forBlocker(context: Context, blocker: SdkBindBlocker, routine: Boolean = false): SdkBindPendingTexts =
            if (routine && !blocker.needsUser) routineTexts(context, blocker) else setupTexts(context, blocker)

        private fun routineTexts(context: Context, blocker: SdkBindBlocker): SdkBindPendingTexts =
            if (blocker == SdkBindBlocker.DEVICE_LOCKED) {
                SdkBindPendingTexts(
                    context.getString(R.string.sdk_bind_pending_sync_locked_title),
                    context.getString(R.string.sdk_bind_pending_sync_locked_message)
                )
            } else {
                SdkBindPendingTexts(
                    context.getString(R.string.sdk_bind_pending_sync_other_title),
                    context.getString(R.string.sdk_bind_pending_sync_other_message)
                )
            }

        private fun setupTexts(context: Context, blocker: SdkBindBlocker): SdkBindPendingTexts = when (blocker) {
            SdkBindBlocker.DEVICE_LOCKED -> SdkBindPendingTexts(
                context.getString(R.string.sdk_bind_pending_locked_title),
                context.getString(R.string.sdk_bind_pending_locked_message)
            )
            SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED, SdkBindBlocker.OTHER -> SdkBindPendingTexts(
                context.getString(R.string.sdk_bind_pending_finishing_title),
                context.getString(R.string.sdk_bind_pending_finishing_message)
            )
            SdkBindBlocker.KEYSTORE_PROBLEM -> SdkBindPendingTexts(
                context.getString(R.string.sdk_bind_problem_title),
                context.getString(R.string.sdk_bind_problem_message)
            )
            SdkBindBlocker.SETUP_FAILED -> SdkBindPendingTexts(
                context.getString(R.string.sdk_bind_failed_title),
                context.getString(R.string.sdk_bind_failed_message)
            )
        }
    }
}
