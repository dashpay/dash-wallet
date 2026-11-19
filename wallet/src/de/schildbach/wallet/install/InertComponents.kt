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

package de.schildbach.wallet.install

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import de.schildbach.wallet_test.R

// Stand-ins that WalletAppComponentFactory returns when the install lacks its
// native libraries. None of them touches the wallet, Hilt or any native code.

private const val TAG = "IncompleteInstall"

/** Replaces WalletApplication: no Hilt graph, no wallet start-up. */
class IncompleteInstallApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "native libraries are missing from this install; the wallet will not start")
        // One non-fatal per process start, so Crashlytics shows how many installs
        // are affected (this used to be a fatal crash at the same point). A build
        // without google-services.json has no FirebaseApp, and reports nothing.
        try {
            FirebaseApp.initializeApp(this)
            if (FirebaseApp.getApps(this).isEmpty()) {
                return
            }
            FirebaseCrashlytics.getInstance().recordException(
                IncompleteInstallException(NativeLibraryCheck.details)
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not report the incomplete install", e)
        }
    }
}

/** Reported to Crashlytics; carries ABIs and split names only. */
class IncompleteInstallException(details: String) :
    IllegalStateException("native libraries missing from the installed APKs: $details")

/** Replaces every broadcast receiver: boot, package-replaced, widget and alarm broadcasts do nothing. */
class InertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}

/** Replaces every job service: jobs that are still scheduled finish at once. */
class InertJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean = false

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/**
 * Replaces every other service and stops at once.
 *
 * An alarm scheduled by a previous, complete install may start a foreground
 * service with `PendingIntent.getForegroundService`. The system kills an app whose
 * service stops without calling `startForeground`, so a service declared with a
 * foreground type briefly enters the foreground before it stops.
 */
class InertService(private val declaredClassName: String) : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (declaresForegroundType()) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                // Not started as a foreground service (no requirement to meet), or the
                // system refused a background foreground start: either way, just stop.
                Log.w(TAG, "could not enter the foreground before stopping", e)
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun declaresForegroundType(): Boolean {
        return try {
            packageManager.getServiceInfo(ComponentName(this, declaredClassName), 0).foregroundServiceType != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.incomplete_install_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dash_d_white)
            .setContentTitle(getString(R.string.incomplete_install_title))
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "incomplete_install"
        const val NOTIFICATION_ID = 0x1D57A11
    }
}

/** Replaces every content provider (except Firebase's): empty and read-only. */
class InertProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
