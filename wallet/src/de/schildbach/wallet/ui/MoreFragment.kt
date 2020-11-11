/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui

import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.showBlockchainSyncingMessage
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity

class MoreFragment : Fragment(R.layout.activity_more) {

    private var blockchainState: BlockchainState? = null
    private lateinit var editProfileViewModel: EditProfileViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupActionBarWithTitle(R.string.more_title)

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(viewLifecycleOwner, Observer {
            blockchainState = it
        })

        buy_and_sell.setOnClickListener {
            if (blockchainState != null && blockchainState?.replaying!!) {
                requireActivity().showBlockchainSyncingMessage()
            } else {
                startBuyAndSellActivity()
            }
        }
        security.setOnClickListener {
            startActivity(Intent(requireContext(), SecurityActivity::class.java))
        }
        settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        tools.setOnClickListener {
            startActivity(Intent(requireContext(), ToolsActivity::class.java))
        }
        contact_support.setOnClickListener {
            ReportIssueDialogBuilder.createReportIssueDialog(requireContext(),
                    WalletApplication.getInstance()).show()
        }
        initViewModel()
    }

    private fun initViewModel() {
        editProfileViewModel = ViewModelProvider(this).get(EditProfileViewModel::class.java)

        // observe our profile
        editProfileViewModel.dashPayProfileData.observe(viewLifecycleOwner, Observer { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileSection(dashPayProfile)
            }
        })
        // track the status of broadcast changes to our profile
        editProfileViewModel.updateProfileRequestState.observe(viewLifecycleOwner, Observer { state ->
            if (state != null) {
                when (state.status) {
                    Status.SUCCESS -> {
                        edit_update_switcher.apply {
                            if (currentView.id == R.id.update_profile) {
                                showPrevious()
                            }
                        }
                    }
                    Status.ERROR -> {
                        var msg = state.message
                        if (msg == null) {
                            msg = "!!Error!!  ${state.exception!!.message}"
                        }
                        Toast.makeText(requireActivity(), msg, Toast.LENGTH_LONG).show()
                        edit_update_switcher.apply {
                            if (currentView.id == R.id.update_profile) {
                                showPrevious()
                            }
                        }
                    }
                    Status.LOADING -> {
                        edit_update_switcher.apply {
                            if (currentView.id == R.id.edit_profile) {
                                showNext()
                                update_profile_status_icon.setImageResource(R.drawable.identity_processing)
                                (update_profile_status_icon.drawable as AnimationDrawable).start()
                            }
                        }
                    }
                    Status.CANCELED -> {
                        edit_update_switcher.apply {
                            if (currentView.id == R.id.update_profile) {
                                showPrevious()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun showProfileSection(profile: DashPayProfile) {
        userInfoContainer.visibility = View.VISIBLE
        if (profile.displayName.isNotEmpty()) {
            username1.text = profile.displayName
            username2.text = profile.username
        } else {
            username1.text = profile.username
            username2.visibility = View.GONE
        }

        ProfilePictureDisplay.display(dashpayUserAvatar, profile)

        edit_profile.setOnClickListener {
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startBuyAndSellActivity() {
        val wallet = WalletApplication.getInstance().wallet
        startActivity(UpholdAccountActivity.createIntent(requireContext(), wallet))
    }

}
