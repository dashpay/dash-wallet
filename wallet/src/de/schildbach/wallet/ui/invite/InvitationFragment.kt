/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.invite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.InvitationBitmapTemplateBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay

const val REQUEST_CODE_SHARE = 1

@ExperimentalCoroutinesApi
@AndroidEntryPoint
abstract class InvitationFragment(fragmentResId: Int) : Fragment(fragmentResId) {
    protected val viewModel by viewModels<InvitationFragmentViewModel>()
    abstract val invitationBitmapTemplateBinding: InvitationBitmapTemplateBinding
    var sendInviteLauncher: ActivityResultLauncher<Intent>? = null

    protected fun shareInvitation(shareImage: Boolean, shortLink: String?) {
        ShareCompat.IntentBuilder.from(requireActivity()).apply {
            setSubject(getString(R.string.invitation_share_title))
            setText(shortLink)
            if (shareImage) {
                setType(Constants.Invitation.MIMETYPE_WITH_IMAGE)
                val fileUri: Uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${BuildConfig.APPLICATION_ID}.file_attachment",
                    viewModel.invitationPreviewImageFile!!
                )
                setStream(fileUri)
            } else {
                setType(Constants.Invitation.MIMETYPE)
            }
            setChooserTitle(R.string.invitation_share_message)
            sendInviteLauncher?.launch(createChooserIntent())
        }
    }

    protected fun setupInvitationPreviewTemplate(profile: DashPayProfile) {
        val profilePictureEnvelope: InvitePreviewEnvelopeView = invitationBitmapTemplateBinding.bitmapTemplateProfilePictureEnvelope
        val messageHtml = getString(R.string.invitation_preview_message, "<b>${profile.nameLabel}</b>")
        val message = HtmlCompat.fromHtml(messageHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        val messageView = invitationBitmapTemplateBinding.bitmapTemplateMessage
        messageView.text = message
        ProfilePictureDisplay.display(
            profilePictureEnvelope.avatarView,
            profile,
            false,
            disableTransition = true,
            listener = object : ProfilePictureDisplay.OnResourceReadyListener {
                override fun onResourceReady(resource: Drawable?) {
                    invitationBitmapTemplateBinding.root.post {
                        viewModel.saveInviteBitmap(invitationBitmapTemplateBinding.root)
                    }
                }
            }
        )
    }

    protected fun showPreviewDialog() {
        val previewDialog = InvitePreviewDialog.newInstance(requireContext(), viewModel.dashPayProfile.value!!)
        previewDialog.show(childFragmentManager, null)
    }

    protected fun copyInvitationLink(shortLink: String?) {
        val clipboardManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.invitation_share_title), shortLink))
        Toast(context).toast(R.string.copied)
    }
}
