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

enum class InvitationValidationState {
    VALID,
    ALREADY_HAS_IDENTITY,
    ALREADY_CLAIMED,
    INVALID,
    NONE,
    NOT_SYNCED
}

@Parcelize
data class InvitationLinkData(
    val link: Uri,
    var isValid: Boolean? = null,
    var validationState: InvitationValidationState? = null
) : Parcelable {

    companion object {
        private const val PARAM_USER = "du"
        private const val PARAM_USER_2 = "user"
        private const val PARAM_DISPLAY_NAME = "display-name"
        private const val PARAM_AVATAR_URL = "avatar-url"
        private const val PARAM_CFTX = "assetlocktx"
        private const val PARAM_CFTX_2 = "cftx"
        private const val PARAM_PRIVATE_KEY = "pk"
        private const val PARAM_IS_LOCK = "islock"
        private const val PARAM_IS_LOCK_2 = "is-lock"

        fun create(username: String, displayName: String, avatarUrl: String, cftx: AssetLockTransaction, aesKeyParameter: KeyParameter): InvitationLinkData {
            val privateKey = cftx.assetLockPublicKey.decrypt(aesKeyParameter)
            val linkBuilder = Uri.parse("https://invitations.dashpay.io/applink").buildUpon()
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
            return (((queryParams.contains(PARAM_USER) || queryParams.contains(PARAM_USER_2))
                    && (queryParams.contains(PARAM_CFTX) || queryParams.contains(PARAM_CFTX_2))
                    && queryParams.contains(PARAM_PRIVATE_KEY)
                    && (queryParams.contains(PARAM_IS_LOCK_2) || queryParams.contains(PARAM_IS_LOCK))))
        }
    }

    @IgnoredOnParcel
    val user by lazy {
        link.getQueryParameter(PARAM_USER) ?: link.getQueryParameter(PARAM_USER_2)!!
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
    val cftx by lazy {
        (link.getQueryParameter(PARAM_CFTX) ?: link.getQueryParameter(PARAM_CFTX_2)!!).lowercase()
    }

    @IgnoredOnParcel
    val privateKey by lazy {
        link.getQueryParameter(PARAM_PRIVATE_KEY)
    }

    @IgnoredOnParcel
    val instantSendLock by lazy {
        (link.getQueryParameter(PARAM_IS_LOCK) ?: link.getQueryParameter(PARAM_IS_LOCK_2)!!).lowercase()
    }

//    val isValid: Boolean
//        get() = validation == true

    fun getUri(): Uri = Uri.parse("https://invitations.dashpay.io/applink").buildUpon()
        .appendQueryParameter(PARAM_USER, user)
        .appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
        .appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
        .appendQueryParameter(PARAM_CFTX, cftx)
        .appendQueryParameter(PARAM_PRIVATE_KEY, privateKey)
        .appendQueryParameter(PARAM_IS_LOCK, instantSendLock)
        .build()
}
