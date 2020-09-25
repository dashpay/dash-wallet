package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.security.SecurityGuard

class SendContactRequestOperation(application: Application) {

    companion object {
        const val WORK_NAME = "SendContactRequest.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(SendContactRequestWorker::class.qualifiedName!!)

    /**
     * Gets the WorkInfo of SendContactRequestWorker for given toUserId and converts it to Resource
     */
    fun operationStatus(toUserId: String) = workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(toUserId)).switchMap {
        return@switchMap liveData {

            if (it.isNullOrEmpty()) {
                return@liveData
            }

            if (it.size > 1) {
                throw RuntimeException("there should never be more than one unique work ${uniqueWorkName(toUserId)}")
            }

            val workInfo = it[0]
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val userIdOut = SendContactRequestWorker.extractUserId(workInfo.outputData)!!
                    val toUserIdOut = SendContactRequestWorker.extractToUserId(workInfo.outputData)!!
                    emit(Resource.success(Pair(userIdOut, toUserIdOut)))
                }
                WorkInfo.State.FAILED -> {
                    val errorMessage = BaseWorker.extractError(workInfo.outputData)
                    emit(if (errorMessage != null) {
                        Resource.error(errorMessage)
                    } else {
                        Resource.error(Exception())
                    })
                }
                WorkInfo.State.CANCELLED -> {
                    emit(Resource.canceled(null))
                }
                else -> {
                    emit(Resource.loading(null))
                }
            }
        }
    }

    @SuppressLint("EnqueueWork")
    fun create(context: Context, toUserId: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(
                        SendContactRequestWorker.KEY_PASSWORD to password,
                        SendContactRequestWorker.KEY_TO_USER_ID to toUserId))
                .build()

        return WorkManager.getInstance(context)
                .beginUniqueWork(uniqueWorkName(toUserId),
                        ExistingWorkPolicy.REPLACE,
                        sendContactRequestWorker)
    }

}