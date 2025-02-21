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
import androidx.work.*
import de.schildbach.wallet.security.SecurityGuard
import org.slf4j.LoggerFactory

class RestoreIdentityOperation(val application: Application) {
    class RestoreIdentityOperationException(message: String) : Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(RestoreIdentityOperation::class.java)

        private const val WORK_NAME = "RestoreIdentityWorker.WORK#"

        fun uniqueWorkName(identityId: String) = WORK_NAME + identityId
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all SendContactRequestWorker WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(RestoreIdentityOperation::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(identity: String, retry: Boolean = false): WorkContinuation {
        val password = SecurityGuard().retrievePassword()
        val verifyIdentityWorker = OneTimeWorkRequestBuilder<RestoreIdentityWorker>()
                .setInputData(
                    workDataOf(
                        RestoreIdentityWorker.KEY_PASSWORD to password,
                        RestoreIdentityWorker.KEY_IDENTITY to identity
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