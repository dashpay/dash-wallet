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

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint

import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentInviteDetailsBinding
import de.schildbach.wallet_test.databinding.InvitationBitmapTemplateBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class InviteDetailsFragment : InvitationFragment(R.layout.fragment_invite_details) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(InviteDetailsFragment::class.java)
    }
    private val binding by viewBinding(FragmentInviteDetailsBinding::bind)
    private val args by navArgs<InviteDetailsFragmentArgs>()
    override val invitationBitmapTemplateBinding: InvitationBitmapTemplateBinding
        get() = binding.invitationBitmapTemplate

    private var tagModified = false
    private var inviteIndex = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.invitation_title)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.previewButton.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_PREVIEW)
            showPreviewDialog()
        }
        binding.copyInvitationLink.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_COPY_LINK)
            copyInvitationLink()
        }
        binding.sendButton.setOnClickListener {
            shareInvitation(true)
        }
        binding.sendButton.setOnLongClickListener {
            shareInvitation(false)
            true
        }
        binding.tagEdit.doAfterTextChanged {
            tagModified = true
            binding.memo.text = if (binding.tagEdit.text.isNotEmpty()) {
                binding.tagEdit.text.toString()
            } else {
                getTagHint()
            }
        }
        binding.profileButton.setOnClickListener {
            lifecycleScope.launch {
                val profile = viewModel.getInvitedUserProfile()

                if (profile != null) {
                    startActivity(DashPayUserActivity.createIntent(requireContext(), profile))
                } else {
                    /* not sure why this is happening */
                    AdaptiveDialog.create(
                        R.drawable.ic_warning,
                        getString(R.string.invitation_creating_error_message_not_synced),
                        getString(R.string.invitation_verifying_progress_title),
                        getString(R.string.button_ok)
                    ).show(requireActivity())
                }
            }
        }

        binding.pendingView.isVisible = false
        binding.claimedView.isVisible = false

        initViewModel()

        sendInviteLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                findNavController().popBackStack()
            }
        }
    }

    private fun initViewModel() {
        val identityId = args.identityId
        inviteIndex = args.inviteIndex
        viewModel.identityId.value = identityId

        viewModel.invitation.filterNotNull().observe(viewLifecycleOwner) {
            log.info("invitation changed: $it")
            if (it.memo.isNotEmpty()) {
                binding.tagEdit.setText(it.memo)
                binding.memo.text = it.memo
            } else {
                val hint = getTagHint()
                binding.tagEdit.hint = hint
                binding.memo.text = hint
            }

            binding.date.text = WalletUtils.formatDate(it.sentAt)
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

    private fun getTagHint() = requireContext().getString(R.string.invitation_created_title) + " " + inviteIndex

    private fun showPending(it: Invitation) {
        binding.sendButton.isVisible = it.canSendAgain()
        binding.pendingView.isVisible = true
        binding.copyInvitationLink.visibility = binding.sendButton.visibility
        binding.claimedView.isVisible = false
        if (!it.canSendAgain()) {
            binding.memo.setText(R.string.invitation_invalid_invite_title)
            binding.pendingView.isVisible = false
            // binding.recoverView.isVisible = true
        } else {
            // binding.recoverView.isVisible = false
        }
    }

    private fun showClaimed() {
        viewModel.invitedUserProfile.observe(viewLifecycleOwner) {
            if (it != null) {
                binding.icon.setImageResource(R.drawable.ic_claimed_invite)
                binding.claimedView.isVisible = true
                binding.pendingView.isVisible = false
                // binding.recoverView.isVisible = false
                binding.previewButton.isVisible = false
                binding.profileButton.isVisible = true
                binding.status.setText(R.string.invitation_details_invite_used_by)
                ProfilePictureDisplay.display(binding.avatarIcon, it)
                if (it.displayName.isEmpty()) {
                    binding.displayName.text = it.username
                    binding.username.text = ""
                } else {
                    binding.displayName.text = it.displayName
                    binding.username.text = it.username
                }
            } else {
                // this means that a username was not registered (yet)
                binding.status.setText(R.string.invitation_details_invite_without_username)
                binding.pendingView.isVisible = false
                binding.profileButton.isVisible = false
                binding.claimedView.isVisible = true
            }
        }
    }

    private fun shareInvitation(shareImage: Boolean) {
        // save memo to the database
        viewModel.saveTag(binding.tagEdit.text.toString())
        viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_SEND_AGAIN)

        if (!binding.tagEdit.text.isNullOrBlank()) {
            viewModel.logEvent(AnalyticsConstants.Invites.DETAILS_TAG)
        }

        super.shareInvitation(shareImage, viewModel.invitation.value!!.shortDynamicLink)
    }

    private fun copyInvitationLink() {
        super.copyInvitationLink(viewModel.invitation.value!!.shortDynamicLink)
    }

    override fun onStop() {
        super.onStop()
        // save memo to the database
        if (tagModified) {
            viewModel.saveTag(binding.tagEdit.text.toString())
        }
    }
}
