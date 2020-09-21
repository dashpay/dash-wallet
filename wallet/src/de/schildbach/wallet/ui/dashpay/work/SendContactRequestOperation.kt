package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.security.SecurityGuard

class SendContactRequestOperation(application: Application) {

    companion object {
        const val WORK_NAME = "SendContactRequest.WORK#"
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    private val operationStatusData = mutableMapOf<String, MutableLiveData<Resource<Pair<String, String>>>>()
    fun operationStatus(userId: String) = workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME + userId).switchMap {
        if (!operationStatusData.containsKey(userId)) {
            operationStatusData[userId] = MutableLiveData()
        }
        val userOperationStatusData = operationStatusData[userId]!!
        if (it == null) {
            return@switchMap userOperationStatusData
        }
        val statuses = mutableSetOf<WorkInfo.State>()
        var sendContactRequestWorkInfo: WorkInfo? = null
        var errorMessage: String? = null
        it.forEach { workInfo ->
            statuses.add(workInfo.state)
            if (workInfo.tags.contains(SendContactRequestWorker::class.qualifiedName)) {
                sendContactRequestWorkInfo = workInfo
            }
            if (workInfo.outputData.hasKeyWithValueOfType<String>(BaseWorker.KEY_ERROR_MESSAGE)) {
                errorMessage = BaseWorker.extractError(workInfo.outputData)
            }
        }
        val allWorkersSucceeded = statuses.size == 1 && statuses.contains(WorkInfo.State.SUCCEEDED)

        userOperationStatusData.apply {
            if (value != null || !allWorkersSucceeded) {
                when {
                    allWorkersSucceeded -> {
                        sendContactRequestWorkInfo!!.apply {
                            val userIdOut = SendContactRequestWorker.extractUserId(outputData)!!
                            val toUserIdOut = SendContactRequestWorker.extractToUserId(outputData)!!
                            value = Resource.success(Pair(userIdOut, toUserIdOut))
                        }
                    }
                    statuses.contains(WorkInfo.State.FAILED) -> {
                        value = if (errorMessage != null) {
                            Resource.error(errorMessage!!)
                        } else {
                            Resource.error(Exception())
                        }
                    }
                    statuses.contains(WorkInfo.State.ENQUEUED) || statuses.contains(WorkInfo.State.RUNNING) -> {
                        if (value?.status != Status.LOADING) {
                            value = Resource.loading(null)
                        }
                    }
                }
            }
        }
        return@switchMap userOperationStatusData
    }

    @SuppressLint("EnqueueWork")
    fun create(context: Context, userId: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val deriveKeyWorker = OneTimeWorkRequestBuilder<DeriveKeyWorker>()
                .setInputData(workDataOf(DeriveKeyWorker.KEY_PASSWORD to password))
                .build()

        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(SendContactRequestWorker.KEY_USER_ID to userId))
                .build()

        return WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME + userId,
                        ExistingWorkPolicy.REPLACE,
                        deriveKeyWorker)
                .then(sendContactRequestWorker)
    }
}