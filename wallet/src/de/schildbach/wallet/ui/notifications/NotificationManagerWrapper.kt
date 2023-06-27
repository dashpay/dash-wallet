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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.NotificationService
import javax.inject.Inject

class NotificationManagerWrapper @Inject constructor(
    @ApplicationContext private val appContext: Context
): NotificationService {
    private var notificationManager = appContext.getSystemService(
        Context.NOTIFICATION_SERVICE
    ) as NotificationManager

    override val isDoNotDisturb: Boolean
        get() {
            return notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
                notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        }

    override fun showNotification(
        tag: String,
        message: String,
        isOngoing: Boolean,
        intent: Intent?
    ) {
        val notification = buildNotification(message, isOngoing, intent)
        notificationManager.notify(tag.hashCode(), notification)
    }

    override fun buildNotification(
        message: String,
        isOngoing: Boolean,
        intent: Intent?
    ): Notification {
        val appName = appContext.getString(R.string.app_name)
        val notification: NotificationCompat.Builder = NotificationCompat.Builder(
            appContext,
            if (isOngoing) Constants.NOTIFICATION_CHANNEL_ID_ONGOING else Constants.NOTIFICATION_CHANNEL_ID_GENERIC
        ).setSmallIcon(R.drawable.ic_dash_d_white_bottom)
            .setTicker(appName)
            .setContentTitle(appName)
            .setContentText(message)

        if (intent != null) {
            val pendingIntent = TaskStackBuilder.create(appContext).run {
                addNextIntentWithParentStack(intent)
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }

            notification.setContentIntent(pendingIntent).setAutoCancel(true)
        }

        return notification.build()
    }
}
