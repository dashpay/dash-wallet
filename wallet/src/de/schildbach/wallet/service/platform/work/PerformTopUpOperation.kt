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
import androidx.lifecycle.LiveData
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Enqueues and observes the ONE in-flight Buy Credits purchase
 * ([PerformTopUpWorker]). A single unique-work name with
 * [ExistingWorkPolicy.KEEP]: a double tap (or re-entering the screen while
 * a purchase runs) attaches to the existing run instead of buying twice.
 * The screen drives its progress/success/failure UI from [status].
 */
class PerformTopUpOperation(private val application: Application) {
    companion object {
        const val WORK_NAME = "PerformTopUpWorker"

        /** Live status of the unique purchase work (empty until first use). */
        fun status(application: Application): LiveData<List<WorkInfo>> =
            WorkManager.getInstance(application)
                .getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }

    fun enqueue(amountDuffs: Long, isMaxSpend: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PerformTopUpWorker>()
            .setInputData(
                workDataOf(
                    PerformTopUpWorker.KEY_AMOUNT_DUFFS to amountDuffs,
                    PerformTopUpWorker.KEY_IS_MAX_SPEND to isMaxSpend
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(application)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
