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
import org.bitcoinj.evolution.AssetLockTransaction
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory

@HiltWorker
class SendInviteWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    val analytics: AnalyticsService,
    val platformRepo: PlatformRepo,
    val walletDataProvider: WalletDataProvider
): BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "SendInviteWorker.PASSWORD"
        const val KEY_TX_ID = "SendInviteWorker.KEY_TX_ID"
        const val KEY_USER_ID = "SendInviteWorker.KEY_USER_ID"
        const val KEY_DYNAMIC_LINK = "SendInviteWorker.KEY_DYNAMIC_LINK"
        const val KEY_SHORT_DYNAMIC_LINK = "SendInviteWorker.KEY_SHORT_DYNAMIC_LINK"
        const val KEY_VALUE = "SendInviteWorker.KEY_VALUE"
        private val log = LoggerFactory.getLogger(SendInviteWorker::class.java)
    }

    class OutputDataWrapper(private val data: Data) {
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

    override suspend fun doWorkWithBaseProgress(): Result {
        val password = inputData.getString(KEY_PASSWORD)
                ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "missing KEY_PASSWORD parameter"))
        val value = inputData.getLong(KEY_VALUE, 0L)
        if (value == 0L) {
            error("missing KEY_VALUE parameter")
        }

        val encryptionKey: KeyParameter
        org.bitcoinj.core.Context.propagate(wallet.context)
        try {
            encryptionKey = wallet.keyCrypter!!.deriveKey(password)
        } catch (ex: KeyCrypterException) {
            analytics.logError(ex, "Send Invite: failed to derive encryption key")
            val msg = formatExceptionMessage("derive encryption key", ex)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to msg))
        }

        return try {
            val blockchainIdentity = platformRepo.blockchainIdentity
            val assetLockTx = platformRepo.createInviteFundingTransactionAsync(
                blockchainIdentity,
                encryptionKey,
                Coin.valueOf(value)
            )
            val dashPayProfile = platformRepo.getLocalUserProfile()
            val dynamicLink = createDynamicLink(dashPayProfile!!, assetLockTx, encryptionKey)
            val shortDynamicLink = buildShortDynamicLink(dynamicLink)
            val invitation = platformRepo.getInvitation(assetLockTx.identityId.toStringBase58())!!
            invitation.shortDynamicLink = shortDynamicLink.shortLink.toString()
            invitation.dynamicLink = dynamicLink.uri.toString()
            platformRepo.updateInvitation(invitation)
            Result.success(workDataOf(
                    KEY_TX_ID to assetLockTx.txId.bytes,
                    KEY_VALUE to value,
                    KEY_USER_ID to assetLockTx.identityId.toStringBase58(),
                    KEY_DYNAMIC_LINK to dynamicLink.uri.toString(),
                    KEY_SHORT_DYNAMIC_LINK to shortDynamicLink.shortLink.toString()
            ))
        } catch (ex: Exception) {
            analytics.logError(ex, "Send Invite: failed to send contact request")
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send invite", ex)))
        }
    }

    private fun createDynamicLink(dashPayProfile: DashPayProfile, assetLockTx: AssetLockTransaction, aesKeyParameter: KeyParameter): DynamicLink {
        log.info("creating dynamic link for invitation")
        // dashj Context does not work with coroutines well, so we need to call Context.propogate
        // in each suspend method that uses the dashj Context
        org.bitcoinj.core.Context.propagate(wallet.context)
        val username = dashPayProfile.username
        val avatarUrlEncoded = URLEncoder.encode(dashPayProfile.avatarUrl, StandardCharsets.UTF_8.displayName())
        return FirebaseDynamicLinks.getInstance()
                .createDynamicLink().apply {
                    link = InvitationLinkData.create(username, dashPayProfile.displayName, avatarUrlEncoded, assetLockTx, aesKeyParameter).link
                    domainUriPrefix = Constants.Invitation.DOMAIN_URI_PREFIX
                    setAndroidParameters(DynamicLink.AndroidParameters.Builder().build())
                    setIosParameters(DynamicLink.IosParameters.Builder(
                            Constants.Invitation.IOS_APP_BUNDLEID
                    ).apply {
                        appStoreId = Constants.Invitation.IOS_APP_APPSTOREID
                    }.build())
                }
                .setSocialMetaTagParameters(DynamicLink.SocialMetaTagParameters.Builder().apply {
                    title = applicationContext.getString(R.string.invitation_preview_title)
                    val nameLabel = dashPayProfile.nameLabel
                    val nameLabelEncoded = URLEncoder.encode(nameLabel, StandardCharsets.UTF_8.displayName())
                    imageUrl = Uri.parse("https://invitations.dashpay.io/fun/invite-preview?display-name=$nameLabelEncoded&avatar-url=$avatarUrlEncoded")
                    description = applicationContext.getString(R.string.invitation_preview_message, nameLabel)
                }.build())
                .setGoogleAnalyticsParameters(DynamicLink.GoogleAnalyticsParameters.Builder(
                        applicationContext.getString(R.string.app_name_dashpay),
                        Constants.Invitation.UTM_MEDIUM,
                        Constants.Invitation.UTM_CAMPAIGN
                ).build())
                .buildDynamicLink()
    }

    private suspend fun buildShortDynamicLink(dynamicLink: DynamicLink): ShortDynamicLink {
        return suspendCoroutine { continuation ->
            FirebaseDynamicLinks.getInstance().createDynamicLink()
                    .setLongLink(dynamicLink.uri)
                    .buildShortDynamicLink()
                    .addOnSuccessListener {
                        log.debug("dynamic link successfully created")
                        continuation.resume(it)
                    }
                    .addOnFailureListener {
                        log.error(it.message, it)
                        continuation.resumeWithException(it)
                    }
        }
    }
}
