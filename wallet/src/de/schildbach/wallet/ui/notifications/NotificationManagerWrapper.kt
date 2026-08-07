/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.NotificationService
import org.slf4j.LoggerFactory
import javax.inject.Inject

class NotificationManagerWrapper @Inject constructor(
    @ApplicationContext private val appContext: Context
): NotificationService {
    companion object {
        private val log = LoggerFactory.getLogger(NotificationManagerWrapper::class.java)
    }

    private var notificationManager = appContext.getSystemService(
        Context.NOTIFICATION_SERVICE
    ) as NotificationManager

    override val isDoNotDisturb: Boolean
        get() {
            return notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        }

    /**
     * On Android 13+ (API 33) `notify()` is a SILENT no-op unless the
     * POST_NOTIFICATIONS runtime permission is granted, and on every version
     * it is a no-op when the user turned the app's notifications off. That
     * made promises like the shielded transfer's "We will notify you when
     * it's done" quietly unkeepable with no trace anywhere. The interface
     * returns Unit (a Boolean would churn every implementation and mock), so
     * the un-shown case is LOGGED instead — visible to QA in wallet.log.
     */
    override fun showNotification(
        tag: String,
        message: String,
        title: String?,
        imageUrl: String?,
        intent: Intent?,
        channelId: String?
    ) {
        if (!notificationsAllowed()) {
            log.warn(
                "notification '{}' NOT shown: notifications are disabled for this app " +
                    "(POST_NOTIFICATIONS granted: {})",
                tag, postNotificationsGranted()
            )
            return
        }
        val notification = buildNotification(message, title, imageUrl, intent, channelId)
        notificationManager.notify(tag.hashCode(), notification)
    }

    /** Whether a posted notification can actually surface (app-level switch + API 33+ permission). */
    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    /** API 33+ runtime permission state; always true below API 33 (install-time grant). */
    private fun postNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    override fun buildNotification(
        message: String,
        title: String?,
        imageUrl: String?,
        intent: Intent?,
        channelId: String?
    ): Notification {
        val appName = appContext.getString(R.string.app_name)
        val notification: NotificationCompat.Builder = NotificationCompat.Builder(
            appContext,
            channelId ?: Constants.NOTIFICATION_CHANNEL_ID_GENERIC
        ).setSmallIcon(R.drawable.ic_dash_d_white_bottom)
            .setTicker(appName)
            .setContentTitle(title ?: appName)
            .setContentText(message)

        if (intent != null) {
            val pendingIntent = TaskStackBuilder.create(appContext).run {
                addNextIntentWithParentStack(intent)
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }

            notification.setContentIntent(pendingIntent).setAutoCancel(true)
        }

        if (imageUrl != null) {
            val futureTarget = Glide.with(appContext)
                .asBitmap()
                .load(imageUrl)
                .submit()

            val bitmap = futureTarget.get()

            notification
                .setLargeIcon(bitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                )

            Glide.with(appContext).clear(futureTarget)
        }

        return notification.build()
    }
}
