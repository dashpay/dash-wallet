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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Enqueues the ONE [ResumeTopUpsWorker] drain instance. A single fixed
 * unique-work name + [ExistingWorkPolicy.KEEP] — there is nothing to
 * parameterize (the SDK's tracked-lock table is the queue), so concurrent
 * triggers (an ambiguous top-up failure racing the periodic
 * `checkTopUps` sweep) collapse into whichever run is already pending.
 * Network-constrained (resume talks to Core peers and Platform) with
 * exponential backoff for the retry path.
 */
class ResumeTopUpsOperation(private val application: Application) {
    companion object {
        private val log = LoggerFactory.getLogger(ResumeTopUpsOperation::class.java)
        const val WORK_NAME = "ResumeTopUpsWorker"
        private const val BACKOFF_DELAY_SECONDS = 30L
    }

    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<ResumeTopUpsWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(application)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        log.info("enqueued the top-up drain worker (KEEP — an existing run wins)")
    }
}
