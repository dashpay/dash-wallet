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
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.SdkTransparentTopUp
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.work.BaseWorker
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Runs ONE user-initiated Buy Credits top-up through the SDK
 * ([SdkTransparentTopUp]), detached from the screen's lifecycle — a lock
 * screen, rotation, or process death cannot cancel the purchase mid-flight
 * (the old dashj flow had this via TopupIdentityWorker; this is its
 * SDK-only successor). Input is the AMOUNT ONLY: no wallet password and no
 * transaction id are stored in WorkManager's database — the SDK signs via
 * its own key resolver.
 *
 * Funds safety on reruns: if the process dies mid-call, WorkManager reruns
 * this worker; [SdkTransparentTopUp]'s resume gate then matches the
 * already-broadcast lock (by the identity's registration index) and
 * completes IT instead of building a second one — no double pay. This
 * worker itself never returns retry: a NotBroadcast outcome is the user's
 * to retry, and an Ambiguous outcome must never be blindly re-run — it is
 * handed to [ResumeTopUpsWorker], which resumes only the tracked lock.
 */
@HiltWorker
class PerformTopUpWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val sdkTransparentTopUp: SdkTransparentTopUp,
    private val blockchainIdentityConfig: BlockchainIdentityConfig
) : BaseWorker(context, parameters) {
    companion object {
        private val log = LoggerFactory.getLogger(PerformTopUpWorker::class.java)
        const val KEY_AMOUNT_DUFFS = "PerformTopUpWorker.AMOUNT_DUFFS"
        const val KEY_NEW_BALANCE = "PerformTopUpWorker.NEW_BALANCE"
        const val KEY_AMBIGUOUS = "PerformTopUpWorker.AMBIGUOUS"

        /** Progress marker: the SDK call that does the actual work has begun. */
        const val KEY_SDK_CALL_STARTED = "PerformTopUpWorker.SDK_CALL_STARTED"
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val amountDuffs = inputData.getLong(KEY_AMOUNT_DUFFS, -1L)
        if (amountDuffs <= 0L) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing or invalid amount"))
        }
        val identityId = blockchainIdentityConfig.get(BlockchainIdentityConfig.IDENTITY_ID)
        if (identityId.isNullOrEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "no identity to top up"))
        }

        val result = try {
            // Tell the UI the hand-off is complete: the purchase is now the
            // SDK's (and this worker's) responsibility, not the screen's.
            setProgress(workDataOf(KEY_SDK_CALL_STARTED to true))
            sdkTransparentTopUp.topUp(identityId, amountDuffs)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("top-up threw unexpectedly", t)
            SdkWriteResult.Ambiguous(t)
        }
        return when (result) {
            is SdkWriteResult.Broadcast -> {
                log.info("top-up of {} duffs credited; new balance {}", amountDuffs, result.value)
                Result.success(workDataOf(KEY_NEW_BALANCE to result.value))
            }
            is SdkWriteResult.NotBroadcast -> {
                log.warn("top-up not sent: {}", result.reason)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to result.reason))
            }
            is SdkWriteResult.Ambiguous -> {
                // The lock, if broadcast, is Rust-tracked — the recovery
                // worker completes it; never re-run the purchase itself.
                ResumeTopUpsOperation(applicationContext as Application).enqueue()
                log.error("top-up outcome unconfirmed; recovery worker enqueued", result.cause)
                Result.failure(
                    workDataOf(
                        KEY_ERROR_MESSAGE to (result.cause.message ?: "top-up outcome unconfirmed"),
                        KEY_AMBIGUOUS to true
                    )
                )
            }
        }
    }
}
