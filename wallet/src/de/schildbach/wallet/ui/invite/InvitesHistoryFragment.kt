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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.schildbach.wallet.Constants
import de.schildbach.wallet.observeOnce
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_invites_history.*
import org.dash.wallet.common.ui.FancyAlertDialog
import org.slf4j.LoggerFactory

class InvitesHistoryFragment : Fragment(R.layout.fragment_invites_history), InvitesAdapter.OnItemClickListener {

    companion object {
        private val log = LoggerFactory.getLogger(InvitesHistoryFragment::class.java)

        fun newInstance() = InvitesHistoryFragment()
    }

    private lateinit var invitesHistoryViewModel: InvitesHistoryViewModel
    private lateinit var filterViewModel: InvitesHistoryFilterViewModel
    private lateinit var createInviteViewModel: CreateInviteViewModel

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
        invitesHistoryViewModel = ViewModelProvider(this)[InvitesHistoryViewModel::class.java]
        invitesHistoryViewModel.filterClick.observe(this, Observer {
            InviteFilterSelectionDialog.createDialog(this)
                    .show(requireActivity().supportFragmentManager, "inviteFilterDialog")
        })

        filterViewModel = ViewModelProvider(this)[InvitesHistoryFilterViewModel::class.java]
        filterViewModel.filterBy.observe(this, Observer {
            invitesAdapter.onFilter(it)
        })

        createInviteViewModel = ViewModelProvider(this)[CreateInviteViewModel::class.java]
        createInviteViewModel.isAbleToCreateInviteLiveData.observe(requireActivity(), Observer {
            if (it != null) {
                invitesAdapter.showCreateInvite(it)
            }
        })
    }

    private fun initHistoryView() {
        invitesHistoryViewModel.invitationHistory.observe(requireActivity(), Observer {
            var inviteNumber = 1
            if (it != null) {
                invitesAdapter.history = it.sortedBy { invite -> invite.sentAt }
                        .map { invite -> InvitationItem(InvitesAdapter.INVITE, invite, inviteNumber++) }
            }
        })
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
                InviteFriendActivity.startOrError(requireActivity(), startedByHistory = true)
            }
            else -> {
                log.info("showing invitation for ${invitationItem.invitation!!.userId}")
                startActivity(InviteFriendActivity.createIntentExistingInvite(requireActivity(),
                        invitationItem.invitation.userId,
                        invitationItem.uniqueIndex))
            }
        }
    }
}
