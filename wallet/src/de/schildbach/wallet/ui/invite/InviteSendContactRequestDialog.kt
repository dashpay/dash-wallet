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

package de.schildbach.wallet.ui.invite

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.text.HtmlCompat
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog

open class InviteSendContactRequestDialog : AdaptiveDialog(R.layout.invite_send_contact_request_view) {
    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_ICON = "icon"

        fun newInstance(context: Context, profile: DashPayProfile): InviteSendContactRequestDialog {
            val messageHtml = context.getString(R.string.invitation_contact_request_sent_message, "<b>${profile.nameLabel}</b>")
            return create(null, "", messageHtml, context.getString(R.string.button_ok))
        }

        @JvmStatic
        fun create(
            @DrawableRes icon: Int?,
            title: String,
            message: String,
            negativeButtonText: String
        ): InviteSendContactRequestDialog {
            return custom(
                icon, title, message, negativeButtonText
            )
        }

        @JvmStatic
        fun custom(
            @DrawableRes icon: Int?,
            title: String?,
            message: String,
            negativeButtonText: String
        ): InviteSendContactRequestDialog {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putBoolean(CUSTOM_DIALOG_ARG, true)
            }
            return InviteSendContactRequestDialog().apply {
                arguments = args
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val icon: ImageView = view.findViewById(R.id.icon)
        requireArguments().apply {
            val messageObj = getString("message")!!
            val message: TextView = view.findViewById(R.id.dialog_message)!!
            message.text = HtmlCompat.fromHtml(messageObj, HtmlCompat.FROM_HTML_MODE_COMPACT)
            val profile = getParcelable<DashPayProfile>(EXTRA_PROFILE)
            ProfilePictureDisplay.display(avatar, profile)
            val iconResId = getInt(EXTRA_ICON)
            icon.setImageResource(iconResId)
        }
    }
}