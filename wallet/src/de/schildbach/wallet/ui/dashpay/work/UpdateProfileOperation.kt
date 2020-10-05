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
        const val WORK_NAME = "UpdateProfile.WORK"

        fun operationStatus(application: Application): LiveData<Resource<String>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME).switchMap {
                return@switchMap liveData {

                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    if (it.size > 1) {
                        throw RuntimeException("there should never be more than one unique work $WORK_NAME")
                    }

                    val workInfo = it[0]
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val userId = UpdateProfileRequestWorker.extractUserId(workInfo.outputData)!!
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
    }

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
                .addTag("dashPayProfileUpdate:${dashPayProfile.userId}")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        updateProfileWorker)
    }

}