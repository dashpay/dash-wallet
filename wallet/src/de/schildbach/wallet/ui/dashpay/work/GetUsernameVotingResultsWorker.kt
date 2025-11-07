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

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.service.work.BaseWorker
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

/**
 * check and update identity data for username voting results
 */
@HiltWorker
class GetUsernameVotingResultsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformSyncService: PlatformSyncService
) : BaseWorker(context, parameters) {

    companion object {
        private val log = LoggerFactory.getLogger(GetUsernameVotingResultsWorker::class.java)
        const val KEY_USERNAME = "GetUsernameVotingResultsWorker.USERNAME"
        const val KEY_IDENTITY_ID = "GetUsernameVotingResultsWorker.IDENTITY_ID"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        log.info("GetUsernameVotingResultsWorker started execution")
        
        val username = inputData.getString(KEY_USERNAME)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_USERNAME parameter"))
        val identityId = inputData.getString(KEY_IDENTITY_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_IDENTITY_ID parameter"))

        return try {
            log.info("GetUsernameVotingResultsWorker calling platformSyncService.checkUsernameVotingStatus()")
            platformSyncService.checkUsernameVotingStatus()
            
            log.info("GetUsernameVotingResultsWorker calling platformSyncService.updateUsernameRequestsWithVotes()")
            platformSyncService.updateUsernameRequestsWithVotes()
            
            log.info("GetUsernameVotingResultsWorker completed successfully")
            Result.success(workDataOf(
                    KEY_USERNAME to username,
                    KEY_IDENTITY_ID to identityId
            ))
        } catch (ex: Exception) {
            log.error("GetUsernameVotingResultsWorker failed with exception", ex)
            analytics.logError(ex, "get username voting results: failed to broadcast identity verify document")
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("get username voting results error", ex)))
        }
    }
}