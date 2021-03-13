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
import kotlinx.android.parcel.Parcelize
import org.bitcoinj.evolution.CreditFundingTransaction

@Parcelize
data class InvitationLinkData(val link: Uri, var validation: Boolean?) : Parcelable {

    companion object {
        private const val PARAM_USER = "user"
        private const val PARAM_DISPLAY_NAME = "display-name"
        private const val PARAM_AVATAR_URL = "avatar-url"
        private const val PARAM_CFTX = "cftx"

        fun create(username: String, displayName: String, avatarUrl: String, cftx: CreditFundingTransaction): InvitationLinkData {
            val link = Uri.parse("https://invitations.dashpay.io/applink").buildUpon()
                    .appendQueryParameter(PARAM_USER, username)
                    .appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
                    .appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
                    .appendQueryParameter(PARAM_CFTX, cftx.txId.toString())
                    .build()
            return InvitationLinkData(link, null)
        }

        fun isValid(link: Uri): Boolean {
            val queryParams = link.queryParameterNames
            return (queryParams.contains(PARAM_USER)
                    && queryParams.contains(PARAM_DISPLAY_NAME)
                    && queryParams.contains(PARAM_AVATAR_URL)
                    && queryParams.contains(PARAM_CFTX))
        }
    }

    val user by lazy {
        link.getQueryParameter("user")!!
    }

    val displayName by lazy {
        link.getQueryParameter("display-name")!!
    }

    val avatarUrl by lazy {
        Uri.decode(link.getQueryParameter("avatar-url")!!)!!
    }

    val cftx by lazy {
        link.getQueryParameter("cftx")!!
    }

    val isValid: Boolean
        get() = validation == true
}
