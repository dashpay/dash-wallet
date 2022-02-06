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

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
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

    override fun showNotification(tag: String, title: String, message: String) {
        val notification: NotificationCompat.Builder = NotificationCompat.Builder(
            appContext,
            Constants.NOTIFICATION_CHANNEL_ID_TRANSACTIONS
        )
        notification.setSmallIcon(R.drawable.ic_dash_d_white_bottom)
        notification.setTicker(title)
        notification.setContentTitle(title)
        notification.setContentText(message)
//        notification.setContentIntent(PendingIntent.getActivity(this, 0, createIntent(this), 0))
        notification.setWhen(System.currentTimeMillis())

        notificationManager.notify(tag.hashCode(), notification.build())
    }
}