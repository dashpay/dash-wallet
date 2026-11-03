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
import androidx.work.*
import de.schildbach.wallet.security.SecurityGuard
import org.slf4j.LoggerFactory

class BroadcastIdentityVerifyOperation(val application: Application) {

    class BroadcastIdentityVerifyOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(BroadcastIdentityVerifyOperation::class.java)

        private const val WORK_NAME = "BroadcastIdentityVerify.WORK#"

        fun uniqueWorkName(toUserId: String) = WORK_NAME + toUserId

        /**
         * The name's verification work already published its document, or is still queued or
         * running: publishing again would broadcast a second document (KEEP only covers work
         * that has not finished).
         */
        fun publishedOrPending(states: List<WorkInfo.State>): Boolean =
            states.any { it == WorkInfo.State.SUCCEEDED || !it.isFinished }
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(BroadcastIdentityVerifyWorker::class.qualifiedName!!)

    /** The states of [username]'s verification work WorkManager still remembers. */
    suspend fun states(username: String): List<WorkInfo.State> =
        workManager.getWorkInfosForUniqueWork(uniqueWorkName(username)).await().map { it.state }

    @SuppressLint("EnqueueWork")
    fun create(username: String, url: String): WorkContinuation {

        val password = SecurityGuard.getInstance().retrievePassword()
        val verifyIdentityWorker = OneTimeWorkRequestBuilder<BroadcastIdentityVerifyWorker>()
                .setInputData(workDataOf(
                        BroadcastIdentityVerifyWorker.KEY_PASSWORD to password,
                        BroadcastIdentityVerifyWorker.KEY_USERNAME to username,
                        BroadcastIdentityVerifyWorker.KEY_URL to url
                    )
                )
                .addTag("username:$username")
                .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(username),
                    ExistingWorkPolicy.KEEP,
                    verifyIdentityWorker)
    }
}