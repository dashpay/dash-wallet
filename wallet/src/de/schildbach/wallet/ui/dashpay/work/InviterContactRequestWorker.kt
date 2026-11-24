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
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.work.BaseWorker
import kotlinx.coroutines.CancellationException
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory

@HiltWorker
class InviterContactRequestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val identityRepository: IdentityRepository,
    private val configuration: Configuration
) : BaseWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(InviterContactRequestWorker::class.java)
        const val KEY_INVITER_USERNAME = "InviterContactRequestWorker.INVITER_USERNAME"
        const val KEY_IDENTITY_ID = "InviterContactRequestWorker.IDENTITY_ID"
        const val MAX_ATTEMPTS = 20
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val inviterUsername = inputData.getString(KEY_INVITER_USERNAME)
            ?: return errorResult("missing KEY_INVITER_USERNAME parameter")
        val identityId = inputData.getString(KEY_IDENTITY_ID)
            ?: return errorResult("missing KEY_IDENTITY_ID parameter")
        val identity = identityRepository.blockchainIdentity
            ?: return retryOrFail("the identity is not loaded yet")
        if (identity.uniqueIdString != identityId) {
            return errorResult("the invite was claimed by another identity")
        }
        val inviterUserId = try {
            identityRepository.getUser(inviterUsername).firstOrNull()?.dashPayProfile?.userId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("inviter {} lookup failed", inviterUsername, e)
            return retryOrFail("inviter lookup failed: ${e.message}")
        } ?: return errorResult("inviter $inviterUsername was not found")

        if (configuration.inviter != inviterUserId) {
            SendContactRequestOperation(applicationContext).create(inviterUserId).enqueue().await()
            configuration.inviter = inviterUserId
            configuration.inviterContactRequestSentInfoShown = false
        }
        return Result.success()
    }

    private fun retryOrFail(reason: String): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else errorResult(reason)
}
