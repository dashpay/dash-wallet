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
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog

open class InvitePreviewDialog : AdaptiveDialog(R.layout.invitation_preview_view) {
    companion object {
        private const val EXTRA_PROFILE = "profile"
        fun newInstance(context: Context, profile: DashPayProfile): InvitePreviewDialog {
            return newInstance(context, profile.nameLabel).apply {
                arguments!!.putParcelable(EXTRA_PROFILE, profile)
            }
        }

        fun newInstance(context: Context, nameLabel: String): InvitePreviewDialog {
            val messageHtml = context.getString(R.string.invitation_preview_message, "<b>${nameLabel}</b>")
            return create(null, "", messageHtml, context.getString(R.string.button_ok))
        }

        @JvmStatic
        fun create(
            @DrawableRes icon: Int?,
            title: String,
            message: String,
            negativeButtonText: String
        ): InvitePreviewDialog {
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
        ): InvitePreviewDialog {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putBoolean(CUSTOM_DIALOG_ARG, true)
            }
            return InvitePreviewDialog().apply {
                arguments = args
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val profilePictureEnvelope: InvitePreviewEnvelopeView = view.findViewById(R.id.profile_picture_envelope)!!
        val profile = requireArguments().getParcelable<DashPayProfile>("profile")
        profilePictureEnvelope.avatarProfile = profile
        val messageObj = requireArguments().getString(MESSAGE_ARG)!!
        val message: TextView = view.findViewById(R.id.dialog_message)!!
        message.text = HtmlCompat.fromHtml(messageObj, HtmlCompat.FROM_HTML_MODE_COMPACT)
        view.findViewById<Button>(R.id.dialog_positive_button)!!.isVisible = false
        view.findViewById<Button>(R.id.dialog_title)!!.isVisible = false
    }
}