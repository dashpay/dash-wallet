package de.schildbach.wallet.ui.dashpay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


class CreateIdentityNotification(
    val service: LifecycleService,
    private val blockchainIdentityDataDao: BlockchainIdentityConfig
) {

    private val notificationManager by lazy { service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    fun startServiceForeground() {
        notificationManager.cancel(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY_ERROR)
        service.startForeground(
                Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY,
                initialNotification())
        startObservingIdentityCreationState()
    }

    private fun initialNotification(): Notification {
        return createNotification(R.string.processing_home_title, 0, -1).build()
    }

    private fun displayStep1() {
        updateNotification(R.string.processing_home_title, R.string.processing_home_step_1, 4, 1)
    }

    private fun displayStep2(restoring: Boolean) {
        val message = if (restoring) R.string.processing_home_step_2_restoring else R.string.processing_home_step_2
        updateNotification(R.string.processing_home_title, message, 4, 2)
    }

    private fun displayStep3(restoring: Boolean) {
        val message = if (restoring) R.string.processing_home_step_3_restoring else R.string.processing_home_step_3
        updateNotification(R.string.processing_home_title, message, 4, 3)
    }

    private fun displayDone() {
        updateNotification(R.string.processing_done_subtitle, 0, 0, 0)
    }

    fun displayErrorAndStopService(isInvite: Boolean) {
        val messageResId = if (isInvite) R.string.processing_error_title_from_invite else R.string.processing_error_title
        val notification: Notification = createNotification(R.string.processing_home_title, messageResId, 0, 0).run {
//            setAutoCancel(true)
//            val startAppIntent = OnboardingActivity.createIntent(service)
//            val contentIntent = PendingIntent.getActivity(service, 0, startAppIntent, PendingIntent.FLAG_UPDATE_CURRENT)
//            setContentIntent(contentIntent)
            val actionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val serviceRetryIntent = if (isInvite) {
                    CreateIdentityService.createIntentForRetryFromInvite(service, true)
                } else {
                    CreateIdentityService.createIntentForRetry(service, true)
                }
                PendingIntent.getForegroundService(service, 0, serviceRetryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else {
                val serviceRetryIntent = if (isInvite) {
                    CreateIdentityService.createIntentForRetryFromInvite(service)
                } else {
                    CreateIdentityService.createIntentForRetry(service)
                }
                PendingIntent.getService(service, 0, serviceRetryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            addAction(R.drawable.ic_retry, service.getString(R.string.button_retry), actionIntent)
            build()
        }
        notificationManager.notify(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY_ERROR, notification)
        service.stopForeground(true)
        service.stopSelf()
    }

    private fun updateNotification(@StringRes titleResId: Int, @StringRes messageResId: Int,
                                   progressMax: Int, progress: Int) {
        val notification: Notification = createNotification(titleResId, messageResId, progressMax, progress).build()
        notificationManager.notify(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY, notification)
    }

    private fun createNotification(@StringRes titleResId: Int, @StringRes messageResId: Int = 0,
                                   progressMax: Int = 0, progress: Int = 0): NotificationCompat.Builder {
        return NotificationCompat.Builder(service, Constants.NOTIFICATION_CHANNEL_ID_DASHPAY).apply {
            setSmallIcon(R.drawable.ic_dash_d_white_bottom)
            setContentTitle(service.getString(titleResId))
            if (messageResId != 0) {
                setContentText(service.getString(messageResId))
            }
            when {
                (progressMax == -1) -> setProgress(100, 0, true)
                (progressMax > 0) -> setProgress(progressMax, progress, false)
                else -> setProgress(0, 0, false)
            }
        }
    }

    private fun startObservingIdentityCreationState() = blockchainIdentityDataDao.observeBase()
        .onEach {
            notificationManager.cancel(Constants.NOTIFICATION_ID_DASHPAY_CREATE_IDENTITY_ERROR)
            when (it?.creationState) {
                BlockchainIdentityData.CreationState.NONE,
                BlockchainIdentityData.CreationState.UPGRADING_WALLET,
                BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_CREATING,
                BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_SENDING,
                BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_SENT,
                BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_CONFIRMED -> {
                    displayStep1()
                }
                BlockchainIdentityData.CreationState.IDENTITY_REGISTERING,
                BlockchainIdentityData.CreationState.IDENTITY_REGISTERED -> {
                    displayStep2(it.restoring)
                }
                BlockchainIdentityData.CreationState.PREORDER_REGISTERING,
                BlockchainIdentityData.CreationState.PREORDER_REGISTERED,
                BlockchainIdentityData.CreationState.USERNAME_REGISTERING,
                BlockchainIdentityData.CreationState.USERNAME_REGISTERED,
                BlockchainIdentityData.CreationState.DASHPAY_PROFILE_CREATING,
                BlockchainIdentityData.CreationState.DASHPAY_PROFILE_CREATED -> {
                    displayStep3(it.restoring)
                }
                BlockchainIdentityData.CreationState.DONE -> {
                    displayDone()
                }
                else -> {
                    // ignore
                }
            }
        }.launchIn(service.lifecycleScope)
}
