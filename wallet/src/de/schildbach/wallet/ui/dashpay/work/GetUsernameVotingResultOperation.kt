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
import de.schildbach.wallet.Constants
import org.bitcoinj.core.NetworkParameters
import org.slf4j.LoggerFactory
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class GetUsernameVotingResultOperation(val application: Application) {

    class GetUsernameVotingResultOperationnException(message: String) : java.lang.Exception(message)

    companion object {
        private val log = LoggerFactory.getLogger(GetUsernameVotingResultOperation::class.java)

        private const val WORK_NAME = "GetUsernameVotingResult.WORK#"

        fun uniqueWorkName(username: String, identityId: String) = "$WORK_NAME$username:$identityId"
    }

    private val workManager: WorkManager = WorkManager.getInstance(application)

    /**
     * Gets the list of all GetUsernameVotingResultOperation WorkInfo's
     */
    val allOperationsData = workManager.getWorkInfosByTagLiveData(GetUsernameVotingResultsWorker::class.qualifiedName!!)

    @SuppressLint("EnqueueWork")
    fun create(username: String, identityId: String, votingStartedAt: Long): WorkContinuation {
        log.info("scheduling work to check username voting status")
        val delay = System.currentTimeMillis() - if (Constants.NETWORK_PARAMETERS.id != NetworkParameters.ID_MAINNET) {
            TimeUnit.MINUTES.toMillis(90)
        } else {
            TimeUnit.DAYS.toMillis(14)
        } + votingStartedAt + TimeUnit.MINUTES.toMillis(2)
        log.info("scheduling work to check username voting status on {}",
            DateFormat.getDateInstance(DateFormat.FULL).format(
                Date(System.currentTimeMillis() + delay)
            )
        )
        val worker = OneTimeWorkRequestBuilder<GetUsernameVotingResultsWorker>()
                .setInputData(workDataOf(
                        BroadcastIdentityVerifyWorker.KEY_USERNAME to username,
                        BroadcastIdentityVerifyWorker.KEY_URL to identityId,
                    )
                )
                .addTag("identity:$username")
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        return WorkManager.getInstance(application)
                .beginUniqueWork(uniqueWorkName(username, identityId),
                    ExistingWorkPolicy.KEEP,
                    worker)
    }
}