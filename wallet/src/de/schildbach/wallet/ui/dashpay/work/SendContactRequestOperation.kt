/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.service.work.BaseWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SendContactRequestOperation(val context: Context) {

    class SendContactRequestOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(SendContactRequestOperation::class.java)

        const val WORK_NAME = "SendContactRequest.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId

        fun hasActiveOperation(context: Context, userId: String): Boolean {
            val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueWorkName(userId)).get()
            return workInfos.any { !it.state.isFinished }
        }

        fun operationStatus(
            context: Context,
            toUserId: String,
            analytics: AnalyticsService
        ): Flow<Resource<Pair<String, String>>> {
            val workManager: WorkManager = WorkManager.getInstance(context)
            return workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(toUserId)).flatMapLatest { workInfos ->
                flow {
                    if (workInfos.isNullOrEmpty()) {
                        return@flow
                    }

                    if (workInfos.size > 1) {
                        val e = RuntimeException("there should never be more than one unique work ${uniqueWorkName(toUserId)}")
                        analytics.logError(e)
                        throw e
                    }

                    val workInfo = workInfos[0]
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
                                analytics.logError(exception)
                                Resource.error(errorMessage, null)
                            } else {
                                val exception = SendContactRequestOperationException("Unknown error")
                                analytics.logError(exception)
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

        fun allOperationsStatus(application: Application): Flow<MutableMap<String, Resource<WorkInfo>>> {
            val workManager: WorkManager = WorkManager.getInstance(application)
            return workManager.getWorkInfosByTagFlow(SendContactRequestWorker::class.qualifiedName!!).flatMapLatest { workInfos ->
                flow {
                    if (workInfos.isNullOrEmpty()) {
                        return@flow
                    }

                    val result = mutableMapOf<String, Resource<WorkInfo>>()
                    workInfos.forEach {
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

    @SuppressLint("EnqueueWork")
    fun create(toUserId: String): WorkContinuation {

        val password = SecurityGuard.getInstance().retrievePassword()
        val sendContactRequestWorker = OneTimeWorkRequestBuilder<SendContactRequestWorker>()
                .setInputData(workDataOf(
                        SendContactRequestWorker.KEY_PASSWORD to password,
                        SendContactRequestWorker.KEY_TO_USER_ID to toUserId))
                .addTag("toUserId:$toUserId")
                .build()

        return WorkManager.getInstance(context)
                .beginUniqueWork(uniqueWorkName(toUserId),
                        ExistingWorkPolicy.KEEP,
                        sendContactRequestWorker)
    }
} 