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

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.notifications.NotificationManagerWrapper
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory

/**
 * The background half of the "SDK setup pending" surface: when the app is
 * not on screen and the SDK bind is blocked, this is the only thing that can
 * tell the user what the wallet is waiting for. Posted on the generic
 * channel, replaced in place on classification changes, cleared on bind
 * success or when the app comes to the foreground (the sheet takes over).
 */
object SdkBindPendingNotification {
    private val log = LoggerFactory.getLogger(SdkBindPendingNotification::class.java)

    const val TAG = "sdk-bind-pending"

    fun show(context: Context, blocker: SdkBindBlocker) {
        try {
            val texts = SdkBindPendingTexts.forBlocker(context, blocker)
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            NotificationManagerWrapper(context).showNotification(
                TAG,
                texts.message,
                texts.title,
                null,
                launch,
                Constants.NOTIFICATION_CHANNEL_ID_GENERIC
            )
        } catch (t: Throwable) {
            log.warn("could not post the SDK-setup-pending notification", t)
        }
    }

    fun clear(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(TAG.hashCode())
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
