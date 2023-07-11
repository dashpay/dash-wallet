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

import android.content.Intent
import androidx.core.os.bundleOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.NotificationService
import javax.inject.Inject

@AndroidEntryPoint
class PushMessagingService : FirebaseMessagingService() {
    companion object {
        private const val FOREGROUND_KEY = "foreground"
    }

    @Inject
    lateinit var notificationService: NotificationService

    override fun onNewToken(token: String) { }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.containsKey(FOREGROUND_KEY) &&
            remoteMessage.data[FOREGROUND_KEY]!!.lowercase() != "true"
        ) {
            return
        }

        notificationService.showNotification(
            tag = "firebase_push",
            message = remoteMessage.notification?.body ?: "",
            title = remoteMessage.notification?.title,
            imageUrl = remoteMessage.notification?.imageUrl?.toString(),
            intent = Intent(this, WalletActivity::class.java).apply {
                putExtras(bundleOf(*remoteMessage.data.map { it.key to it.value }.toTypedArray()))
            },
            channelId = getString(R.string.fcm_notification_channel_id)
        )
    }
}
