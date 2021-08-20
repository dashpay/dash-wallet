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

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_invites_history.*
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class InvitesHistoryFragment(private val caller: String) :
    Fragment(R.layout.fragment_invites_history), InvitesAdapter.OnItemClickListener {

    companion object {
        private val log = LoggerFactory.getLogger(InvitesHistoryFragment::class.java)

        fun newInstance(caller: String = "") = InvitesHistoryFragment(caller)
    }

    private val invitesHistoryViewModel: InvitesHistoryViewModel by viewModels()
    private val filterViewModel: InvitesHistoryFilterViewModel by viewModels()
    private val createInviteViewModel: CreateInviteViewModel by viewModels()

    private lateinit var invitesAdapter: InvitesAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)

        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = requireContext().getString(R.string.menu_invite_title)
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)
        val actionBar = appCompatActivity.supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }


        initViewModel()

        invitesAdapter = InvitesAdapter(this, invitesHistoryViewModel.filterClick)
        history_rv.layoutManager = LinearLayoutManager(requireContext())
        history_rv.adapter = this.invitesAdapter

        initHistoryView()
    }

    private fun initViewModel() {
        invitesHistoryViewModel.filterClick.observe(this) {
            InviteFilterSelectionDialog.createDialog(this)
                .show(requireActivity().supportFragmentManager, "inviteFilterDialog")
        }

        filterViewModel.filterBy.observe(this) {
            invitesAdapter.onFilter(it)
        }

        createInviteViewModel.isAbleToCreateInviteLiveData.observe(requireActivity()) {
            if (it != null) {
                invitesAdapter.showCreateInvite(it)
            }
        }
    }

    private fun initHistoryView() {
        invitesHistoryViewModel.invitationHistory.observe(requireActivity()) {
            var inviteNumber = 1
            if (it != null) {
                invitesAdapter.history = it.sortedBy { invite -> invite.sentAt }
                    .map { invite -> InvitationItem(InvitesAdapter.INVITE, invite, inviteNumber++) }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                requireActivity().onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onItemClicked(view: View, invitationItem: InvitationItem) {
        when (invitationItem.type) {
            InvitesAdapter.INVITE_HEADER,
            InvitesAdapter.EMPTY_HISTORY -> {

            }
            InvitesAdapter.INVITE_CREATE -> {
                createInviteViewModel.logEvent(when(caller) {
                    "more" -> Constants.Events.Invites.CREATE_MORE
                    else -> Constants.Events.Invites.CREATE_HISTORY
                })
                InviteFriendActivity.startOrError(requireActivity(), startedByHistory = true)
            }
            else -> {
                log.info("showing invitation for ${invitationItem.invitation!!.userId}")
                startActivity(
                    InviteFriendActivity.createIntentExistingInvite(
                        requireActivity(),
                        invitationItem.invitation.userId,
                        invitationItem.uniqueIndex
                    )
                )
            }
        }
    }
}
