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
import android.text.format.DateFormat
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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.EditProfileActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.SettingsActivity
import de.schildbach.wallet.ui.coinjoin.CoinJoinLevelViewModel
import de.schildbach.wallet.ui.dashpay.CreateIdentityViewModel
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.invite.CreateInviteViewModel
import de.schildbach.wallet.ui.invite.InviteFriendActivity
import de.schildbach.wallet.ui.invite.InvitesHistoryActivity
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMoreBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dashj.platform.dashpay.UsernameRequestStatus
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
    private val createIdentityViewModel: CreateIdentityViewModel by viewModels()
    private val coinJoinViewModel: CoinJoinLevelViewModel by viewModels()

    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var walletData: WalletDataProvider
    @Inject lateinit var walletApplication: WalletApplication
    @Inject lateinit var analytics: AnalyticsService

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()

        binding.appBar.toolbar.title = getString(R.string.more_title)
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.buyAndSell.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.BUY_SELL, mapOf())
            safeNavigate(MoreFragmentDirections.moreToBuySell())
        }
        binding.explore.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.EXPLORE, mapOf())
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
            analytics.logEvent(AnalyticsConstants.MoreMenu.SECURITY, mapOf())
            safeNavigate(MoreFragmentDirections.moreToSecurity())
        }
        binding.settings.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.SETTINGS, mapOf())
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        binding.tools.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.TOOLS, mapOf())
            findNavController().navigate(
                R.id.toolsFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
        binding.contactSupport.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.MoreMenu.CONTACT_SUPPORT, mapOf())
            ContactSupportDialogFragment.newInstance(
                getString(R.string.report_issue_dialog_title_issue),
                getString(R.string.report_issue_dialog_message_issue)
            ).show(requireActivity())
        }

        binding.invite.visibility = View.GONE
        binding.invite.setOnClickListener {
            lifecycleScope.launch {
                val inviteHistory = mainActivityViewModel.getInviteHistory()
                mainActivityViewModel.logEvent(AnalyticsConstants.MoreMenu.INVITE)
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
            lifecycleScope.launch {
                val shouldShowMixDashDialog = withContext(Dispatchers.IO) { createIdentityViewModel.shouldShowMixDash() }
                mainActivityViewModel.logEvent(AnalyticsConstants.UsersContacts.JOIN_DASHPAY)
                if (coinJoinViewModel.isMixing || !shouldShowMixDashDialog) {
                    startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
                } else {
                    MixDashFirstDialogFragment().show(requireActivity()) {
                        startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
                    }
                }
            }
        }
        binding.usernameVoting.isVisible = Constants.SUPPORTS_PLATFORM
        binding.usernameVoting.setOnClickListener {
            mainActivityViewModel.logEvent(AnalyticsConstants.MoreMenu.USERNAME_VOTING)
            safeNavigate(MoreFragmentDirections.moreToUsernameVoting())
        }

        binding.requestedUsernameContainer.setOnClickListener {
            val errorMessage = createIdentityViewModel.creationException.value
            if (createIdentityViewModel.creationState.value.ordinal < BlockchainIdentityData.CreationState.VOTING.ordinal &&
                errorMessage != null) {
                // Perform Retry
                mainActivityViewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_TRYAGAIN)
                retry(errorMessage)
            } else {
                startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
            }
        }

        mainActivityViewModel.blockchainIdentityDataDao.observeBase().observe(viewLifecycleOwner) {
            if (!it.restoring && it.creationState.ordinal > BlockchainIdentityData.CreationState.NONE.ordinal &&
                it.creationState.ordinal < BlockchainIdentityData.CreationState.VOTING.ordinal
            ) {
                val username = it.username

                binding.joinDashpayContainer.visibility = View.GONE
                binding.requestedUsernameContainer.visibility = View.VISIBLE
                if (it.creationError) {
                    binding.requestedUsernameTitle.text = getString(R.string.requesting_your_username_error_title)
                    binding.requestedUsernameSubtitle.text = getString(R.string.requesting_your_username_error_message, username)
                    binding.requestedUsernameSubtitleTwo.isVisible = false
                    binding.retryRequestButton.isVisible = true
                    binding.retryRequestButton.text = getString(R.string.retry)
                    binding.requestedUsernameIcon.setImageResource(R.drawable.ic_join_dashpay_red)
                } else {
                    if (it.usernameRequested == UsernameRequestStatus.NONE) {
                        binding.requestedUsernameTitle.text = getString(R.string.requesting_your_username_title)
                        binding.requestedUsernameSubtitle.text = getString(R.string.creating_your_username_message, username)
                        binding.requestedUsernameSubtitleTwo.isVisible = false
                        binding.retryRequestButton.isVisible = false
                        binding.requestedUsernameArrow.isVisible = false
                        binding.requestedUsernameContainer.isEnabled = false
                    } else {
                        binding.requestedUsernameTitle.text = getString(R.string.requesting_your_username_title)
                        binding.requestedUsernameSubtitle.text = getString(R.string.requesting_your_username_message, username)
                        binding.retryRequestButton.isVisible = false
                        binding.requestedUsernameArrow.isVisible = false
                        binding.requestedUsernameContainer.isEnabled = false
                    }
                }
            } else if (it.creationState == BlockchainIdentityData.CreationState.VOTING) {
                binding.joinDashpayContainer.visibility = View.GONE
                binding.requestedUsernameContainer.visibility = View.VISIBLE
                val votingPeriod = it.votingPeriodStart?.let { startTime ->
                    val endTime = startTime + UsernameRequest.VOTING_PERIOD_MILLIS
                    val dateFormat = DateFormat.getMediumDateFormat(requireContext())
                    String.format("%s", dateFormat.format(endTime))
                } ?: "Voting Period not found"
                when (it.usernameRequested) {
                    UsernameRequestStatus.SUBMITTING,
                    UsernameRequestStatus.SUBMITTED -> {
                        binding.requestedUsernameTitle.text = mainActivityViewModel.getRequestedUsername()
                        binding.requestedUsernameSubtitleTwo.isVisible = false
                        binding.retryRequestButton.isVisible = false
                        binding.requestedUsernameIcon.setImageResource(R.drawable.ic_join_dashpay)
                        binding.requestedUsernameArrow.isVisible = true
                    }
                    UsernameRequestStatus.VOTING -> {
                        binding.requestedUsernameTitle.text = mainActivityViewModel.getRequestedUsername()
                        binding.requestedUsernameSubtitleTwo.isVisible = true
                        binding.requestedUsernameSubtitleTwo.text =
                            getString(R.string.requested_voting_duration, votingPeriod)
                        binding.retryRequestButton.isVisible = false
                        binding.requestedUsernameIcon.setImageResource(R.drawable.ic_join_dashpay)
                        binding.requestedUsernameArrow.isVisible = true
                    }
                    UsernameRequestStatus.LOCKED -> {
                        binding.requestedUsernameTitle.text = getString(R.string.request_username_blocked)
                        binding.requestedUsernameSubtitle.text =
                            getString(R.string.request_username_blocked_message, mainActivityViewModel.getRequestedUsername())
                        binding.requestedUsernameSubtitleTwo.isVisible = false
                        binding.requestedUsernameSubtitle.maxLines = 4
                        binding.retryRequestButton.isVisible = false
                        binding.retryRequestButton.text = getString(R.string.try_again)
                        binding.requestedUsernameIcon.setImageResource(R.drawable.ic_join_dashpay_red)
                        binding.requestedUsernameArrow.isVisible = false
                    }
                    UsernameRequestStatus.LOST_VOTE -> {
                        binding.requestedUsernameTitle.text = getString(R.string.request_username_lost_vote)
                        binding.requestedUsernameSubtitle.text =
                            getString(R.string.request_username_lost_vote_message, mainActivityViewModel.getRequestedUsername())
                        binding.requestedUsernameSubtitle.maxLines = 4
                        binding.requestedUsernameSubtitleTwo.isVisible = false
                        binding.retryRequestButton.isVisible = false
                        binding.retryRequestButton.text = getString(R.string.try_again)
                        binding.requestedUsernameIcon.setImageResource(R.drawable.ic_join_dashpay_red)
                        binding.requestedUsernameArrow.isVisible = false
                    }
                    else -> error("${it.usernameRequested} is not valid")
                }
            } else {
                binding.joinDashpayContainer.visibility = View.VISIBLE
                binding.requestedUsernameContainer.visibility = View.GONE
            }
        }

        binding.retryRequestButton.setOnClickListener {
            mainActivityViewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_TRYAGAIN)
            val errorMessage = createIdentityViewModel.creationException.value ?: ""
            retry(errorMessage)
        }

        initViewModel()

        if (!Constants.SUPPORTS_PLATFORM) {
            binding.usernameVoting.isVisible = false
        }
    }

    private fun retry(errorMessage: String) {
        val needsNewName = errorMessage.contains("Document transitions with duplicate unique properties") ||
                errorMessage.contains("Document Contest for vote_poll ContestedDocumentResourceVotePoll") ||
                errorMessage.contains(Regex("does not have .* as a contender")) ||
                errorMessage.contains("missing domain document for ")
        if (!needsNewName) {
            createIdentityViewModel.retryCreateIdentity()
        } else {
            startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
        }
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
        createIdentityViewModel.creationState.observe(viewLifecycleOwner) { _ ->
            editProfileViewModel.dashPayProfile.value?.let { dashPayProfile ->
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
            binding.dashpayContainer.isVisible = it
        }

        createInviteViewModel.isAbleToPerformInviteAction.observe(viewLifecycleOwner) {
            showInviteSection(it)
        }
    }


    private fun showInviteSection(showInviteSection: Boolean) {
        this.showInviteSection = showInviteSection

        // show the invite section only after the profile section is visible
        // to avoid flickering
        if (binding.editUpdateSwitcher.isVisible) {
            //TODO: remove && Constants.SUPPORTS_INVITES when INVITES are supported
            binding.invite.isVisible = showInviteSection && Constants.SUPPORTS_INVITES
        }
    }

    private fun showProfileSection(profile: DashPayProfile) {
        if (createIdentityViewModel.creationState.value.ordinal >= BlockchainIdentityData.CreationState.DONE.ordinal) {
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
            // if the invite section is not visible, show/hide it
            if (!binding.invite.isVisible) {
                //TODO: remove && Constants.SUPPORTS_INVITES when INVITES are supported
                binding.invite.isVisible = showInviteSection && Constants.SUPPORTS_INVITES
            }
        } else {
            binding.editUpdateSwitcher.isVisible = false
            binding.invite.isVisible = Constants.SUPPORTS_INVITES
        }
    }

    override fun onResume() {
        super.onResume()
        //TODO: remove && Constants.SUPPORTS_INVITES when INVITES are supported
        binding.invite.isVisible = showInviteSection && Constants.SUPPORTS_INVITES
    }
}
