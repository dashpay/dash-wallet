/*
 * Copyright 2026 Dash Core Group.
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

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.service.platform.sdk.InviteOverageOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedInviteOverageTopUp
import de.schildbach.wallet.service.work.BaseWorker
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Background completion of a shielded invite claim's OVERAGE top-up (see
 * [ShieldedInviteOverageTopUp]): each run performs one resume-aware pass and
 * retries with backoff until the pending record resolves. The claim and the
 * username registration never wait on this — it is enqueued AFTER the claim
 * broadcast succeeded ([de.schildbach.wallet.ui.dashpay.CreateIdentityService])
 * and re-enqueued at app launch while a record exists
 * ([de.schildbach.wallet.ui.main.MainActivity]), so an app death anywhere in
 * the pipeline resumes on the persisted record.
 */
@HiltWorker
class ShieldedInviteOverageWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val overageTopUp: ShieldedInviteOverageTopUp
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(ShieldedInviteOverageWorker::class.java)

        private const val UNIQUE_WORK_NAME = "ShieldedInviteOverageWorker.WORK"

        /**
         * Enqueue (or keep) the single overage-completion chain. KEEP: at most
         * one pending record exists at a time and an already-enqueued chain is
         * already draining it. The ~30s Halo 2 proof plus DAPI round-trips run
         * inside, so the work requires a network and backs off exponentially
         * (30s, 60s, 120s… capped by WorkManager) between passes.
         */
        fun enqueue(application: Application) {
            val work = OneTimeWorkRequestBuilder<ShieldedInviteOverageWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ShieldedInviteOverageWorker::class.qualifiedName!!)
                .build()
            WorkManager.getInstance(application)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, work)
        }
    }

    override suspend fun doWorkWithBaseProgress(): Result = try {
        when (overageTopUp.runPending()) {
            InviteOverageOutcome.IDLE,
            InviteOverageOutcome.DONE -> Result.success()
            InviteOverageOutcome.RETRY -> Result.retry()
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        // The pass itself maps expected failures to RETRY; anything escaping
        // is unexpected but never terminal for the record — retry with
        // backoff rather than dropping the user's funds on an exception.
        log.error("invite overage pass failed unexpectedly — retrying", t)
        Result.retry()
    }
}
