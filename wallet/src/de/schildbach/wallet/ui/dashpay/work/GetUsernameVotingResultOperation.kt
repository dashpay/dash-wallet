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
import com.google.android.material.timepicker.TimeFormat
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.UsernameRequest
import org.bitcoinj.core.NetworkParameters
import org.slf4j.LoggerFactory
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class GetUsernameVotingResultOperation(val application: Application) {

    class GetUsernameVotingResultOperationException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(GetUsernameVotingResultOperation::class.java)

        private const val WORK_NAME = "GetUsernameVotingResult.WORK#"
        private val DELAY_AFTER_VOTING_PERIOD = TimeUnit.MINUTES.toMillis(1)

        fun uniqueWorkName(username: String, identityId: String) = "$WORK_NAME$username:$identityId"

        fun calculateDelay(votingStartedAt: Long, currentTime: Long) =
            votingStartedAt + UsernameRequest.VOTING_PERIOD_MILLIS + DELAY_AFTER_VOTING_PERIOD - currentTime
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all GetUsernameVotingResultOperation WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(GetUsernameVotingResultsWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(username: String, identityId: String, votingStartedAt: Long): WorkContinuation {
        val currentTime = System.currentTimeMillis()
        val delay = calculateDelay(votingStartedAt, currentTime)
        
        // Handle negative delays (voting already ended)
        val adjustedDelay = if (delay < 0) {
            log.info("Voting already ended, scheduling work to run immediately")
            0L
        } else {
            log.info("scheduling work to check username voting status on {}",
                DateFormat.getDateTimeInstance(DateFormat.FULL, TimeFormat.CLOCK_24H).format(
                    Date(currentTime + delay)
                )
            )
            delay
        }
        
        val worker = OneTimeWorkRequestBuilder<GetUsernameVotingResultsWorker>()
            .setInputData(workDataOf(
                    GetUsernameVotingResultsWorker.KEY_USERNAME to username,
                    GetUsernameVotingResultsWorker.KEY_IDENTITY_ID to identityId,
                )
            )
            .addTag("identity:$username")
            .setInitialDelay(adjustedDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        return WorkManager.getInstance(application)
            .beginUniqueWork(
                uniqueWorkName(username, identityId),
                ExistingWorkPolicy.KEEP,
                worker
            )
    }
}