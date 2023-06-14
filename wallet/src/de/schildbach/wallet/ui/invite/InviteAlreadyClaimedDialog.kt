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
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invitation_already_claimed_view.*
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

open class InviteAlreadyClaimedDialog : FancyAlertDialog() {

    companion object {

        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_INVITE = "invite"

        fun newInstance(context: Context, profile: DashPayProfile): FancyAlertDialog {
            return newInstance(context, profile.nameLabel).apply {
                arguments!!.putParcelable(EXTRA_PROFILE, profile)
            }
        }

        @JvmStatic
        fun newInstance(context: Context, invite: InvitationLinkData): FancyAlertDialog {
            return newInstance(context, invite.displayName).apply {
                requireArguments().putParcelable(EXTRA_INVITE, invite)
            }
        }

        fun newInstance(context: Context, nameLabel: String): FancyAlertDialog {
            val messageHtml = context.getString(R.string.invitation_already_claimed_message, "<b>${nameLabel}</b>")
            return InviteAlreadyClaimedDialog().apply {
                arguments = createBaseArguments(Type.INFO, 0, R.string.okay, 0)
                        .apply {
                            putString("message", messageHtml)
                        }
            }
        }
    }

    override val customContentViewResId: Int
        get() = R.layout.invitation_already_claimed_view

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        if (args.containsKey(EXTRA_PROFILE)) {
            val profile = args.getParcelable<DashPayProfile>(EXTRA_PROFILE)
            profile_picture_envelope.avatarProfile = profile
        } else {
            val invite = args.getParcelable<InvitationLinkData>(EXTRA_INVITE)!!
            ProfilePictureDisplay.display(profile_picture_envelope.avatarView, invite.avatarUrl, null, invite.displayName)
        }
    }
}