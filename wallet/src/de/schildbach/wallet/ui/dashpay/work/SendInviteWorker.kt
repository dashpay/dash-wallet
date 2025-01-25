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
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.service.platform.TopUpRepository
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import org.bitcoinj.core.Coin
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

@HiltWorker
class SendInviteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val analytics: AnalyticsService,
    private val platformRepo: PlatformRepo,
    private val invitationsDao: InvitationsDao,
    private val topUpRepository: TopUpRepository,
    private val walletDataProvider: WalletDataProvider
): BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "SendInviteWorker.PASSWORD"
        const val KEY_FUNDING_ADDRESS = "SendInviteWorker.FUNDING_ADDRESS"
        const val KEY_TX_ID = "SendInviteWorker.KEY_TX_ID"
        const val KEY_USER_ID = "SendInviteWorker.KEY_USER_ID"
        const val KEY_DYNAMIC_LINK = "SendInviteWorker.KEY_DYNAMIC_LINK"
        const val KEY_SHORT_DYNAMIC_LINK = "SendInviteWorker.KEY_SHORT_DYNAMIC_LINK"
        const val KEY_VALUE = "SendInviteWorker.KEY_VALUE"
        private val log = LoggerFactory.getLogger(SendInviteWorker::class.java)
    }

    class OutputDataWrapper(private val data: Data) {
        val fundingAddress
            get() = data.getString(KEY_FUNDING_ADDRESS)
        val txId
            get() = data.getByteArray(KEY_TX_ID)!!
        val userId
            get() = data.getString(KEY_USER_ID)!!
        val dynamicLink
            get() = data.getString(KEY_DYNAMIC_LINK)!!
        val shortDynamicLink
            get() = data.getString(KEY_SHORT_DYNAMIC_LINK)!!
        val value: Coin
            get() = Coin.valueOf(data.getLong(KEY_VALUE, 0L))
    }

    private val wallet = walletDataProvider.wallet!!
    private val authGroupExtension = wallet.getKeyChainExtension(AuthenticationGroupExtension.EXTENSION_ID) as AuthenticationGroupExtension

    // TODO: align with the topup-worker for retry and skip completed steps
    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val value = inputData.getLong(KEY_VALUE, 0L)
        if (value == 0L) {
            error("missing KEY_VALUE parameter")
        }
        val fundingAddress = inputData.getString(KEY_FUNDING_ADDRESS) ?:
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_FUNDING_ADDRESS parameter"))

        val encryptionKey: KeyParameter
        try {
            encryptionKey = wallet.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Send Invite: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            org.bitcoinj.core.Context.propagate(wallet.context)
            val blockchainIdentity = platformRepo.blockchainIdentity
            var invitation = invitationsDao.loadByFundingAddress(fundingAddress)
            val assetLockTx = if (invitation == null || !invitation.hasTransaction()) {
                topUpRepository.createInviteFundingTransactionAsync(
                    blockchainIdentity,
                    encryptionKey,
                    Coin.valueOf(value)
                )
            } else {
                authGroupExtension.invitationFundingTransactions.find { it.txId == invitation!!.txid }
                    ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "invite funding tx ${invitation.txid} not found"))
            }

            // make sure TX has been sent
            val confidence = assetLockTx.getConfidence(walletDataProvider.wallet!!.context)
            val wasTxSent = confidence.isChainLocked ||
                confidence.isTransactionLocked ||
                confidence.numBroadcastPeers() > 0
            if (!wasTxSent) {
                topUpRepository.sendTransaction(assetLockTx)
            }

            // create the dynamic link
            invitation = invitationsDao.loadByFundingAddress(fundingAddress)!!
            if (invitation.dynamicLink == null) {
                val dashPayProfile = platformRepo.getLocalUserProfile()
                val dynamicLink = topUpRepository.createDynamicLink(dashPayProfile!!, assetLockTx, encryptionKey)
                val shortDynamicLink = topUpRepository.buildShortDynamicLink(dynamicLink)
                invitation = invitation.copy(
                    shortDynamicLink = shortDynamicLink.shortLink.toString(),
                    dynamicLink = dynamicLink.uri.toString()
                )
                topUpRepository.updateInvitation(invitation)
            }
            Result.success(
                workDataOf(
                    KEY_TX_ID to assetLockTx.txId.bytes,
                    KEY_VALUE to value,
                    KEY_USER_ID to assetLockTx.identityId.toStringBase58(),
                    KEY_DYNAMIC_LINK to invitation.dynamicLink,
                    KEY_SHORT_DYNAMIC_LINK to invitation.shortDynamicLink
                )
            )
        } catch (ex: Exception) {
            analytics.logError(ex, "Send Invite: failed to send contact request")
            Result.failure(
                workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send invite", ex)
                )
            )
        }
    }
}
