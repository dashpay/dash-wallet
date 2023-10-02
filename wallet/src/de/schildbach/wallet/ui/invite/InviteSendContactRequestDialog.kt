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
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.InviteSendContactRequestViewBinding
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

open class InviteSendContactRequestDialog : FancyAlertDialog() {

    companion object {

        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_ICON = "icon"

        fun newInstance(context: Context, profile: DashPayProfile): FancyAlertDialog {
            val messageHtml = context.getString(R.string.invitation_contact_request_sent_message, "<b>${profile.nameLabel}</b>")
            return InviteSendContactRequestDialog().apply {
                arguments = createBaseArguments(Type.INFO, 0, R.string.okay, 0)
                        .apply {
                            putString("message", messageHtml)
                            putInt(EXTRA_ICON, R.drawable.ic_invitation_contact_request_sent)
                            putParcelable(EXTRA_PROFILE, profile)
                        }
            }
        }
    }

    override val customContentViewResId: Int
        get() = R.layout.invite_send_contact_request_view

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = InviteSendContactRequestViewBinding.bind(view)
        requireArguments().apply {
            val profile = getParcelable<DashPayProfile>(EXTRA_PROFILE)
            ProfilePictureDisplay.display(binding.avatar, profile)
            val iconResId = getInt(EXTRA_ICON)
            binding.icon.setImageResource(iconResId)
        }
    }
}