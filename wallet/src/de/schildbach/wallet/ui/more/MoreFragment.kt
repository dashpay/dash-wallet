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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.EditProfileActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import de.schildbach.wallet.ui.dashpay.CreateIdentityViewModel
import de.schildbach.wallet.ui.dashpay.EditProfileViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.display
import de.schildbach.wallet.ui.invite.CreateInviteViewModel
import de.schildbach.wallet.ui.main.MainViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentMoreBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.money.Dash
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.ui.components.ComposeHostFrameLayout
import org.dash.wallet.common.ui.components.ToastImageResource
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dashj.platform.sdk.platform.Names
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

        /**
         * One-shot navigation argument: show the "Transfer completed"
         * toast (Figma 1691:15460) — set by
         * [de.schildbach.wallet.ui.shielded.ShieldedBalanceActivity] after
         * a successful shielded internal transfer (AC12).
         */
        const val ARG_SHOW_TRANSFER_COMPLETED_TOAST = "show_transfer_completed_toast"

        private const val TRANSFER_TOAST_DURATION_MS = 3000L

        private val log = LoggerFactory.getLogger(MoreFragment::class.java)
    }

    private val binding by viewBinding(FragmentMoreBinding::bind)
    private var showInviteSection = false
    private var transferToastHost: ComposeHostFrameLayout? = null

    /**
     * One-shot: this screen was opened arriving from a completed shielded
     * transfer (the [ARG_SHOW_TRANSFER_COMPLETED_TOAST] nav argument). Until
     * the shielded runtime re-settles to READY the last-known balance is
     * stale, so the card shows "Syncing…" rather than that stale amount
     * (case (b) of the card-gating rule — see [mapShieldedCardDisplay]).
     * Cleared once READY is observed.
     */
    private var arrivedFromCompletedTransfer = false

    private val mainActivityViewModel: MainViewModel by activityViewModels()
    private val editProfileViewModel: EditProfileViewModel by viewModels()
    private val createInviteViewModel: CreateInviteViewModel by viewModels()
    private val createIdentityViewModel: CreateIdentityViewModel by viewModels()

    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var walletData: WalletData
    @Inject lateinit var walletApplication: WalletApplication
    @Inject lateinit var analytics: AnalyticsService
    @Inject lateinit var dashPayConfig: DashPayConfig
    @Inject lateinit var shieldedBalanceService: ShieldedBalanceService

    /**
     * Balance-card amount format, shared by the Dash and Shielded cards
     * (design 1691:15460 shows "2.00 Đ"): three decimals, rounded DOWN so a
     * card never overstates the balance; Đ stays an Inter font glyph
     * appended in the string (a trailing ImageView clips).
     */
    private val balanceCardFormat = org.dash.wallet.common.money.MoneyFormat()
        .noCode()
        .minDecimals(3)
        .optionalDecimals()
        .roundingMode(java.math.RoundingMode.DOWN)

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
            safeNavigate(MoreFragmentDirections.moreToSettings())
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
            viewLifecycleOwner.lifecycleScope.launch {
                val inviteHistory = mainActivityViewModel.getInviteHistory()
                mainActivityViewModel.logEvent(AnalyticsConstants.MoreMenu.INVITE)
                if (inviteHistory.isEmpty()) {
                    safeNavigate(MoreFragmentDirections.moreToInviteFee("more"))
                } else {
                    safeNavigate(MoreFragmentDirections.moreToInviteHistory("more"))
                }
            }
        }

        binding.updateProfileNetworkError.errorTryAgain.setOnClickListener {
            editProfileViewModel.retryBroadcastProfile()
        }

        binding.updateProfileNetworkError.cancelNetworkError.setOnClickListener { dismissProfileError() }
        binding.errorUpdatingProfile.cancel.setOnClickListener { dismissProfileError() }
        binding.editUpdateSwitcher.isVisible = false
        binding.joinDashpayContainer.setOnClickListener {
            mainActivityViewModel.logEvent(AnalyticsConstants.UsersContacts.JOIN_DASHPAY)
            startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
        }
        binding.usernameVoting.isVisible = Constants.SUPPORTS_PLATFORM
        binding.usernameVoting.setOnClickListener {
            mainActivityViewModel.logEvent(AnalyticsConstants.MoreMenu.USERNAME_VOTING)
            safeNavigate(MoreFragmentDirections.moreToUsernameVoting())
        }

        binding.requestedUsernameContainer.setOnClickListener {
            val errorMessage = createIdentityViewModel.creationException.value
            if (createIdentityViewModel.creationState.value.ordinal < IdentityCreationState.VOTING.ordinal &&
                errorMessage != null) {
                // Perform Retry
                mainActivityViewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_TRYAGAIN)
                retry(errorMessage)
            } else {
                // A username in voting opens its request-status screen
                // (CreateUsernameActivity resolves its start destination to
                // VotingRequestDetailsFragment for that state). This tap
                // must NEVER dismiss the tile — the old dual-username
                // branch flipped the creation state to DONE_AND_DISMISS,
                // which made the tile vanish with no navigation (observed
                // live).
                startActivity(Intent(requireContext(), CreateUsernameActivity::class.java))
            }
        }

        mainActivityViewModel.isBlockchainSynced.observe(viewLifecycleOwner) { isSynced ->
            binding.joinDashpayWait.isVisible = !isSynced
            binding.joinDashpayIcon.setColorFilter(
                if (isSynced) {
                    ContextCompat.getColor(requireContext(), R.color.dash_blue)
                } else {
                    ContextCompat.getColor(requireContext(), R.color.gray)
                }
            )
            binding.joinDashpayContainer.isEnabled = isSynced
        }

        mainActivityViewModel.blockchainIdentityDataDao.observeBase().observe(viewLifecycleOwner) {
            if (!it.restoring && it.creationState.ordinal > IdentityCreationState.NONE.ordinal &&
                it.creationState.ordinal < IdentityCreationState.VOTING.ordinal
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
            } else if (it.creationState == IdentityCreationState.VOTING) {
                binding.joinDashpayContainer.visibility = View.GONE
                binding.requestedUsernameContainer.visibility = View.VISIBLE
                // The pre-voting branches disable the tile; voting states
                // must re-enable it so the tap opens the request-status
                // screen even after an in-place state transition.
                binding.requestedUsernameContainer.isEnabled = true
                // A voting start that was never persisted properly arrives
                // as 0 (epoch) — rendering it produced "Results on
                // Dec 31, 1969" (observed live). Omit the date instead.
                val votingPeriod = usernameVotingEndTime(it.votingPeriodStart)?.let { endTime ->
                    val dateFormat = DateFormat.getMediumDateFormat(requireContext())
                    String.format("%s", dateFormat.format(endTime))
                }
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
                        binding.requestedUsernameSubtitleTwo.isVisible = votingPeriod != null
                        votingPeriod?.let { period ->
                            binding.requestedUsernameSubtitleTwo.text =
                                getString(R.string.requested_voting_duration, period)
                        }
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
                    UsernameRequestStatus.NONE,
                    UsernameRequestStatus.APPROVED -> {
                        // swallow to prevent crash
                    }
                    else -> error("${it.usernameRequested} is not valid")
                }
            } else if (it.creationState >= IdentityCreationState.VOTING) {
                binding.joinDashpayContainer.visibility = View.GONE
                binding.requestedUsernameContainer.visibility = View.GONE
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
        setupBalanceCards()

        if (!Constants.SUPPORTS_PLATFORM) {
            binding.usernameVoting.isVisible = false
        }

        // One-shot: arriving from a completed shielded internal transfer
        // (AC12). The argument is consumed so returning to this screen
        // never re-shows the toast. It also marks the shielded balance stale
        // (the transfer just changed it) so the card shows "Syncing…" instead
        // of the now-stale last-known amount until the runtime re-settles.
        if (arguments?.getBoolean(ARG_SHOW_TRANSFER_COMPLETED_TOAST) == true) {
            arguments?.remove(ARG_SHOW_TRANSFER_COMPLETED_TOAST)
            arrivedFromCompletedTransfer = true
            showTransferCompletedToast()
        }
    }

    /**
     * "Transfer completed" toast (Figma 1691:15460) using the
     * design-system Compose [org.dash.wallet.common.ui.components.Toast]
     * hosted over the activity content (the `MainActivityExt.showToast`
     * pattern) — this XML screen has no compose root of its own, and the
     * design-system toast beats a themed Snackbar for design parity.
     * Auto-dismisses; torn down with the view either way.
     */
    private fun showTransferCompletedToast() {
        val host = ComposeHostFrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = resources.getDimensionPixelSize(R.dimen.bottom_nav_bar_height)
            }
        }
        requireActivity().findViewById<ViewGroup>(android.R.id.content).addView(host)
        transferToastHost = host
        host.setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) {
                LaunchedEffect(Unit) {
                    delay(TRANSFER_TOAST_DURATION_MS)
                    visible = false
                }
                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                    org.dash.wallet.common.ui.components.Toast(
                        text = stringResource(R.string.shielded_transfer_completed),
                        imageResource = ToastImageResource.Success.resourceId,
                        onActionClick = { visible = false }
                    )
                }
            }
        }
    }

    private fun removeTransferCompletedToast() {
        transferToastHost?.let { host ->
            (host.parent as? ViewGroup)?.removeView(host)
        }
        transferToastHost = null
    }

    override fun onDestroyView() {
        removeTransferCompletedToast()
        super.onDestroyView()
    }

    /**
     * Dash Wallet / Shielded balance cards at the top (Figma 1691:15460),
     * flag-gated: with `SUPPORTS_PLATFORM` or `USE_KOTLIN_SDK_SHIELDED` off
     * the container stays GONE and the screen is unchanged.
     */
    private fun setupBalanceCards() {
        viewLifecycleOwner.lifecycleScope.launch {
            val shieldedEnabled = Constants.SUPPORTS_PLATFORM &&
                dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true

            if (!shieldedEnabled) {
                binding.balanceCardsContainer.isVisible = false
                return@launch
            }

            binding.balanceCardsContainer.isVisible = true

            walletData.observeTotalBalance().observe(viewLifecycleOwner) { balance ->
                binding.walletBalanceCardAmount.text =
                    "${balanceCardFormat.format(org.dash.wallet.common.money.Dash(balance.value))} Đ"
            }

            // The shielded card must never flash a bare "0" for a funded wallet
            // while the pool re-syncs (observeShieldedBalance emits Dash.ZERO
            // until ready AND during a re-scan), and it must show a balance the
            // app already knows the moment the screen opens — a "Syncing…"
            // placeholder appears ONLY when there is genuinely nothing better
            // to show. Render from the latest of: the live balance, the sync
            // status, the identity presence, the persisted last-known (cached)
            // balance, and whether a local spend just made that cache stale
            // (see [mapShieldedCardDisplay]). Display-only: the trust/gating
            // semantics of shieldedSyncStatus elsewhere are unchanged.
            var latestBalance = Dash.ZERO
            var latestStatus = shieldedBalanceService.shieldedSyncStatus.value
            // Conservative until the identity store emits: assume a context
            // exists so a migrated wallet never flashes "0" before the first
            // identity emission.
            var latestHasShieldedContext = true
            // Rendered instantly on open so a background re-scan on relaunch
            // shows the known balance, not a spinner. Null until the runtime
            // has ever persisted a trustworthy (READY) balance.
            var latestCachedBalance = shieldedBalanceService.lastKnownShieldedBalance()
            // A shielded spend from this app (service flag) OR arrival from a
            // completed shielded transfer (the nav one-shot, captured in
            // [arrivedFromCompletedTransfer]) both mean the cached balance is
            // known-stale until the runtime re-settles → show "Syncing…".
            var latestMaybeStale =
                shieldedBalanceService.shieldedBalanceMaybeStale.value || arrivedFromCompletedTransfer

            fun render() = renderShieldedCardAmount(
                latestStatus, latestBalance, latestHasShieldedContext, latestCachedBalance, latestMaybeStale
            )

            shieldedBalanceService.observeShieldedBalance().observe(viewLifecycleOwner) { balance ->
                latestBalance = balance
                render()
            }
            shieldedBalanceService.shieldedSyncStatus.observe(viewLifecycleOwner) { status ->
                latestStatus = status
                // Once the runtime is fully synced the live balance is fresh
                // and authoritative — any nav-arg staleness one-shot is spent.
                if (status == ShieldedSyncStatus.READY) {
                    arrivedFromCompletedTransfer = false
                    latestMaybeStale = shieldedBalanceService.shieldedBalanceMaybeStale.value
                }
                render()
            }
            shieldedBalanceService.shieldedBalanceMaybeStale.observe(viewLifecycleOwner) { stale ->
                latestMaybeStale = stale || arrivedFromCompletedTransfer
                render()
            }
            mainActivityViewModel.blockchainIdentityDataDao.observeBase().observe(viewLifecycleOwner) { identity ->
                latestHasShieldedContext = hasShieldedContext(identity)
                render()
            }
            render()

            // Bring the shielded runtime up so the balance loads and the sync
            // status advances past NOT_READY, then kick an immediate sync
            // pass so the card shows fresh notes on screen entry instead of
            // waiting out the ~60s background tick (Brian's request after
            // the first live shield). Idempotent + single-flight; the
            // background sync/poll runs in the app scope, not this one.
            launch {
                runCatching {
                    if (shieldedBalanceService.ensureShieldedReady()) {
                        shieldedBalanceService.syncNow()
                    }
                }
            }
        }
    }

    /**
     * Render the "Shielded" card's amount. The amount arms of
     * [mapShieldedCardDisplay] show a balance in DASH, formatted exactly like
     * the sibling Dash Wallet card ("2.00 Đ" — two decimals, rounded DOWN so
     * the card never overstates, Đ as an Inter glyph in the string); the
     * credits glyph stays hidden (the card stopped showing credits per Brian,
     * 2026-07-12). [ShieldedCardDisplay.LIVE_AMOUNT] shows the live [balance],
     * [ShieldedCardDisplay.CACHED_AMOUNT] the persisted last-known
     * [cachedBalance] (so a relaunch re-scan shows the known balance instead
     * of a spinner), and the SYNCING arm a subtle "Syncing…" placeholder, so
     * a still-syncing funded wallet is never misread as empty — while a fresh
     * wallet with nothing shielded to sync shows its honest zero (see
     * [mapShieldedCardDisplay]).
     */
    private fun renderShieldedCardAmount(
        status: ShieldedSyncStatus,
        balance: Dash,
        hasShieldedContext: Boolean,
        cachedBalance: Dash?,
        balanceMaybeStale: Boolean
    ) {
        val amount = binding.shieldedBalanceCardAmount
        binding.shieldedBalanceCardSymbol.isVisible = false
        val shown = when (mapShieldedCardDisplay(status, hasShieldedContext, cachedBalance, balanceMaybeStale)) {
            ShieldedCardDisplay.LIVE_AMOUNT -> balance
            ShieldedCardDisplay.CACHED_AMOUNT -> cachedBalance ?: balance
            ShieldedCardDisplay.SYNCING -> null
        }
        if (shown != null) {
            // Mirror the sibling Dash card's EXACT size (both are Subtitle2;
            // reading it at runtime keeps them identical even if the style
            // changes) — the amount arm must undo the smaller Syncing size.
            amount.setTextSize(TypedValue.COMPLEX_UNIT_PX, binding.walletBalanceCardAmount.textSize)
            amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.content_primary))
            amount.text = "${balanceCardFormat.format(shown)} Đ"
        } else {
            amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            amount.setTextColor(ContextCompat.getColor(requireContext(), R.color.content_secondary))
            amount.text = getString(R.string.shielded_balance_syncing)
        }
    }

    private fun retry(errorMessage: String) {
        val needsNewName = errorMessage.contains("Document transitions with duplicate unique properties") ||
                errorMessage.contains("DuplicateUniqueIndexError") ||
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

        createInviteViewModel.blockchainIdentity.observe(viewLifecycleOwner) { _ ->
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

        mainActivityViewModel.isAbleToCreateIdentity.observe(viewLifecycleOwner) {
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
        val shouldShowProfileSection = createIdentityViewModel.creationState.value.ordinal >= IdentityCreationState.DONE.ordinal ||
                createIdentityViewModel.blockchainIdentity.value?.showSecondaryUsername == true
        if (shouldShowProfileSection) {
            binding.editUpdateSwitcher.visibility = View.VISIBLE
            binding.editUpdateSwitcher.displayedChild = PROFILE_VIEW
            // For a dual (contested + instant) username still in voting, the
            // freshly-created profile carries the CONTESTED primary name while
            // only the INSTANT secondary name is actually owned — so the
            // identity's activeUsername is authoritative and matches what a
            // profile refresh persists on re-entry (Bug 2). Single-username
            // wallets fall through to the profile's own name, unchanged.
            val identity = createIdentityViewModel.blockchainIdentity.value
            val displayUsername = profileDisplayUsername(
                profile.username,
                identity?.activeUsername,
                identity?.showSecondaryUsername == true,
                Names::normalizeString
            )
            if (profile.displayName.isNotEmpty()) {
                binding.username1.text = profile.displayName
                binding.username2.text = displayUsername
            } else {
                binding.username1.text = displayUsername
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

/**
 * What the More-screen "Shielded" balance card renders (see
 * [mapShieldedCardDisplay]): the live balance, the persisted last-known
 * (cached) balance, or the "Syncing…" placeholder.
 */
internal enum class ShieldedCardDisplay { LIVE_AMOUNT, CACHED_AMOUNT, SYNCING }

/**
 * Pure, host-testable display decision for the More-screen "Shielded" card.
 * DISPLAY-ONLY — the trust rule everywhere else (any non-READY status means
 * "balance not yet trustworthy") is unchanged.
 *
 * The card must show "Syncing…" ONLY when there is genuinely nothing better
 * to show. In every other case it prefers a real amount — the live balance
 * when trustworthy, otherwise the persisted last-known balance — so a
 * background re-scan on relaunch never hides a balance the app already knows.
 *
 * - READY → the LIVE amount: the balance is trustworthy.
 * - [balanceMaybeStale] (a shielded spend from this app just happened and the
 *   runtime has not re-settled) → "Syncing…": the last-known amount is
 *   known-stale, so showing it would be wrong. This is the only case that
 *   overrides an available cached balance.
 * - a persisted [cachedBalance] exists → the CACHED amount, no placeholder:
 *   the common relaunch case (durable notes, runtime re-binding in the
 *   background), and any not-yet-READY rebind where a known balance beats a
 *   spinner.
 * - NO cache and NO shielded context → the LIVE amount (which is `Dash.ZERO`,
 *   the flow's placeholder — here also the honest balance): on a fresh wallet
 *   with no platform identity bound or being created,
 *   [de.schildbach.wallet.service.platform.sdk.SdkWalletBinder] never binds
 *   the SDK wallet, `ensureShieldedReady` can never succeed, and the status
 *   stays NOT_READY forever — a permanent "Syncing…" would be a lie.
 * - Otherwise (no cache, a wallet that DOES have a shielded context, bring-up
 *   still pending) → "Syncing…": the first balance fetch on a funded/migrated
 *   wallet before the startup pass has completed — never flash a bare zero.
 */
internal fun mapShieldedCardDisplay(
    status: ShieldedSyncStatus,
    hasShieldedContext: Boolean,
    cachedBalance: Dash?,
    balanceMaybeStale: Boolean
): ShieldedCardDisplay = when {
    status == ShieldedSyncStatus.READY -> ShieldedCardDisplay.LIVE_AMOUNT
    balanceMaybeStale -> ShieldedCardDisplay.SYNCING
    cachedBalance != null -> ShieldedCardDisplay.CACHED_AMOUNT
    !hasShieldedContext -> ShieldedCardDisplay.LIVE_AMOUNT
    else -> ShieldedCardDisplay.SYNCING
}

/**
 * The username to show under the More-screen avatar — pure, host-testable.
 *
 * A dual (contested + instant) username sits in voting with only the
 * INSTANT/secondary name confirmed and owned; the CONTESTED primary name is
 * not yet the user's. Right after creation the local profile is seeded with
 * the primary (contested) name, but a profile refresh on re-entry persists
 * the owned instant name — so the two renders disagreed (Bug 2). When the
 * identity reports [BlockchainIdentityData.showSecondaryUsername] its
 * [BlockchainIdentityData.activeUsername] (the confirmed secondary name) is
 * authoritative and shown immediately. Otherwise the profile's own username
 * is used, so single-username wallets are unchanged.
 */
internal fun profileDisplayUsername(
    profileUsername: String,
    identityActiveUsername: String?,
    showSecondaryUsername: Boolean,
    normalize: (String) -> String
): String = when {
    !showSecondaryUsername || identityActiveUsername.isNullOrEmpty() -> profileUsername
    // The identity's activeUsername is the DPNS-NORMALIZED secondary label
    // (homoglyphs folded: o→0, l/i→1 — e.g. "c0ntested11-2"). Once the
    // profile refresh lands, profileUsername is that same name in DISPLAY
    // form (the domain document's .label, "contested11-2"); prefer it so the
    // user sees what they typed. Detect that by normalizing the profile name
    // and matching it to the active label.
    normalize(profileUsername) == identityActiveUsername -> profileUsername
    // Profile not yet refreshed to the secondary (first render still shows
    // the primary) — the normalized active label is the only secondary value
    // available until the refresh; better than showing the primary name.
    else -> identityActiveUsername
}

/**
 * When the voting period for a requested username ends, in epoch millis —
 * or null when the persisted voting start is missing or non-positive
 * (0/-1 placeholders), in which case NO date must be rendered. Guards the
 * More-screen tile against the "Results on Dec 31, 1969" epoch render
 * observed live when the restore path persisted a 0 voting start.
 */
internal fun usernameVotingEndTime(votingPeriodStart: Long?): Long? =
    votingPeriodStart?.takeIf { it > 0 }?.let { it + UsernameRequest.VOTING_PERIOD_MILLIS }

/**
 * Whether this wallet has (or is creating/restoring) a platform identity —
 * the exact eligibility gate `SdkWalletBinder.bindLocked` uses to decide
 * whether the SDK wallet gets bound at all. `false` means the shielded
 * runtime can never come up on this wallet, so there is provably nothing
 * shielded-related to sync.
 */
internal fun hasShieldedContext(identity: BlockchainIdentityBaseData): Boolean =
    identity.creationState != IdentityCreationState.NONE || identity.userId != null

