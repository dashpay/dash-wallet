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

package de.schildbach.wallet.ui.dashpay

import android.view.View
import de.schildbach.wallet_test.R
import org.dashj.platform.dpp.toHexString
import java.security.MessageDigest
import java.util.regex.Matcher
import java.util.regex.Pattern

class GravatarProfilePictureDialog : ExternalUrlProfilePictureDialog() {

    companion object {
        @JvmStatic
        fun newInstance(): GravatarProfilePictureDialog {
            return GravatarProfilePictureDialog()
        }
    }

    override val errorMessageId = R.string.gravatar_email_error
    override val fetchingMessageId = R.string.gravatar_fetching
    override val disclaimerMessageId = R.string.gravatar_disclaimer
    override val dialogTitleId = R.string.gravatar
    override val dialogIconId = R.drawable.ic_gravatar
    override val dialogPromptId = R.string.gravatar_email_prompt

    override fun initCustomView(): View {
        val customView = super.initCustomView()
        setEditHint(R.string.gravatar_email_sample)
        setEditSingleLine(true)
        return customView
    }

    private fun getMd5Hash(email: String): String {
        val md: MessageDigest = MessageDigest.getInstance("MD5")
        md.update(email.encodeToByteArray())
        val digest: ByteArray = md.digest()
        return digest.toHexString().toLowerCase()
    }

    override fun loadFromString(text: String) {
        val md5Hash = getMd5Hash(text)
        // fetch size 200px (s=200) and fail if not found (d=404) and the G rated image (rated=g)
        val url = "https://www.gravatar.com/avatar/$md5Hash?s=200&d=404&rating=g"
        loadUrl(url)
    }

    val VALID_EMAIL_ADDRESS_REGEX: Pattern = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE)

    // text must be an email address
    override fun isTextValid(text: String): Boolean {
        val matcher: Matcher = VALID_EMAIL_ADDRESS_REGEX.matcher(text)
        return matcher.find()
    }
}