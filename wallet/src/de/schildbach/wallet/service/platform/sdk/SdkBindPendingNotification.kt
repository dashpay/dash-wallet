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
 * ONGOING, and that is load-bearing rather than cosmetic. The design assumed
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
 * Re-posted on every classified failure (see [SdkBindRetryService]), so a
 * dismissal on a platform that allows one is repaired by the next retry, and
 * cleared the moment the bind succeeds or the app comes to the foreground.
 */
object SdkBindPendingNotification {
    private val log = LoggerFactory.getLogger(SdkBindPendingNotification::class.java)

    const val TAG = "sdk-bind-pending"
    private val NOTIFICATION_ID = TAG.hashCode()

    fun show(context: Context, blocker: SdkBindBlocker) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                log.warn("SDK-setup-pending notification NOT shown: notifications are disabled for this app")
                return
            }
            val texts = SdkBindPendingTexts.forBlocker(context, blocker)
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
                .setOngoing(true)
                .setAutoCancel(false)
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
        fun forBlocker(context: Context, blocker: SdkBindBlocker): SdkBindPendingTexts = when (blocker) {
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
