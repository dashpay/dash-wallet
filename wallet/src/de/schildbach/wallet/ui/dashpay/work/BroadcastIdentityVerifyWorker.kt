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
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService

@HiltWorker
class BroadcastIdentityVerifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformBroadcastService: PlatformBroadcastService,
    val walletDataProvider: WalletDataProvider
) : BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "BroadcastIdentityVerifyWorker.PASSWORD"
        const val KEY_USERNAME = "BroadcastIdentityVerifyWorker.USERNAME"
        const val KEY_URL = "BroadcastIdentityVerifyWorker.URL"


//        fun extractUserId(date: Data): String? {
//            return date.getString(KEY_USER_ID)
//        }
//
//        fun extractToUserId(date: Data): String? {
//            return date.getString(KEY_TO_USER_ID)
//        }
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

        return try {
            val identityVerifyDocument = platformBroadcastService.broadcastIdentityVerify(username, url, encryptionKey)
            Result.success(workDataOf(
                    KEY_USERNAME to identityVerifyDocument.normalizedLabel,
                    KEY_URL to identityVerifyDocument.url
            ))
        } catch (ex: Exception) {
            analytics.logError(ex, "Identity Verify: failed to broadcast identity verify document")
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("broadcast identity verify", ex)))
        }
    }
}