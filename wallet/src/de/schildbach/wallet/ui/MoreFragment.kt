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
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.lifecycleOwner
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.showBlockchainSyncingMessage
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_edit_profile.*
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.android.synthetic.main.activity_more.dashpayUserAvatar
import kotlinx.android.synthetic.main.activity_more.userInfoContainer
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.dashevo.dashpay.BlockchainIdentity

class MoreFragment : Fragment(R.layout.activity_more) {

    private var blockchainState: BlockchainState? = null
    private lateinit var dashPayProfile: DashPayProfile
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
            ReportIssueDialogBuilder.createReportIssueDialog(requireActivity(),
                    WalletApplication.getInstance()).show()
        }

        update_profile_status_container.visibility = View.GONE


        editProfileViewModel = ViewModelProvider(this).get(EditProfileViewModel::class.java)

        // blockchainIdentityData is observed instead of using PlatformRepo.getBlockchainIdentity()
        // since neither PlatformRepo nor blockchainIdentity is initialized when there is no username
        editProfileViewModel.blockchainIdentityData.observe(viewLifecycleOwner, Observer {
            if (it != null && it.creationState >= BlockchainIdentityData.CreationState.DONE) {

                // observe our profile
                editProfileViewModel.dashPayProfileData
                        .observe(viewLifecycleOwner, Observer { profile ->
                            if (profile != null) {
                                dashPayProfile = profile
                                showProfileSection(profile)
                            }
                        })

                // track the status of broadcast changes to our profile
                editProfileViewModel.updateProfileRequestState.observe(viewLifecycleOwner, Observer { state ->
                    if (state != null) {
                        when (state.status) {
                            Status.SUCCESS -> {
                                Toast.makeText(requireActivity(), "Update successful", Toast.LENGTH_LONG).show()
                                update_profile_status_container.visibility = View.GONE
                                editProfile.visibility = View.VISIBLE
                            }
                            Status.ERROR -> {
                                var msg = state.message
                                if (msg == null) {
                                    msg = "!!Error!!  ${state.exception!!.message}"
                                }
                                Toast.makeText(requireActivity(), msg, Toast.LENGTH_LONG).show()
                                update_profile_status_container.visibility = View.VISIBLE
                                update_status_text.text = msg
                                editProfile.visibility = View.VISIBLE
                            }
                            Status.LOADING -> {
                                Toast.makeText(requireActivity(), "Processing update", Toast.LENGTH_LONG).show()
                                update_profile_status_container.visibility = View.VISIBLE
                                editProfile.visibility = View.GONE
                            }
                            Status.CANCELED -> {
                                update_profile_status_container.visibility = View.VISIBLE
                                update_status_text.text = "Cancelled" //hard coded text
                                editProfile.visibility = View.VISIBLE
                            }
                        }
                    }
                })
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

        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(requireContext(),
                profile.username.toCharArray()[0])
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(dashpayUserAvatar).load(profile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(dashpayUserAvatar)
        } else {
            dashpayUserAvatar.setImageDrawable(defaultAvatar)
        }
        editProfile.setOnClickListener {
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
