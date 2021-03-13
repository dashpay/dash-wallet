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
import android.net.Uri
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.dynamiclinks.DynamicLink
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import com.google.firebase.dynamiclinks.ShortDynamicLink
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Address
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.evolution.CreditFundingTransaction
import org.bouncycastle.crypto.params.KeyParameter
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val log = LoggerFactory.getLogger(SendInviteWorker::class.java)

class SendInviteWorker(context: Context, parameters: WorkerParameters)
    : BaseWorker(context, parameters) {

    companion object {
        const val KEY_PASSWORD = "SendInviteWorker.PASSWORD"
        const val KEY_TX_ID = "SendInviteWorker.KEY_TX_ID"
        const val KEY_USER_ID = "SendInviteWorker.KEY_USER_ID"
        const val KEY_INVITE_ID = "SendInviteWorker.KEY_INVITE_ID"
        const val KEY_DYNAMIC_LINK = "SendInviteWorker.KEY_DYNAMIC_LINK"
        const val KEY_SHORT_DYNAMIC_LINK = "SendInviteWorker.KEY_SHORT_DYNAMIC_LINK"
    }

    class OutputDataWrapper(private val data: Data) {
        val txId
            get() = data.getByteArray(KEY_TX_ID)!!
        val userId
            get() = data.getString(KEY_USER_ID)!!
        val inviteId
            get() = data.getString(KEY_INVITE_ID)!!
        val dynamicLink
            get() = data.getString(KEY_DYNAMIC_LINK)!!
        val shortDynamicLink
            get() = data.getString(KEY_SHORT_DYNAMIC_LINK)!!
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
            val blockchainIdentity = platformRepo.getBlockchainIdentity()!!
            val cftx = platformRepo.createInviteFundingTransactionAsync(blockchainIdentity, encryptionKey)
            val dashPayProfile = platformRepo.getLocalUserProfile()
            val dynamicLink = createDynamicLink(dashPayProfile!!, cftx)
            val shortDynamicLink = buildShortDynamicLink(dynamicLink)
            Result.success(workDataOf(
                    KEY_TX_ID to cftx.txId.bytes,
                    KEY_USER_ID to cftx.creditBurnIdentityIdentifier.toStringBase58(),
                    KEY_INVITE_ID to Address.fromPubKeyHash(wallet.params, cftx.creditBurnPublicKeyId.bytes).toBase58(),
                    KEY_DYNAMIC_LINK to dynamicLink.uri.toString(),
                    KEY_SHORT_DYNAMIC_LINK to shortDynamicLink.shortLink.toString()
            ))
        } catch (ex: Exception) {
            FirebaseCrashlytics.getInstance().log("Send Invite: failed to send contact request")
            FirebaseCrashlytics.getInstance().recordException(ex)
            Result.failure(workDataOf(
                    KEY_ERROR_MESSAGE to formatExceptionMessage("send invite", ex)))
        }
    }

    private fun createDynamicLink(dashPayProfile: DashPayProfile, cftx: CreditFundingTransaction): DynamicLink {
        log.info("creating dynamic link for invitation")
        val username = dashPayProfile.username
        val nameLabel = dashPayProfile.nameLabel
        val avatarUrlEncoded = URLEncoder.encode(dashPayProfile.avatarUrl, StandardCharsets.UTF_8.toString())
        return FirebaseDynamicLinks.getInstance()
                .createDynamicLink().apply {
                    link = InvitationLinkData.create(username, nameLabel, avatarUrlEncoded, cftx).link
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
                    imageUrl = Uri.parse("https://dashpay.site/image.php?image=$avatarUrlEncoded")
                    description = applicationContext.getString(R.string.invitation_preview_message, nameLabel)
                }.build())
                .setGoogleAnalyticsParameters(DynamicLink.GoogleAnalyticsParameters.Builder(
                        Constants.Invitation.UTM_SOURCE,
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