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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentInviteCreatedBinding
import de.schildbach.wallet_test.databinding.InvitationBitmapTemplateBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.util.KeyboardUtil

class InviteCreatedFragment : InvitationFragment(R.layout.fragment_invite_created) {

    companion object {
        private const val ARG_IDENTITY_ID = "identity_id"
        private const val ARG_STARTED_FROM_HISTORY = "started_from_history"

        fun newInstance(identity: String, startedFromHistory: Boolean = false): InviteCreatedFragment {
            val fragment = InviteCreatedFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_IDENTITY_ID, identity)
                putBoolean(ARG_STARTED_FROM_HISTORY, startedFromHistory)
            }
            return fragment
        }
    }
    private lateinit var binding: FragmentInviteCreatedBinding
    override val invitationBitmapTemplateBinding: InvitationBitmapTemplateBinding
        get() = binding.invitationBitmapTemplate

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentInviteCreatedBinding.bind(view)
        setHasOptionsMenu(true)

        binding.toolbar.title = ""
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
            finishActivity()
        }

        initViewModel()
    }

    private fun finishActivity() {
        // was this fragment created indirectly by InvitesHistoryActivity
        // If yes, then Maybe Later will start InvitesHistoryActivity
        // If no, InvitesHistoryActivity started this fragment, so just finish()
        if (!requireArguments().getBoolean(ARG_STARTED_FROM_HISTORY)) {
            startActivity(InvitesHistoryActivity.createIntent(requireContext()))
        }
        requireActivity().finish()
    }

    private fun initViewModel() {
        val identityId = requireArguments().getString(ARG_IDENTITY_ID)
        viewModel.identityIdLiveData.value = identityId

        viewModel.invitationLiveData.observe(viewLifecycleOwner) {
            binding.tagEdit.setText(it.memo)
        }

        viewModel.dashPayProfile.observe(viewLifecycleOwner) {
            binding.profilePictureEnvelope.avatarProfile = it
            setupInvitationPreviewTemplate(it!!)
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
            finishActivity()
        }
    }

    private fun copyInvitationLink() {
        super.copyInvitationLink(viewModel.shortDynamicLinkData)
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.close_button_white_options, menu)
        super.onCreateOptionsMenu(menu, menuInflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.option_close -> {
                requireActivity().run {
                    KeyboardUtil.hideKeyboard(this, binding.tagEdit)
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
