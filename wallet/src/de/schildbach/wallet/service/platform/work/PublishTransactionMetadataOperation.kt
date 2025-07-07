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
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.work.BroadcastUsernameVotesWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

class PublishTransactionMetadataOperation(val application: Application) {
    class PublishTransactionMetadataException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(PublishTransactionMetadataOperation::class.java)

        private const val WORK_NAME = "PublishTransactionMetadataWorker.WORK#"
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
                            uniqueWorkName(workId)
                        }")
                        analytics.logError(e)
                        throw e
                    }
                    emit(convertState(it.first()))
                }
            }
        }

        fun operationStatusFlow(
            application: Application,
            workId: String,
            analytics: AnalyticsService
        ): Flow<Resource<WorkInfo>> {
            val workManager = WorkManager.getInstance(application)
            return workManager
                .getWorkInfosForUniqueWorkFlow(uniqueWorkName(workId))
                .map { list ->
                    if (list.isNullOrEmpty()) {
                        return@map null
                    }
                    if (list.size > 1) {
                        val e = RuntimeException("there should never be more than one unique work ${uniqueWorkName(workId)}")
                        analytics.logError(e)
                        throw e
                    }
                    convertState(list.first())
                }
                .filterNotNull()
        }

        fun allOperationsStatus(application: Application): LiveData<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosByTagLiveData(BroadcastUsernameVotesWorker::class.qualifiedName!!).switchMap {
                return@switchMap liveData {
                    if (it.isNullOrEmpty()) {
                        return@liveData
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    it.forEach { workInfo ->
                        var toUserId = ""
                        workInfo.tags.forEach { tag ->
                            if (tag.startsWith("workId:")) {
                                toUserId = tag.replace("workId:", "")
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

    @SuppressLint("EnqueueWork")
    fun create(workId: String): WorkContinuation {
        val worker = OneTimeWorkRequestBuilder<PublishTransactionMetadataWorker>()
            .setInputData(
                workDataOf()
            )
            .addTag("workId:$workId")
            .build()

        return WorkManager.getInstance(application)
            .beginUniqueWork(
                uniqueWorkName(workId),
                ExistingWorkPolicy.KEEP,
                worker
            )
    }
}