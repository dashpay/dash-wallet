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
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.service.work.BaseWorker
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsService

@HiltWorker
class BroadcastIdentityVerifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformBroadcastService: PlatformBroadcastService,
    val walletDataProvider: WalletData,
    val identityRepository: IdentityRepository
) : BaseWorker(context, parameters) {

    enum class IdentityWait { PUBLISH, RETRY, GIVE_UP }

    companion object {
        const val KEY_PASSWORD = "BroadcastIdentityVerifyWorker.PASSWORD"
        const val KEY_USERNAME = "BroadcastIdentityVerifyWorker.USERNAME"
        const val KEY_URL = "BroadcastIdentityVerifyWorker.URL"

        /**
         * Set on a failure after the document was submitted: its outcome is unknown (the SDK's
         * Ambiguous, a timeout), so it must not be published again without reconciliation.
         */
        const val KEY_PUBLICATION_UNCERTAIN = "BroadcastIdentityVerifyWorker.PUBLICATION_UNCERTAIN"

        /**
         * Runs (with WorkManager's backoff) this worker waits for the identity to be loaded
         * before it gives up: an SDK registration enqueues it next to recovery, which loads the
         * identity only when it finishes.
         */
        const val MAX_IDENTITY_WAIT_ATTEMPTS = 12

        /** Whether to publish now, wait for recovery to load the identity, or stop waiting. */
        fun identityWait(identityLoaded: Boolean, runAttemptCount: Int): IdentityWait = when {
            identityLoaded -> IdentityWait.PUBLISH
            runAttemptCount < MAX_IDENTITY_WAIT_ATTEMPTS -> IdentityWait.RETRY
            else -> IdentityWait.GIVE_UP
        }
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val username = inputData.getString(KEY_USERNAME)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_USERNAME parameter"))
        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_URL parameter"))

        val encryptionKey: KeyParameter
        try {
            encryptionKey = walletDataProvider.wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Identity Verify: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        // Not loaded yet (recovery still running): retry later instead of failing for good,
        // which would leave the name's contest without its verification link.
        when (identityWait(identityRepository.blockchainIdentity?.identity != null, runAttemptCount)) {
            IdentityWait.PUBLISH -> Unit
            IdentityWait.RETRY -> return Result.retry()
            IdentityWait.GIVE_UP -> return Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "the identity was never loaded; verification link not published")
            )
        }

        return try {
            val identityVerifyDocument = platformBroadcastService.broadcastIdentityVerify(username, url, encryptionKey)
            Result.success(workDataOf(
                    KEY_USERNAME to identityVerifyDocument.normalizedLabel,
                    KEY_URL to identityVerifyDocument.url
            ))
        } catch (ex: Exception) {
            analytics.logError(ex, "Identity Verify: failed to broadcast identity verify document")
            // Fail safe: past this point the document may have been submitted (the SDK throws
            // for an Ambiguous outcome), so this failure never authorizes another create.
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("broadcast identity verify", ex),
                    KEY_PUBLICATION_UNCERTAIN to true))
        }
    }
}