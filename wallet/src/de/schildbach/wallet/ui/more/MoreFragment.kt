/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.more

import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.EditProfileActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.SettingsActivity
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.invite.CreateInviteViewModel
import de.schildbach.wallet.ui.invite.InviteFriendActivity
import de.schildbach.wallet.ui.invite.InvitesHistoryActivity
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMoreBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class MoreFragment : Fragment(R.layout.fragment_more) {
    companion object {
        const val PROFILE_VIEW = 0
        const val UPDATING_PROFILE_VIEW = 1
        const val UPDATE_PROFILE_ERROR_VIEW = 2
        const val UPDATE_PROFILE_NETWORK_ERROR_VIEW = 3

        private val log = LoggerFactory.getLogger(MoreFragment::class.java)
    }

    private val binding by viewBinding(FragmentMoreBinding::bind)
    private var showInviteSection = false

    private val mainActivityViewModel: MainViewModel by activityViewModels()
    private val editProfileViewModel: EditProfileViewModel by viewModels()
    private val createInviteViewModel: CreateInviteViewModel by viewModels()

    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var walletData: WalletDataProvider
    @Inject lateinit var analytics: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        binding.appBar.toolbar.title = getString(R.string.more_title)
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.buyAndSell.setOnClickListener {
            startBuyAndSellActivity()
        }
        binding.explore.setOnClickListener {
            findNavController().navigate(
                R.id.exploreFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .setPopUpTo(R.id.moreFragment, true)
                    .build()
            )
        }
        binding.security.setOnClickListener {
            safeNavigate(MoreFragmentDirections.moreToSecurity())
        }
        binding.settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.tools.setOnClickListener {
            //startActivity(Intent(requireContext(), ToolsActivity::class.java))
            findNavController().navigate(
                R.id.toolsFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
        binding.contactSupport.setOnClickListener {
            val alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                requireActivity(),
                packageInfoProvider,
                configuration,
                walletData.wallet
            ).buildAlertDialog()
            (requireActivity() as LockScreenActivity).alertDialog = alertDialog
            alertDialog.show()
        }

        binding.invite.visibility = View.GONE
        binding.invite.setOnClickListener {
            lifecycleScope.launch {
                val inviteHistory = mainActivityViewModel.getInviteHistory()

                if (inviteHistory.isEmpty()) {
                    InviteFriendActivity.startOrError(requireActivity())
                } else {
                    val intent = InvitesHistoryActivity.createIntent(requireContext()).apply {
                        putExtra(AnalyticsConstants.CALLING_ACTIVITY, "more")
                    }
                    startActivity(intent)
                }
            }
        }

        binding.updateProfileNetworkError.errorTryAgain.setOnClickListener {
            editProfileViewModel.retryBroadcastProfile()
        }

        binding.updateProfileNetworkError.cancelNetworkError.setOnClickListener { dismissProfileError() }
        binding.errorUpdatingProfile.cancel.setOnClickListener { dismissProfileError() }
        binding.editUpdateSwitcher.isVisible = false
        binding.joinDashpayBtn.setOnClickListener {
            startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
        }
        binding.usernameVoting.setOnClickListener {
            safeNavigate(MoreFragmentDirections.moreToUsernameVoting())
        }

        initViewModel()

        lifecycleScope.launchWhenResumed {
            mainActivityViewModel.getRequestedUsername().also { username ->
                if (username.isNotEmpty()) {
                    binding.joinDashpayContainer.visibility = View.GONE
                    binding.requestedUsernameContainer.visibility = View.VISIBLE
                    binding.requestedUsernameTitle.text = username
                } else {
                    binding.joinDashpayContainer.visibility = View.VISIBLE
                    binding.requestedUsernameContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun startBuyAndSellActivity() {
        analytics.logEvent(AnalyticsConstants.MoreMenu.BUY_SELL_MORE, mapOf())
        safeNavigate(MoreFragmentDirections.moreToBuySell())
    }

    private fun dismissProfileError() {
        val allowedScreensToSwitch = listOf(
            UPDATE_PROFILE_ERROR_VIEW,
            UPDATE_PROFILE_NETWORK_ERROR_VIEW
        )
        if (binding.editUpdateSwitcher.displayedChild in allowedScreensToSwitch) {
            binding.editUpdateSwitcher.displayedChild =
                PROFILE_VIEW // reset to previous profile
            editProfileViewModel.clearLastAttemptedProfile()
        }
    }

    private fun initViewModel() {
        // observe our profile
        editProfileViewModel.dashPayProfile.observe(viewLifecycleOwner) { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileSection(dashPayProfile)
            }
        }

        // observe our profile
        editProfileViewModel.dashPayProfile.observe(viewLifecycleOwner) { dashPayProfile ->
            if (dashPayProfile != null) {
                showProfileSection(dashPayProfile)
            }
        }
        // track the status of broadcast changes to our profile
        editProfileViewModel.updateProfileRequestState.observe(viewLifecycleOwner) { state ->
            if (state != null) {
                (requireActivity() as LockScreenActivity).imitateUserInteraction()
                when (state.status) {
                    Status.SUCCESS -> {
                        binding.editUpdateSwitcher.apply {
                            displayedChild = PROFILE_VIEW
                        }
                    }
                    Status.ERROR -> {
                        binding.editUpdateSwitcher.apply {
                            if (mainActivityViewModel.isNetworkUnavailable.value == true) {
                                if (displayedChild != UPDATE_PROFILE_NETWORK_ERROR_VIEW) {
                                    displayedChild = UPDATE_PROFILE_NETWORK_ERROR_VIEW
                                }
                            } else if (displayedChild != UPDATE_PROFILE_ERROR_VIEW) {
                                displayedChild = UPDATE_PROFILE_ERROR_VIEW
                                binding.updateProfileNetworkError.errorCodeText.text = getString(R.string.error_updating_profile_code, state.message)
                            }
                        }
                    }
                    Status.LOADING -> {
                        binding.editUpdateSwitcher.apply {
                            val allowedScreensToSwitch = listOf(
                                PROFILE_VIEW, UPDATE_PROFILE_ERROR_VIEW,
                                UPDATE_PROFILE_NETWORK_ERROR_VIEW
                            )
                            if (displayedChild in allowedScreensToSwitch) {
                                //showNext()
                                displayedChild = UPDATING_PROFILE_VIEW
                                binding.updateProfile.updateProfileStatusIcon.setImageResource(R.drawable.identity_processing)
                                (binding.updateProfile.updateProfileStatusIcon.drawable as AnimationDrawable).start()
                            }
                        }
                    }
                    Status.CANCELED -> {
                        binding.editUpdateSwitcher.apply {
                            if (displayedChild != PROFILE_VIEW) {
                                displayedChild = PROFILE_VIEW
                            }
                        }
                        log.info("update profile operation cancelled")
                    }
                }
            }
        }

        mainActivityViewModel.isAbleToCreateIdentityLiveData.observe(viewLifecycleOwner) {
            binding.joinDashpayContainer.isVisible = it
        }

        createInviteViewModel.isAbleToPerformInviteAction.observe(viewLifecycleOwner) {
            showInviteSection(it)
        }
    }

    private fun showInviteSection(showInviteSection: Boolean) {
        this.showInviteSection = showInviteSection

        //show the invite section only after the profile section is visible
        //to avoid flickering
        if (binding.editUpdateSwitcher.isVisible) {
            binding.invite.isVisible = showInviteSection
        }
    }

    private fun showProfileSection(profile: DashPayProfile) {
        binding.editUpdateSwitcher.visibility = View.VISIBLE
        binding.editUpdateSwitcher.displayedChild = PROFILE_VIEW
        if (profile.displayName.isNotEmpty()) {
            binding.username1.text = profile.displayName
            binding.username2.text = profile.username
        } else {
            binding.username1.text = profile.username
            binding.username2.visibility = View.GONE
        }

        ProfilePictureDisplay.display(binding.dashpayUserAvatar, profile)

        binding.editProfile.setOnClickListener {
            editProfileViewModel.logEvent(AnalyticsConstants.UsersContacts.PROFILE_EDIT_MORE)
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }
        //if the invite section is not visible, show/hide it
        if (!binding.invite.isVisible) {
            binding.invite.isVisible = showInviteSection
        }
    }

    override fun onResume() {
        super.onResume()
        // Developer Mode Feature
        binding.invite.isVisible = showInviteSection
    }
}
