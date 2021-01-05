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
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.BottomNavFragment
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.showBlockchainSyncingMessage
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.android.synthetic.main.fragment_updating_profile.*
import kotlinx.android.synthetic.main.fragment_update_profile_error.*
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity

class MoreFragment : BottomNavFragment(R.layout.activity_more) {

    override val navigationItemId = R.id.more

    private var blockchainState: BlockchainState? = null
    private lateinit var editProfileViewModel: EditProfileViewModel
    private lateinit var mainActivityViewModel: MainActivityViewModel

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
        error_try_again.setOnClickListener {
            editProfileViewModel.retryBroadcastProfile()
        }
        cancel.setOnClickListener {
            if (edit_update_switcher.displayedChild == 2) {
                edit_update_switcher.displayedChild = 0 // reset to previous profile
                editProfileViewModel.clearLastAttemptedProfile()
            }

        }
        edit_update_switcher.isVisible = false
        join_dashpay_btn.setOnClickListener {
            mainActivityViewModel.goBackAndStartActivityEvent.postValue(CreateUsernameActivity::class.java)
        }
        initViewModel()
    }

    private fun initViewModel() {
        mainActivityViewModel = ViewModelProvider(requireActivity()).get(MainActivityViewModel::class.java)
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
                (requireActivity() as InteractionAwareActivity).imitateUserInteraction()
                when (state.status) {
                    Status.SUCCESS -> {
                        edit_update_switcher.apply {
                            displayedChild = 0
                        }
                    }
                    Status.ERROR -> {
                        edit_update_switcher.apply {
                            if (displayedChild != 2) {
                                displayedChild = 2
                                error_code_text.text = getString(R.string.error_updating_profile_code, state.message)
                            }
                        }
                    }
                    Status.LOADING -> {
                        edit_update_switcher.apply {
                            if (displayedChild == 0 || displayedChild == 2) {
                                //showNext()
                                displayedChild = 1
                                update_profile_status_icon.setImageResource(R.drawable.identity_processing)
                                (update_profile_status_icon.drawable as AnimationDrawable).start()
                            }
                        }
                    }
                    Status.CANCELED -> {
                        edit_update_switcher.apply {
                            if (displayedChild != 0) {
                                displayedChild = 0
                            }
                        }
                    }
                }
            }
        })

        mainActivityViewModel.isAbleToCreateIdentityLiveData.observe(viewLifecycleOwner, {
            join_dashpay_container.visibility = if (it) {
                View.VISIBLE
            } else {
                View.GONE
            }
        })
    }

    private fun showProfileSection(profile: DashPayProfile) {
        edit_update_switcher.visibility = View.VISIBLE
        edit_update_switcher.displayedChild = 0
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
