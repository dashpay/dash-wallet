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

class SendContactRequestOperation(val application: Application) {

    class SendContactRequestOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(SendContactRequestOperation::class.java)

        const val WORK_NAME = "SendContactRequest.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId

        fun operationStatus(
            application: Application,
            toUserId: String,
            analytics: AnalyticsService
        ): LiveData<Resource<Pair<String, String>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(toUserId)).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    if (it.size > 1) {
                        val e = RuntimeException("there should never be more than one unique work ${uniqueWorkName(toUserId)}")
                        throw e
                    }

                    val workInfo = it[0]
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val userIdOut = SendContactRequestWorker.extractUserId(workInfo.outputData)!!
                            val toUserIdOut = SendContactRequestWorker.extractToUserId(workInfo.outputData)!!
                            emit(Resource.success(Pair(userIdOut, toUserIdOut)))
                            log.info("send contact request operation successful: $workInfo")
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = BaseWorker.extractError(workInfo.outputData)
                            emit(if (errorMessage != null) {
                                val exception = SendContactRequestOperationException(errorMessage)
                                Resource.error(errorMessage, null)
                            } else {
                                val exception = SendContactRequestOperationException("Unknown error")
                                Resource.error(exception)
                            })
                            log.error("send contact request operation failed: $errorMessage")
                        }
                        WorkInfo.State.CANCELLED -> {
                            log.info("send contact request operation cancelled")
                            emit(Resource.canceled(null))
                        }
                        else -> {
                            emit(Resource.loading(null))
                        }
                    }
                }
            }
        }

        fun allOperationsStatus(application: Application): LiveData<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosByTagLiveData(SendContactRequestWorker::class.qualifiedName!!).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    it.forEach {
                        var toUserId = ""
                        it.tags.forEach { tag ->
                            if (tag.startsWith("toUserId:")) {
                                toUserId = tag.replace("toUserId:", "")
                            }
                        }
                        result[toUserId] = convertState(it)
                    }
                    emit(result)
                }
            }
        }

        private fun convertState(workInfo: WorkInfo): Resource<WorkInfo> {
            return when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    Resource.success(workInfo)
                }
                WorkInfo.State.FAILED -> {
                    val errorMessage = BaseWorker.extractError(workInfo.outputData)
                    if (errorMessage != null) {
                        Resource.error(errorMessage, workInfo)
                    } else {
                        Resource.error(Exception(), workInfo)
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    Resource.canceled(workInfo)
                }
                else -> {
                    Resource.loading(workInfo)
                }
            }
        }
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(SendContactRequestWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(toUserId: String): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(
                        SendContactRequestWorker.KEY_PASSWORD to password,
                        SendContactRequestWorker.KEY_TO_USER_ID to toUserId))
                .addTag("toUserId:$toUserId")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(toUserId),
                        ExistingWorkPolicy.KEEP,
                        sendContactRequestWorker)
    }

}