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

package de.schildbach.wallet.ui.dashpay.work

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.work.*
import de.schildbach.wallet.Constants
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.security.SecurityGuard
import org.bitcoinj.core.ECKey
import org.dashj.platform.dpp.voting.ResourceVoteChoice
import org.slf4j.LoggerFactory

class BroadcastUsernameVotesOperation(val application: Application) {
    class BroadcastUsernameVotesOperationException(message: String) : java.lang.Exception(message)

    companion object {
        var lastTimestamp: Long = System.currentTimeMillis()
        private val log = LoggerFactory.getLogger(BroadcastUsernameVotesOperation::class.java)

        private const val WORK_NAME = "BroadcastUsernameVotes.WORK#"

        fun uniqueWorkName(usernames: String) = "$WORK_NAME$usernames-${System.currentTimeMillis()}"

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
                            timestamp != null && timestamp > lastTimestamp
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

    private val workManager: WorkManager = WorkManager.getInstance(application)

//    /**
//     * Gets the list of all BroadcastUsernameVotesOperation WorkInfo's
//     */
//    val allOperationsLiveData = workManager.getWorkInfosByTagLiveData(BroadcastUsernameVotesWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(
        usernames: List<String>,
        voteChoices: List<ResourceVoteChoice>,
        masternodeKeys: List<ByteArray>
    ): WorkContinuation {
        val password = SecurityGuard().retrievePassword()
        val verifyIdentityWorker = OneTimeWorkRequestBuilder<BroadcastUsernameVotesWorker>()
            .setInputData(
                workDataOf(
                    BroadcastUsernameVotesWorker.KEY_PASSWORD to password,
                    BroadcastUsernameVotesWorker.KEY_USERNAMES to usernames.toTypedArray(),
                    BroadcastUsernameVotesWorker.KEY_VOTE_CHOICES to voteChoices.map { it.toString() }.toTypedArray(),
                    BroadcastUsernameVotesWorker.KEY_MASTERNODE_KEYS to masternodeKeys.map {
                        ECKey.fromPrivate(it).getPrivateKeyAsWiF(Constants.NETWORK_PARAMETERS)
                    }.toTypedArray()
                )
            )
            .addTag("usernames:$usernames")
            .addTag("timestamp:${System.currentTimeMillis()}") // Add timestamp as a tag
            .build()
        log.info("creating BroadcastUsernameVotesOperation({}, {})", usernames, voteChoices)
        return WorkManager.getInstance(application)
            .beginUniqueWork(
                uniqueWorkName(usernames.joinToString(",")),
                ExistingWorkPolicy.KEEP,
                verifyIdentityWorker
            )
    }
}