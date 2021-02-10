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

import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.InvitationCreatedFragmentViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_payments.toolbar
import kotlinx.android.synthetic.main.fragment_invitation_created.*
import org.dash.wallet.common.ui.FancyAlertDialog


class InvitationCreatedFragment : Fragment(R.layout.fragment_invitation_created) {

    companion object {
        fun newInstance() = InvitationCreatedFragment()
    }

    val viewModel by lazy {
        ViewModelProvider(this).get(InvitationCreatedFragmentViewModel::class.java)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)

        toolbar.title = ""
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)

        displayOwnProfilePicture()
        preview_button.setOnClickListener {
            showPreviewDialog()
        }
        send_button.setOnClickListener {
//            val errorDialog = FancyAlertDialog.newInstance(R.string.invitation_creating_error_title,
//                    R.string.invitation_creating_error_message, R.drawable.ic_error_creating_invitation,
//                    R.string.okay, 0)
//            errorDialog.show(childFragmentManager, null)
            shareInvitation(true)
        }
        send_button.setOnLongClickListener {
            shareInvitation(false)
            true
        }

        maybe_later_button.setOnClickListener {
            val errorDialog = FancyAlertDialog.newInstance(R.string.invitation_cant_afford_title,
                    R.string.invitation_cant_afford_message, R.drawable.ic_cant_afford_invitation,
                    R.string.okay, 0)
            errorDialog.show(childFragmentManager, null)
        }
    }

    private fun displayOwnProfilePicture() {
        viewModel.dashPayProfileData.observe(viewLifecycleOwner, {
            profile_picture_envelope.avatarProfile = it
            setupInvitationPreviewTemplate(it!!)
        })
    }

    private fun shareInvitation(shareImage: Boolean) {
        IntentBuilder.from(requireActivity()).apply {
            setSubject("DashPay invitation")
            setText("sample://dashpay.invitation")
            if (shareImage) {
                setType(Constants.MIMETYPE_INVITATION_WITH_IMAGE)
                val fileUri: Uri = FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.file_attachment", viewModel.invitationPreviewImageFile!!)
                setStream(fileUri)
            } else {
                setType(Constants.MIMETYPE_INVITATION)
            }
            setChooserTitle(R.string.invitation_share_message)
            startChooser()
        }
    }

    private fun setupInvitationPreviewTemplate(profile: DashPayProfile) {
        val profilePictureEnvelope: InvitePreviewEnvelopeView = invitation_bitmap_template.findViewById(R.id.bitmap_template_profile_picture_envelope)
        val messageHtml = getString(R.string.invitation_preview_message, "<b>${profile.nameLabel}</b>")
        val message = HtmlCompat.fromHtml(messageHtml, HtmlCompat.FROM_HTML_MODE_COMPACT)
        val messageView = invitation_bitmap_template.findViewById<TextView>(R.id.bitmap_template_message)
        messageView.text = message
        ProfilePictureDisplay.display(profilePictureEnvelope.avatarView, profile, false,
                object : ProfilePictureDisplay.OnResourceReadyListener {
                    override fun onResourceReady(resource: Drawable?) {
                        invitation_bitmap_template.post {
                            viewModel.saveInviteBitmap(invitation_bitmap_template)
                        }
                    }
                })
    }

    private fun showPreviewDialog() {
        val previewDialog = InvitationPreviewDialog.newInstance(requireContext(), viewModel.dashPayProfile!!)
        previewDialog.show(childFragmentManager, null)
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.close_button_options, menu)
        super.onCreateOptionsMenu(menu, menuInflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.option_close -> {
                requireActivity().run {
                    KeyboardUtil.hideKeyboard(this, tag_edit)
                    finish()
                }
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }
}
