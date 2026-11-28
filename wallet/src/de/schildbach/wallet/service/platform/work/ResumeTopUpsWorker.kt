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

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.service.platform.sdk.SdkTopUpRecoveryService
import de.schildbach.wallet.service.work.BaseWorker
import kotlinx.coroutines.CancellationException
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

/**
 * Phase B of the SDK top-up migration (#1520 item 3 / MO-998): the
 * PAYLOAD-FREE drain worker that replaces the txid-based
 * [TopupIdentityWorker] retry for SDK-created top-ups.
 *
 * The SDK's tracked-lock table IS the retry queue: this worker carries no
 * input data at all — no txid, no identity, and (unlike
 * [TopupIdentityWorker.KEY_PASSWORD]) no wallet password serialized into
 * WorkManager's database; the SDK signs via the manager's live mnemonic
 * resolver. One run = one [SdkTopUpRecoveryService.drainPendingTopUps] pass,
 * idempotent by construction (consumed locks vanish from the recovery
 * surface), so [androidx.work.ExistingWorkPolicy.KEEP] + a crash mid-run
 * + a duplicate enqueue are all harmless.
 *
 * [Result.retry] (WorkManager's backoff) when the pass reports another
 * attempt could make progress; success otherwise — including when locks
 * remain but are terminal ([TopUpDrainReport.alreadyConsumed]), which
 * retrying cannot fix.
 */
@HiltWorker
class ResumeTopUpsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val sdkTopUpRecoveryService: SdkTopUpRecoveryService,
    private val analytics: AnalyticsService
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(ResumeTopUpsWorker::class.java)
        const val KEY_PENDING = "ResumeTopUpsWorker.PENDING"
        const val KEY_RESUMED = "ResumeTopUpsWorker.RESUMED"
        const val KEY_ALREADY_CONSUMED = "ResumeTopUpsWorker.ALREADY_CONSUMED"
        const val KEY_FAILED = "ResumeTopUpsWorker.FAILED"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val report = try {
            sdkTopUpRecoveryService.drainPendingTopUps()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // drainPendingTopUps contains its own failures; this is
            // belt-and-braces for anything unexpected.
            analytics.logError(t, "Resume top-ups: drain pass failed")
            return Result.retry()
        }
        log.info(
            "drain pass: {} pending, {} resumed, {} already consumed, {} failed{}",
            report.pending, report.resumed, report.alreadyConsumed, report.failed,
            if (report.surfaceUnavailable) " (surface unavailable)" else ""
        )
        return if (report.retryNeeded) {
            Result.retry()
        } else {
            Result.success(
                workDataOf(
                    KEY_PENDING to report.pending,
                    KEY_RESUMED to report.resumed,
                    KEY_ALREADY_CONSUMED to report.alreadyConsumed,
                    KEY_FAILED to report.failed
                )
            )
        }
    }
}
