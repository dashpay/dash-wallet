/*
 * Copyright 2021 Dash Core Group
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
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.crashlytics.FirebaseCrashlytics
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Address
import org.bitcoinj.crypto.KeyCrypterException
import org.bouncycastle.crypto.params.KeyParameter
import org.dashevo.dpp.toHexString

class SendInviteWorker(context: Context, parameters: WorkerParameters)
    : BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "SendInviteWorker.PASSWORD"
        const val KEY_TX_ID = "SendInviteWorker.KEY_TX_ID"
        const val KEY_USER_ID = "SendInviteWorker.KEY_USER_ID"
        const val KEY_INVITE_ID = "SendInviteWorker.KEY_INVITE_ID"

        fun extractTxId(data: Data): ByteArray? {
            return data.getByteArray(KEY_TX_ID)
        }

        fun extractUserId(data: Data): String? {
            return data.getString(KEY_USER_ID)
        }

        fun extractInviteId(data: Data): String? {
            return data.getString(KEY_INVITE_ID)
        }
    }

    private val platformRepo = PlatformRepo.getInstance()

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))

        val encryptionKey: KeyParameter
        val wallet = WalletApplication.getInstance().wallet!!
        try {
            encryptionKey = wallet.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            FirebaseCrashlytics.getInstance().log("Send Invite: failed to derive encryption key")
            FirebaseCrashlytics.getInstance().recordException(ex)
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            val blockchainIdentity = PlatformRepo.getInstance().getBlockchainIdentity()!!
            val cftx = platformRepo.createInviteFundingTransactionAsync(blockchainIdentity, encryptionKey)
            Result.success(workDataOf(
                    KEY_TX_ID to cftx.txId.bytes,
                    KEY_USER_ID to cftx.creditBurnIdentityIdentifier.toStringBase58(),
                    KEY_INVITE_ID to Address.fromPubKeyHash(wallet.params, cftx.creditBurnPublicKeyId.bytes).toBase58()
            ))
        } catch (ex: Exception) {
            FirebaseCrashlytics.getInstance().log("Send Invite: failed to send contact request")
            FirebaseCrashlytics.getInstance().recordException(ex)
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send invite", ex)))
        }
    }
}