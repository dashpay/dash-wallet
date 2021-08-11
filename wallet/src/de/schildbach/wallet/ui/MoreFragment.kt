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
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.observeOnce
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.ui.invite.CreateInviteViewModel
import de.schildbach.wallet.ui.invite.InviteFriendActivity
import de.schildbach.wallet.ui.invite.InvitesHistoryActivity
import de.schildbach.wallet.util.showBlockchainSyncingMessage
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.android.synthetic.main.fragment_updating_profile.*
import kotlinx.android.synthetic.main.update_profile_error.*
import kotlinx.android.synthetic.main.update_profile_error.error_try_again
import kotlinx.android.synthetic.main.update_profile_error.view.*
import kotlinx.android.synthetic.main.update_profile_network_unavailable.*
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.slf4j.LoggerFactory

class MoreFragment : BottomNavFragment(R.layout.activity_more) {

    override val navigationItemId = R.id.more

    private var blockchainState: BlockchainState? = null
    private lateinit var editProfileViewModel: EditProfileViewModel
    private lateinit var mainActivityViewModel: MainActivityViewModel
    private lateinit var createInviteViewModel: CreateInviteViewModel
    private val walletApplication = WalletApplication.getInstance()
    private var showInviteSection = false
    private var lastInviteCount = -1

    companion object {
        const val PROFILE_VIEW = 0
        const val UPDATING_PROFILE_VIEW = 1
        const val UPDATE_PROFILE_ERROR_VIEW = 2
        const val UPDATE_PROFILE_NETWORK_ERROR_VIEW = 3

        private val log = LoggerFactory.getLogger(MoreFragment::class.java)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupActionBarWithTitle(R.string.more_title)

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(viewLifecycleOwner, Observer {
            blockchainState = it
        })

        invite.visibility = View.GONE
        invite.setOnClickListener {
            // use observeOnce to avoid the history screen being recreated
            // after returning to the More Screen after an invite is created
            mainActivityViewModel.inviteHistory.observeOnce(requireActivity(), Observer {
                if (it == null || it.isEmpty()) {
                    InviteFriendActivity.startOrError(requireActivity())
                } else {
                    startActivity(InvitesHistoryActivity.createIntent(requireContext()))
                }
            })
        }
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
        error_try_again.setOnClickListener {
            editProfileViewModel.retryBroadcastProfile()
        }
        listOf(cancel, cancel_network_error).forEach {
            it.setOnClickListener { dismissProfileError() }
        }
        edit_update_switcher.isVisible = false
        join_dashpay_btn.setOnClickListener {
            mainActivityViewModel.goBackAndStartActivityEvent.postValue(CreateUsernameActivity::class.java)
        }
        initViewModel()
    }

    private fun dismissProfileError() {
        val allowedScreensToSwitch = listOf(UPDATE_PROFILE_ERROR_VIEW, UPDATE_PROFILE_NETWORK_ERROR_VIEW)
        if (edit_update_switcher.displayedChild in allowedScreensToSwitch) {
            edit_update_switcher.displayedChild = PROFILE_VIEW // reset to previous profile
            editProfileViewModel.clearLastAttemptedProfile()
        }
    }

    private fun initViewModel() {
        mainActivityViewModel = ViewModelProvider(requireActivity())[MainActivityViewModel::class.java]
        editProfileViewModel = ViewModelProvider(this)[EditProfileViewModel::class.java]
        createInviteViewModel = ViewModelProvider(this)[CreateInviteViewModel::class.java]

        // observe our profile
        editProfileViewModel.dashPayProfileData.observe(viewLifecycleOwner, Observer { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileSection(dashPayProfile)
            }
        })
        // track the status of broadcast changes to our profile
        editProfileViewModel.updateProfileRequestState.observe(viewLifecycleOwner, Observer { state ->
            if (state != null) {
                (requireActivity() as LockScreenActivity).imitateUserInteraction()
                when (state.status) {
                    Status.SUCCESS -> {
                        edit_update_switcher.apply {
                            displayedChild = PROFILE_VIEW
                        }
                    }
                    Status.ERROR -> {
                        edit_update_switcher.apply {
                            val networkUnavailable = blockchainState?.impediments?.contains(BlockchainState.Impediment.NETWORK) == true
                            if (networkUnavailable) {
                                if (displayedChild != UPDATE_PROFILE_NETWORK_ERROR_VIEW) {
                                    displayedChild = UPDATE_PROFILE_NETWORK_ERROR_VIEW
                                }
                            } else if (displayedChild != UPDATE_PROFILE_ERROR_VIEW) {
                                displayedChild = UPDATE_PROFILE_ERROR_VIEW
                                error_code_text.text = getString(R.string.error_updating_profile_code, state.message)
                            }
                        }
                    }
                    Status.LOADING -> {
                        edit_update_switcher.apply {
                            val allowedScreensToSwitch = listOf(PROFILE_VIEW, UPDATE_PROFILE_ERROR_VIEW,
                                    UPDATE_PROFILE_NETWORK_ERROR_VIEW)
                            if (displayedChild in allowedScreensToSwitch) {
                                //showNext()
                                displayedChild = UPDATING_PROFILE_VIEW
                                update_profile_status_icon.setImageResource(R.drawable.identity_processing)
                                (update_profile_status_icon.drawable as AnimationDrawable).start()
                            }
                        }
                    }
                    Status.CANCELED -> {
                        edit_update_switcher.apply {
                            if (displayedChild != PROFILE_VIEW) {
                                displayedChild = PROFILE_VIEW
                            }
                        }
                        log.info("update profile operation cancelled")
                    }
                }
            }
        })

        mainActivityViewModel.isAbleToCreateIdentityLiveData.observe(viewLifecycleOwner, Observer {
            join_dashpay_container.visibility = if (it) {
                View.VISIBLE
            } else {
                View.GONE
            }
        })

        createInviteViewModel.isAbleToPerformInviteAction.observe(viewLifecycleOwner, Observer {
            showInviteSection(it)
        })
    }

    private fun showInviteSection(showInviteSection: Boolean) {
        this.showInviteSection = showInviteSection

        //show the invite section only after the profile section is visible
        //to avoid flickering
        if (edit_update_switcher.isVisible)
            showHideInviteSection()
    }

    private fun showProfileSection(profile: DashPayProfile) {
        edit_update_switcher.visibility = View.VISIBLE
        edit_update_switcher.displayedChild = PROFILE_VIEW
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
        //if the invite section is not visible, show/hide it
        if (!invite.isVisible)
            showHideInviteSection()
    }

    override fun onResume() {
        super.onResume()
        // Developer Mode Feature
        showHideInviteSection()
    }

    private fun showHideInviteSection() {
        if (walletApplication.configuration.developerMode && showInviteSection) {
            invite.visibility = View.VISIBLE
        } else {
            invite.visibility = View.GONE
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startBuyAndSellActivity() {
        startActivity(UpholdAccountActivity.createIntent(requireContext()))
    }
}
