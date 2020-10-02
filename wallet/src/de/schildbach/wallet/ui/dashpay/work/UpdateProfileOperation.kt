package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.security.SecurityGuard

class UpdateProfileOperation(val application: Application) {

    companion object {
        const val WORK_NAME = "UpdateProfile.WORK#"

        fun uniqueWorkName(userId: String) = WORK_NAME + userId

        fun operationStatus(application: Application, userId: String): LiveData<Resource<String>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(userId)).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    if (it.size > 1) {
                        throw RuntimeException("there should never be more than one unique work ${uniqueWorkName(userId)}")
                    }

                    val workInfo = it[0]
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val userId = SendContactRequestWorker.extractUserId(workInfo.outputData)!!
                            emit(Resource.success(userId))
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
        }

        fun allOperationsStatus(application: Application): LiveData<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            workManager.pruneWork()
            return workManager.getWorkInfosByTagLiveData(UpdateProfileRequestWorker::class.qualifiedName!!).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    it.forEach {
                        var userId = ""
                        it.tags.forEach { tag ->
                            if (tag.startsWith("dashPayProfile:")) {
                                userId = tag.replace("dashPayProfile:", "")
                            }
                        }
                        result[userId] = convertState(it)
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
    fun create(dashPayProfile: DashPayProfile): WorkContinuation {

        val password = SecurityGuard().retrievePassword()
        val updateProfileWorker = OneTimeWorkRequestBuilder<UpdateProfileRequestWorker>()
                .setInputData(workDataOf(
                        UpdateProfileRequestWorker.KEY_PASSWORD to password,
                        UpdateProfileRequestWorker.KEY_DISPLAY_NAME to dashPayProfile.displayName,
                        UpdateProfileRequestWorker.KEY_PUBLIC_MESSAGE to dashPayProfile.publicMessage,
                        UpdateProfileRequestWorker.KEY_AVATAR_URL to dashPayProfile.avatarUrl,
                        UpdateProfileRequestWorker.KEY_CREATED_AT to dashPayProfile.createdAt))
                .addTag("dashPayProfile:${dashPayProfile.userId}")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(dashPayProfile.userId),
                        ExistingWorkPolicy.KEEP,
                        updateProfileWorker)
    }

}