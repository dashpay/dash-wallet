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
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.InvitationPreviewViewBinding
import org.dash.wallet.common.ui.FancyAlertDialog

open class InvitePreviewDialog : FancyAlertDialog() {

    companion object {
        fun newInstance(context: Context, profile: DashPayProfile): FancyAlertDialog {
            return newInstance(context, profile.nameLabel).apply {
                arguments!!.putParcelable("profile", profile)
            }
        }

        fun newInstance(context: Context, nameLabel: String): FancyAlertDialog {
            val messageHtml = context.getString(R.string.invitation_preview_message, "<b>${nameLabel}</b>")
            return InvitePreviewDialog().apply {
                arguments = createBaseArguments(Type.INFO, 0, 0, R.string.invitation_preview_close)
                        .apply {
                            putString("message", messageHtml)
                        }
            }
        }
    }

    override val customContentViewResId: Int
        get() = R.layout.invitation_preview_view

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = InvitationPreviewViewBinding.bind(view)

        val profile = requireArguments().getParcelable<DashPayProfile>("profile")
        binding.profilePictureEnvelope.avatarProfile = profile
    }
}