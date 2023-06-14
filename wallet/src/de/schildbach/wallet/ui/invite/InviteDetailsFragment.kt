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

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint

import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_forgot_pin.toolbar
import kotlinx.android.synthetic.main.fragment_invite_details.*
import kotlinx.android.synthetic.main.fragment_invite_details.copy_invitation_link
import kotlinx.android.synthetic.main.fragment_invite_details.preview_button
import kotlinx.android.synthetic.main.fragment_invite_details.send_button
import kotlinx.android.synthetic.main.fragment_invite_details.tag_edit
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.KeyboardUtil

@AndroidEntryPoint
class InviteDetailsFragment : InvitationFragment(R.layout.fragment_invite_details) {

    companion object {
        private const val ARG_IDENTITY_ID = "identity_id"
        private const val ARG_STARTED_FROM_HISTORY = "started_from_history"
        private const val ARG_INVITE_INDEX = "invite_index"

        fun newInstance(identity: String, inviteIndex: Int, startedFromHistory: Boolean = false): InviteDetailsFragment {
            val fragment = InviteDetailsFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_IDENTITY_ID, identity)
                putBoolean(ARG_STARTED_FROM_HISTORY, startedFromHistory)
                putInt(ARG_INVITE_INDEX, inviteIndex)
            }
            return fragment
        }
    }

    var tagModified = false
    var inviteIndex = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)

        toolbar.title = requireContext().getString(R.string.menu_invite_title)
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)

        val actionBar = appCompatActivity.supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        preview_button.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_PREVIEW)
            showPreviewDialog()
        }
        copy_invitation_link.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_COPY_LINK)
            copyInvitationLink()
        }
        send_button.setOnClickListener {
            shareInvitation(true)
        }
        send_button.setOnLongClickListener {
            shareInvitation(false)
            true
        }
        tag_edit.doAfterTextChanged {
            tagModified = true
            memo.text = if (tag_edit.text.isNotEmpty()) {
                tag_edit.text.toString()
            } else {
                getTagHint()
            }
        }
        profile_button.setOnClickListener {
            lifecycleScope.launch {
                val profile = viewModel.getInvitedUserProfile()

                if (profile != null) {
                    startActivity(DashPayUserActivity.createIntent(requireContext(), profile))
                } else {
                    /*not sure why this is happening*/
                    AdaptiveDialog.create(
                        R.drawable.ic_warning,
                        getString(R.string.invitation_creating_error_message_not_synced),
                        getString(R.string.invitation_verifying_progress_title),
                        getString(R.string.button_ok)
                    ).show(requireActivity())
                }
            }
        }

        pending_view.isVisible = false
        claimed_view.isVisible = false

        initViewModel()
    }

    private fun initViewModel() {
        val identityId = requireArguments().getString(ARG_IDENTITY_ID)
        inviteIndex = requireArguments().getInt(ARG_INVITE_INDEX)
        viewModel.identityIdLiveData.value = identityId

        viewModel.invitationLiveData.observe(viewLifecycleOwner) {
            if (it.memo.isNotEmpty()) {
                tag_edit.setText(it.memo)
                memo.text = it.memo
            } else {
                val hint = getTagHint()
                tag_edit.hint = hint
                memo.text = hint
            }

            date.text = WalletUtils.formatDate(it.sentAt)
            if (it.acceptedAt != 0L) {
                showClaimed()
            } else {
                showPending(it)
            }
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) {
            setupInvitationPreviewTemplate(it!!)
        }
        viewModel.updateInvitedUserProfile()
        viewModel.logEvent(AnalyticsConstants.Invites.DETAILS)
    }

    private fun getTagHint() =
        requireContext().getString(R.string.invitation_created_title) + " " + inviteIndex

    private fun showPending(it: Invitation) {
        send_button.isVisible = it.canSendAgain()
        pending_view.isVisible = true
        copy_invitation_link.visibility = send_button.visibility
        claimed_view.isVisible = false
        if (!it.canSendAgain()) {
            memo.setText(R.string.invitation_invalid_invite_title)
            pending_view.isVisible = false
        }
    }

    private fun showClaimed() {
        viewModel.invitedUserProfile.observe(viewLifecycleOwner) {
            if (it != null) {
                icon.setImageResource(R.drawable.ic_claimed_invite)
                claimed_view.isVisible = true
                pending_view.isVisible = false
                preview_button.isVisible = false
                profile_button.isVisible = true
                status.setText(R.string.invitation_details_invite_used_by)
                ProfilePictureDisplay.display(avatarIcon, it)
                if (it.displayName.isEmpty()) {
                    display_name.text = it.username
                    username.text = ""
                } else {
                    display_name.text = it.displayName
                    username.text = it.username
                }
            } else {
                // this means that a username was not registered (yet)
                status.setText(R.string.invitation_details_invite_without_username)
                pending_view.isVisible = false
                profile_button.isVisible = false
                claimed_view.isVisible = true
            }
        }
    }

    private fun shareInvitation(shareImage: Boolean) {
        // save memo to the database
        viewModel.saveTag(tag_edit.text.toString())
        viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_SEND_AGAIN)

        if (!tag_edit.text.isNullOrBlank()) {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_TAG)
        }

        super.shareInvitation(shareImage, viewModel.invitation.shortDynamicLink)
    }

    private fun copyInvitationLink() {
        super.copyInvitationLink(viewModel.invitation.shortDynamicLink)
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
            android.R.id.home -> {
                requireActivity().onBackPressed()
                return true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // save memo to the database
        if (tagModified) {
            viewModel.saveTag(tag_edit.text.toString())
        }
    }
}
