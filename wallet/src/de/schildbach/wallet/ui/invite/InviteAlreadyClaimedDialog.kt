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
import android.widget.Button
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog

open class InviteAlreadyClaimedDialog : AdaptiveDialog(R.layout.invitation_already_claimed_view) {
    companion object {
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_INVITE = "invite"

        fun newInstance(context: Context, profile: DashPayProfile): AdaptiveDialog {
            return newInstance(context, profile.nameLabel).apply {
                arguments!!.putParcelable(EXTRA_PROFILE, profile)
            }
        }

        @JvmStatic
        fun newInstance(context: Context, invite: InvitationLinkData): AdaptiveDialog {
            return newInstance(context, invite.displayName).apply {
                requireArguments().putParcelable(EXTRA_INVITE, invite)
            }
        }

        fun newInstance(context: Context, nameLabel: String): AdaptiveDialog {
            val messageHtml = context.getString(R.string.invitation_already_claimed_message, "<b>$nameLabel</b>")
            return create(null, "", messageHtml, context.getString(R.string.button_ok))
        }

        @JvmStatic
        fun create(
            @DrawableRes icon: Int?,
            title: String,
            message: String,
            negativeButtonText: String
        ): InviteAlreadyClaimedDialog {
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
        ): InviteAlreadyClaimedDialog {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putBoolean(CUSTOM_DIALOG_ARG, true)
            }
            return InviteAlreadyClaimedDialog().apply {
                arguments = args
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val messageObj = args.getString("message")!!
        val message: TextView = view.findViewById(R.id.dialog_message)!!
        message.text = HtmlCompat.fromHtml(messageObj, HtmlCompat.FROM_HTML_MODE_COMPACT)
        view.findViewById<Button>(R.id.dialog_positive_button)!!.isVisible = false
        view.findViewById<Button>(R.id.dialog_title)!!.isVisible = false
        val profilePictureEnvelope: InviteErrorEnvelopeView = view.findViewById(R.id.profile_picture_envelope)!!
        if (args.containsKey(EXTRA_PROFILE)) {
            val profile = args.getParcelable<DashPayProfile>(EXTRA_PROFILE)
            profilePictureEnvelope.avatarProfile = profile
        } else {
            val invite = args.getParcelable<InvitationLinkData>(EXTRA_INVITE)!!
            ProfilePictureDisplay.display(
                profilePictureEnvelope.avatarView,
                invite.avatarUrl,
                null,
                invite.displayName
            )
        }
    }
}
