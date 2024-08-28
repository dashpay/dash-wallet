package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.security.SecurityGuard
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

class BroadcastIdentityVerifyOperation(val application: Application) {

    class SendContactRequestOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(BroadcastIdentityVerifyOperation::class.java)

        private const val WORK_NAME = "BroadcastIdentityVerify.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(SendContactRequestWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(username: String, url: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(
                        BroadcastIdentityVerifyWorker.KEY_PASSWORD to password,
                        BroadcastIdentityVerifyWorker.KEY_USERNAME to username,
                        BroadcastIdentityVerifyWorker.KEY_URL to url
                    )
                )
                .addTag("username:$username")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(username),
                        ExistingWorkPolicy.KEEP,
                        sendContactRequestWorker)
    }
}