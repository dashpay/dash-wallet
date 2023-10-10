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
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentInvitesHistoryBinding
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class InvitesHistoryFragment(private val caller: String) :
    Fragment(R.layout.fragment_invites_history), InvitesAdapter.OnItemClickListener {

    companion object {
        private val log = LoggerFactory.getLogger(InvitesHistoryFragment::class.java)

        fun newInstance(caller: String = "") = InvitesHistoryFragment(caller)
    }

    // need default constructor to prevent crashes
    constructor() :this("")
    private lateinit var binding: FragmentInvitesHistoryBinding

    private val invitesHistoryViewModel: InvitesHistoryViewModel by viewModels()
    private val filterViewModel: InvitesHistoryFilterViewModel by viewModels()
    private val createInviteViewModel: CreateInviteViewModel by viewModels()

    private lateinit var invitesAdapter: InvitesAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentInvitesHistoryBinding.bind(view)
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
        binding.historyRv.layoutManager = LinearLayoutManager(requireContext())
        binding.historyRv.adapter = this.invitesAdapter

        initHistoryView()
    }

    private fun initViewModel() {
        invitesHistoryViewModel.filterClick.observe(viewLifecycleOwner) {
            InviteFilterSelectionDialog.createDialog(this)
                .show(requireActivity().supportFragmentManager, "inviteFilterDialog")
        }

        filterViewModel.filterBy.observe(viewLifecycleOwner) {
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
                    "more" -> AnalyticsConstants.Invites.CREATE_MORE
                    else -> AnalyticsConstants.Invites.CREATE_HISTORY
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
