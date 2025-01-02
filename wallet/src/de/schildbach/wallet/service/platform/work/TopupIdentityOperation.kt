/*
 * Copyright 2024 Dash Core Group
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

package de.schildbach.wallet.service.platform.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.work.BroadcastUsernameVotesOperation
import de.schildbach.wallet.ui.dashpay.work.BroadcastUsernameVotesWorker
import org.bitcoinj.core.Coin
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

class TopupIdentityOperation(val application: Application) {
    class TopupIdentityOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(TopupIdentityOperation::class.java)

        private const val WORK_NAME = "TopupIdentityWorker.WORK#"
        fun uniqueWorkName(workId: String) = "${WORK_NAME}$workId}"

        fun operationStatus(
            application: Application,
            workId: String,
            analytics: AnalyticsService
        ): LiveData<Resource<WorkInfo>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosForUniqueWorkLiveData(uniqueWorkName(workId)).switchMap {
                return@switchMap liveData {
                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    if (it.size > 1) {
                        val e = RuntimeException("there should never be more than one unique work ${
                            uniqueWorkName(
                                workId
                            )
                        }")
                        analytics.logError(e)
                        throw e
                    }
                    emit(convertState(it.first()))
                }
            }
        }

        fun allOperationsStatus(application: Application): LiveData<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosByTagLiveData(BroadcastUsernameVotesWorker::class.qualifiedName!!).switchMap {
                return@switchMap liveData {
                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    it.filter { workInfo ->
                        val timestampTag = workInfo.tags.firstOrNull { it.startsWith("timestamp:") }
                        timestampTag?.let {
                            val timestamp = it.removePrefix("timestamp:").toLongOrNull()
                            timestamp != null && timestamp > BroadcastUsernameVotesOperation.lastTimestamp
                        } ?: false
                    }.forEach { workInfo ->
                        var toUserId = ""
                        workInfo.tags.forEach { tag ->
                            if (tag.startsWith("usernames:")) {
                                toUserId = tag.replace("usernames:", "")
                            }
                        }
                        result[toUserId] = convertState(workInfo)
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

//    private val workManager: WorkManager = WorkManager.getInstance(application)
//
//    /**
//     * Gets the list of all SendContactRequestWorker WorkInfo's
//     */
//    val allOperationsData = workManager.getWorkInfosByTagLiveData(TopupIdentityOperation::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(identity: String, amount: Coin): WorkContinuation {
        val password = SecurityGuard().retrievePassword()
        val verifyIdentityWorker = OneTimeWorkRequestBuilder<TopupIdentityWorker>()
                .setInputData(
                    workDataOf(
                        TopupIdentityWorker.KEY_PASSWORD to password,
                        TopupIdentityWorker.KEY_IDENTITY to identity,
                        TopupIdentityWorker.KEY_VALUE to amount.value
                    )
                )
                .addTag("identity:$identity")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(identity),
                    ExistingWorkPolicy.KEEP,
                    verifyIdentityWorker)
    }
}