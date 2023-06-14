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
import com.bumptech.glide.Glide
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R

@AndroidEntryPoint
class PushMessagingService : FirebaseMessagingService() {
    companion object {
        private const val FOREGROUND_KEY = "foreground"
    }

    override fun onNewToken(token: String) { }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.containsKey(FOREGROUND_KEY) &&
            remoteMessage.data[FOREGROUND_KEY]!!.lowercase() != "true"
        ) {
            return
        }

        val notificationBuilder = NotificationCompat.Builder(this, getString(R.string.fcm_notification_channel_id))
            .setSmallIcon(R.drawable.ic_dash_d_white)
            .setContentTitle(remoteMessage.notification?.title)
            .setContentText(remoteMessage.notification?.body)
            .setAutoCancel(true)

        remoteMessage.notification?.imageUrl?.let { image ->
            val futureTarget = Glide.with(this)
                .asBitmap()
                .load(image)
                .submit()

            val bitmap = futureTarget.get()

            notificationBuilder
                .setLargeIcon(bitmap)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null)
                )

            Glide.with(this).clear(futureTarget)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify("firebase_push".hashCode(), notificationBuilder.build())
    }
}
