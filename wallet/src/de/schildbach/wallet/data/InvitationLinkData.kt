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

package de.schildbach.wallet.data

import android.net.Uri
import android.os.Parcelable
import de.schildbach.wallet.Constants
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.bitcoinj.evolution.AssetLockTransaction
import org.bouncycastle.crypto.params.KeyParameter
import java.util.concurrent.TimeUnit

enum class InvitationValidationState {
    /** there is no invitation present */
    NONE,
    /** the invitation is valid and can be used */
    VALID,
    /** this user already has an identity */
    ALREADY_HAS_IDENTITY,
    /** this user already has an identity and is requesting a username */
    ALREADY_HAS_REQUESTED_USERNAME,
    /** this invitation has already been claimed */
    ALREADY_CLAIMED,
    /** this invitation is not valid (malformed) */
    INVALID,
    /** the blockchain has not been synced, cannot check invite validity */
    NOT_SYNCED
}

@Parcelize
data class InvitationLinkData(
    val link: Uri,
    val isValid: Boolean? = null,
    val validationState: InvitationValidationState? = null,
    val validationTimestamp: Long? = null
) : Parcelable {
    companion object {
        private const val URI_PREFIX = "dashpay://invite"
        private const val PARAM_USER = "du"
        private const val PARAM_DISPLAY_NAME = "display-name"
        private const val PARAM_AVATAR_URL = "avatar-url"
        private const val PARAM_CFTX = "assetlocktx"
        private const val PARAM_PRIVATE_KEY = "pk"
        private const val PARAM_IS_LOCK = "islock"
        private val VALIDATION_EXPIRED = TimeUnit.MINUTES.toMillis(1)

        fun create(username: String, displayName: String, avatarUrl: String, cftx: AssetLockTransaction, aesKeyParameter: KeyParameter): InvitationLinkData {
            val privateKey = cftx.assetLockPublicKey.decrypt(aesKeyParameter)
            val linkBuilder = Uri.parse(URI_PREFIX).buildUpon()
                .appendQueryParameter(PARAM_USER, username)
                .appendQueryParameter(PARAM_CFTX, cftx.txId.toString())
                .appendQueryParameter(PARAM_PRIVATE_KEY, privateKey.getPrivateKeyAsWiF(Constants.NETWORK_PARAMETERS))
                .appendQueryParameter(PARAM_IS_LOCK, cftx.confidence.instantSendlock?.toStringHex())

            if (displayName.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
            }
            if (avatarUrl.isNotEmpty()) {
                linkBuilder.appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
            }
            return InvitationLinkData(linkBuilder.build(), null)
        }

        fun isValid(link: Uri): Boolean {
            val queryParams = link.queryParameterNames
            return queryParams.contains(PARAM_USER) &&
                queryParams.contains(PARAM_PRIVATE_KEY) &&
                queryParams.contains(PARAM_IS_LOCK)
        }
    }

    @IgnoredOnParcel
    val user by lazy {
        link.getQueryParameter(PARAM_USER)!!
    }

    @IgnoredOnParcel
    val displayName by lazy {
        link.getQueryParameter(PARAM_DISPLAY_NAME) ?: user
    }

    @IgnoredOnParcel
    val avatarUrl by lazy {
        link.getQueryParameter(PARAM_AVATAR_URL)?.run {
            Uri.decode(this)
        } ?: ""
    }

    @IgnoredOnParcel
    val assetLockTx by lazy {
        link.getQueryParameter(PARAM_CFTX)!!.lowercase()
    }

    @IgnoredOnParcel
    val privateKey by lazy {
        link.getQueryParameter(PARAM_PRIVATE_KEY)!!
    }

    @IgnoredOnParcel
    val instantSendLock by lazy {
        link.getQueryParameter(PARAM_IS_LOCK)!!.lowercase()
    }

    @Deprecated("use link")
    fun getUri(): Uri = Uri.parse("https://invitations.dashpay.io/applink").buildUpon()
        .appendQueryParameter(PARAM_USER, user)
        .appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
        .appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
        .appendQueryParameter(PARAM_CFTX, assetLockTx)
        .appendQueryParameter(PARAM_PRIVATE_KEY, privateKey)
        .appendQueryParameter(PARAM_IS_LOCK, instantSendLock)
        .build()

    val expired: Boolean
        get() = validationTimestamp?.let { it < System.currentTimeMillis() - VALIDATION_EXPIRED } ?: true

    fun validate(validationState: InvitationValidationState): InvitationLinkData {
        return copy(validationState = validationState, validationTimestamp = System.currentTimeMillis())
    }
}
