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
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.util.KeyboardUtil
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_payments.toolbar
import kotlinx.android.synthetic.main.fragment_create_new_invite.*
import kotlinx.android.synthetic.main.fragment_invite_created.*
import kotlinx.android.synthetic.main.fragment_invites_history.*
import org.slf4j.LoggerFactory

class InvitesHistoryFragment : Fragment(R.layout.fragment_invites_history), InvitesAdapter.OnItemClickListener {

    companion object {
        private val log = LoggerFactory.getLogger(InvitesHistoryFragment::class.java)

        fun newInstance() = InvitesHistoryFragment()
    }

    private lateinit var invitesHistoryViewModel: InvitesHistoryViewModel
    private lateinit var filterViewModel: InvitesHistoryFilterViewModel

    private lateinit var invitesAdapter: InvitesAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)

        toolbar.title = requireContext().getString(R.string.menu_invite_title)
        val appCompatActivity = requireActivity() as AppCompatActivity
        appCompatActivity.setSupportActionBar(toolbar)

        initViewModel()

        invitesAdapter = InvitesAdapter(this, invitesHistoryViewModel.filterClick)
        history_rv.layoutManager = LinearLayoutManager(requireContext())
        history_rv.adapter = this.invitesAdapter

        initHistoryView()

        create_new_invitation.setOnClickListener {
            InviteFriendActivity.startOrError(requireActivity())
        }
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
    }

    private fun initHistoryView() {
        invitesHistoryViewModel.invitationHistory.observe(requireActivity(), Observer {
            if (it != null) {
                invitesAdapter.history = it.sortedBy { invite -> invite.sentAt }
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.close_button_white_options, menu)
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

    override fun onItemClicked(view: View, invitation: Invitation) {
        if (invitation.userId != InvitesAdapter.headerId.toStringBase58()) {
            log.info("showing invitation for ${invitation.userId}")
        }
    }
}
