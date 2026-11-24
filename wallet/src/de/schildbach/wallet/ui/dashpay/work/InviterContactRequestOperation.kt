/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf

class InviterContactRequestOperation(private val context: Context) {
    companion object {
        private const val WORK_NAME = "InviterContactRequest.WORK"
    }

    suspend fun enqueue(inviterUsername: String, identityId: String) {
        val request = OneTimeWorkRequestBuilder<InviterContactRequestWorker>()
            .setInputData(
                workDataOf(
                    InviterContactRequestWorker.KEY_INVITER_USERNAME to inviterUsername,
                    InviterContactRequestWorker.KEY_IDENTITY_ID to identityId
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            .await()
    }
}
