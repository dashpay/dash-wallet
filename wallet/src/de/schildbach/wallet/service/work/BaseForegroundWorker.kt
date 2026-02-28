package de.schildbach.wallet.service.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import de.schildbach.wallet_test.R
import org.slf4j.LoggerFactory

abstract class BaseForegroundWorker(
    context: Context,
    parameters: WorkerParameters,
    private val channelId: String,
    private val notificationId: Int,
    private val workName: String,
    private val initialTitle: String,
    private val initialContent: String
): BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(BaseForegroundWorker::class.java)
    }
    private val notificationManager = NotificationManagerCompat.from(context)
    abstract suspend fun doWorkInForeground(inForeground: Boolean): Result

    override suspend fun doWorkWithBaseProgress(): Result {
        return try {
            // Ensure permission is granted before proceeding
            if (hasNotificationPermission()) {
                setForegroundAsync(createForegroundInfo())
                doWorkInForeground(true)
            } else {
                // Log or handle the case where permission is not granted
                log.info("executing work in background")
                doWorkInForeground(false)
            }
        } catch (e: SecurityException) {
            // Log and handle SecurityException gracefully
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return notificationManager.areNotificationsEnabled() && if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannelIfNeeded()
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(initialTitle)
            .setContentText(initialContent)
            .setSmallIcon(R.drawable.ic_dash_pay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 1, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)

        }
    }

    protected fun updateNotification(contentTitle: String, contentText: String, progressMax: Int, progress: Int) {
        if (!hasNotificationPermission()) return

        val updatedNotification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_dash_pay)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progressMax, progress, false)
            .build()

        try {
            notificationManager.notify(notificationId, updatedNotification)
        } catch (e: SecurityException) {
            e.printStackTrace() // Handle gracefully
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                workName,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}