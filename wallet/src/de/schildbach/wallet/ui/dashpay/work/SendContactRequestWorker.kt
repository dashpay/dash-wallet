/*
 * Copyright 2020 Dash Core Group
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
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.platform.PlatformBroadcastService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.analytics.AnalyticsService

@HiltWorker
class SendContactRequestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformBroadcastService: PlatformBroadcastService
) : BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "SendContactRequestWorker.PASSWORD"
        const val KEY_TO_USER_ID = "SendContactRequestWorker.KEY_TO_USER_ID"
        const val KEY_USER_ID = "SendContactRequestWorker.KEY_USER_ID"

        fun extractUserId(date: Data): String? {
            return date.getString(KEY_USER_ID)
        }

        fun extractToUserId(date: Data): String? {
            return date.getString(KEY_TO_USER_ID)
        }
    }

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val toUserId = inputData.getString(KEY_TO_USER_ID)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_TO_USER_ID parameter"))

        val encryptionKey: KeyParameter
        try {
            encryptionKey = WalletApplication.getInstance().wallet!!.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Contact Request: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            val sendContactRequestResult = platformBroadcastService.sendContactRequest(toUserId, encryptionKey)
            Result.success(workDataOf(
                    KEY_USER_ID to sendContactRequestResult.userId,
                    KEY_TO_USER_ID to sendContactRequestResult.toUserId
            ))
        } catch (ex: Exception) {
            analytics.logError(ex, "Contact Request: failed to send contact request")
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send contact request", ex)))
        }
    }
}