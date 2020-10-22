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

        fun operationStatus(application: Application): LiveData<Resource<Nothing>> {
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
                            emit(Resource.success(null))
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMessage = BaseWorker.extractError(workInfo.outputData)
                            emit(if (errorMessage != null) {
                                Resource.error(errorMessage, null)
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
        val updateProfileWorker = OneTimeWorkRequestBuilder<UpdateProfileWorker>()
                .setInputData(workDataOf(
                        UpdateProfileWorker.KEY_PASSWORD to password,
                        UpdateProfileWorker.KEY_DISPLAY_NAME to dashPayProfile.displayName,
                        UpdateProfileWorker.KEY_PUBLIC_MESSAGE to dashPayProfile.publicMessage,
                        UpdateProfileWorker.KEY_AVATAR_URL to dashPayProfile.avatarUrl,
                        UpdateProfileWorker.KEY_CREATED_AT to dashPayProfile.createdAt))
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        updateProfileWorker)
    }

}