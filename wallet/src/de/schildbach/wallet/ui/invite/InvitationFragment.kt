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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.dynamiclinks.ShortDynamicLink
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_payments.toolbar
import kotlinx.android.synthetic.main.fragment_invite_created.*

const val REQUEST_CODE_SHARE = 1

abstract class InvitationFragment(fragmentResId: Int) : Fragment(fragmentResId) {

    protected val viewModel by lazy {
        ViewModelProvider(requireActivity()).get(InvitationFragmentViewModel::class.java)
    }

    protected fun shareInvitation(shareImage: Boolean, shortLink: String?) {
        ShareCompat.IntentBuilder.from(requireActivity()).apply {
            setSubject(getString(R.string.invitation_share_title))
            setText(shortLink)
            if (shareImage) {
                setType(Constants.Invitation.MIMETYPE_WITH_IMAGE)
                val fileUri: Uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.file_attachment", viewModel.invitationPreviewImageFile!!)
                setStream(fileUri)
            } else {
                setType(Constants.Invitation.MIMETYPE)
            }
            setChooserTitle(R.string.invitation_share_message)
            startActivityForResult(createChooserIntent(), REQUEST_CODE_SHARE)
        }
    }

    protected fun setupInvitationPreviewTemplate(profile: DashPayProfile) {
        val profilePictureEnvelope: InvitePreviewEnvelopeView = invitation_bitmap_template.findViewById(R.id.bitmap_template_profile_picture_envelope)
        val messageHtml = getString(R.string.invitation_preview_message, "<b>${profile.nameLabel}</b>")
        val message = HtmlCompat.fromHtml(messageHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        val messageView = invitation_bitmap_template.findViewById<TextView>(R.id.bitmap_template_message)
        messageView.text = message
        ProfilePictureDisplay.display(profilePictureEnvelope.avatarView, profile, false, disableTransition = true,
                listener = object : ProfilePictureDisplay.OnResourceReadyListener {
                    override fun onResourceReady(resource: Drawable?) {
                        invitation_bitmap_template.post {
                            viewModel.saveInviteBitmap(invitation_bitmap_template)
                        }
                    }
                })
    }

    protected fun showPreviewDialog() {
        val previewDialog = InvitePreviewDialog.newInstance(requireContext(), viewModel.dashPayProfile!!)
        previewDialog.show(childFragmentManager, null)
    }

    protected fun copyInvitationLink(shortLink: String?) {
        val clipboardManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(getString(R.string.invitation_share_title), viewModel.shortDynamicLinkData))
        Toast(context).toast(R.string.receive_copied)
    }
}
