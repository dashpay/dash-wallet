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

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentInviteCreatedBinding
import de.schildbach.wallet_test.databinding.InvitationBitmapTemplateBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@ExperimentalCoroutinesApi
class InviteCreatedFragment : InvitationFragment(R.layout.fragment_invite_created) {
    private val binding by viewBinding(FragmentInviteCreatedBinding::bind)
    private val args by navArgs<InviteCreatedFragmentArgs>()
    override val invitationBitmapTemplateBinding: InvitationBitmapTemplateBinding
        get() = binding.invitationBitmapTemplate

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = ""
        binding.profilePictureEnvelope.isVisible = false
        binding.previewButton.isVisible = false
        binding.inviteCreationProgressTitle.text = getString(R.string.invitation_creating_progress_loading)
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(binding.toolbar)

        binding.previewButton.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.CREATED_PREVIEW)
            showPreviewDialog()
        }
        binding.copyInvitationLink.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Invites.CREATED_COPY_LINK)
            copyInvitationLink()
        }
        binding.sendButton.setOnClickListener {
            shareInvitation(false)
        }
        binding.maybeLaterButton.setOnClickListener {
            viewModel.saveTag(binding.tagEdit.text.toString())
            viewModel.logEvent(AnalyticsConstants.Invites.CREATED_LATER)
            findNavController().popBackStack()
        }

        initViewModel()
    }

    private fun initViewModel() {
        viewModel.invitation.filterNotNull().observe(viewLifecycleOwner) {
            binding.tagEdit.setText(it.memo)
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) {
            binding.profilePictureEnvelope.avatarProfile = it
            setupInvitationPreviewTemplate(it!!)
        }

        viewModel.sendInviteStatusLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    if (it.data != null) {
                        viewModel.identityId.value = it.data.userId
                        binding.profilePictureEnvelope.isVisible = true
                        binding.previewButton.isVisible = true
                        binding.inviteCreationProgressTitle.text = getString(R.string.invitation_created_successfully)
                        binding.sendButton.isEnabled = true
                        binding.progress.isGone = true
                    }
                }
                Status.LOADING -> {
                    // sending has begun
                    binding.inviteCreationProgressTitle.text = getString(R.string.invitation_creating_progress_wip)
                    binding.sendButton.isEnabled = false
                    binding.progress.isGone = false
                }
                else -> {
                    binding.inviteCreationProgressTitle.text = getString(R.string.invitation_creating_error_title)
                    binding.progress.isGone = true
//                    // there was an error sending
//                    val errorDialog = FancyAlertDialog.newInstance(
//                        R.string.invitation_creating_error_title,
//                        R.string.invitation_creating_error_message,
//                        R.drawable.ic_error_creating_invitation,
//                        R.string.okay,
//                        0,
//                    )
//                    errorDialog.show(childFragmentManager, null)
                    viewModel.logEvent(AnalyticsConstants.Invites.ERROR_CREATE)
                }
            }
        }
    }

    private fun shareInvitation(shareImage: Boolean) {
        // save memo to the database
        viewModel.saveTag(binding.tagEdit.text.toString())
        viewModel.logEvent(AnalyticsConstants.Invites.CREATED_SEND)

        if (!binding.tagEdit.text.isNullOrBlank()) {
            viewModel.logEvent(AnalyticsConstants.Invites.CREATED_TAG)
        }

        super.shareInvitation(shareImage, viewModel.shortDynamicLinkData)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SHARE) {
            findNavController().popBackStack()
        }
    }

    private fun copyInvitationLink() {
        super.copyInvitationLink(viewModel.shortDynamicLinkData)
    }
}
