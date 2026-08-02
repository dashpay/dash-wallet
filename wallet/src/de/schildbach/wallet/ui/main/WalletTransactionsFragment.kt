/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.main

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.HistoryHeaderAdapter
import de.schildbach.wallet.ui.dashpay.IdentityCreationStatusHolder
import de.schildbach.wallet.ui.dashpay.RetryStatusHint
import de.schildbach.wallet.ui.invite.InviteHandler
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.InviteHandlerViewModel
import de.schildbach.wallet.ui.registerLockScreenDeactivated
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.transactions.TransactionGroupDetailsFragment
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet.ui.unregisterLockScreenDeactivated
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.WalletTransactionsFragmentBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.slf4j.LoggerFactory
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsDialog
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardOrderDetailsDialog
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardViewModel
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash
import de.schildbach.wallet.service.L1SyncUiStatus
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class WalletTransactionsFragment : Fragment(R.layout.wallet_transactions_fragment) {
    companion object {
        private const val HEADER_ITEM_TAG = "header"
        private val log = LoggerFactory.getLogger(WalletTransactionsFragment::class.java)
    }

    private var firstPageLoadStartTime: Long = 0L
    private var onViewCreatedTime: Long = 0L
    private var pendingManualRefresh: Boolean = false

    /**
     * One "still syncing" invite notice per launch. The provisional
     * NOT_SYNCED verdict can be re-reached on every sync-state emission,
     * and the notice is informational — repeating it would be a nag.
     */
    private var notSyncedInviteNoticeShown = false

    private val viewModel by activityViewModels<MainViewModel>()
    private val giftCardViewModel by activityViewModels<GiftCardViewModel>()
    private val binding by viewBinding(WalletTransactionsFragmentBinding::bind)
    private val inviteHandlerViewModel by activityViewModels<InviteHandlerViewModel>()

    val isHistoryEmpty: Boolean
        get() = (binding.walletTransactionsList.adapter?.itemCount ?: 0) == 0

    private lateinit var header: HistoryHeaderAdapter
    @Inject
    lateinit var identityRepository: IdentityRepository

    @Inject
    lateinit var identityCreationStatus: IdentityCreationStatusHolder

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreatedTime = System.currentTimeMillis()
        log.info("STARTUP WalletTransactionsFragment.onViewCreated at {}", onViewCreatedTime)

        val clickHandler = { rowView: HistoryRowView, _: Int, isProfileClick: Boolean ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (rowView is TransactionRowView) {
                    if (isProfileClick && rowView.contact != null) {
                        requireContext().startActivity(
                            DashPayUserActivity.createIntent(
                                requireContext(),
                                rowView.contact
                            )
                        )
                    } else {
                        // For rows loaded from the display cache, txWrapper is null.
                        // Fall back to the live wrapper list so CoinJoin/CrowdNode groups still open.
                        val txWrapper = rowView.txWrapper ?: viewModel.getTransactionWrapper(rowView.id)
                        val fragment = when {
                            txWrapper != null && txWrapper.transactions.size > 1 -> {
                                // Multi-tx group (CrowdNode / CoinJoin) — open group detail.
                                viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                                TransactionGroupDetailsFragment(txWrapper)
                            }

                            txWrapper != null -> {
                                // Single-tx wrapper found in memory — open TX detail directly.
                                // TransactionGroupDetailsFragment would show the wrong (default
                                // CrowdNode) icon for non-group wrappers, so route here instead.
                                if (ServiceName.isDashSpend(rowView.service)) {
                                    viewModel.logEvent(AnalyticsConstants.DashSpend.DETAILS_GIFT_CARD)
                                    val txId = rowView.id
                                    if (giftCardViewModel.getGiftCardCount(txId) > 1) {
                                        GiftCardOrderDetailsDialog.newInstance(txId)
                                    } else {
                                        GiftCardDetailsDialog.newInstance(txId)
                                    }
                                } else {
                                    viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                                    TransactionDetailsDialogFragment.newInstance(Sha256Hash.wrap(txWrapper.transactions.keys.first()))
                                }
                            }

                            ServiceName.isDashSpend(rowView.service) -> {
                                viewModel.logEvent(AnalyticsConstants.DashSpend.DETAILS_GIFT_CARD)
                                val txId = rowView.id
                                if (giftCardViewModel.getGiftCardCount(txId) > 1) {
                                    GiftCardOrderDetailsDialog.newInstance(txId)
                                } else {
                                    GiftCardDetailsDialog.newInstance(txId)
                                }
                            }

                            rowView.transactionAmount == 1 -> {
                                // Individual transaction — rowId is a 64-char txId hex string.
                                viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                                TransactionDetailsDialogFragment.newInstance(Sha256Hash.wrap(rowView.id))
                            }

                            else -> {
                                // Group row whose wrapper isn't loaded yet (lazy startup) —
                                // load it on demand so the user can still open the detail view.
                                viewLifecycleOwner.lifecycleScope.launch {
                                    val wrapper = viewModel.loadGroupWrapper(rowView.id)
                                    val activity = if (isAdded) activity else null
                                    if (wrapper != null && activity != null) {
                                        viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                                        TransactionGroupDetailsFragment(wrapper).show(activity)
                                    } else if (wrapper == null) {
                                        log.warn("group {} not found in cache — cannot open details", rowView.id)
                                    }
                                }
                                null  // fragment already shown inside the coroutine above
                            }
                        }

                        fragment?.show(requireActivity())
                    }
                }
            }
            Unit // don't return the job
        }

        // Long-press "History" title → offer to wipe and rebuild the transaction cache.
        binding.transactionListTitle.setOnLongClickListener {
            AdaptiveDialog.create(
                icon = null,
                negativeButtonText = getString(R.string.cancel),
                positiveButtonText = getString(R.string.history_refresh_dialog_confirm),
                title = getString(R.string.history_refresh_dialog_title),
                message = getString(R.string.history_refresh_dialog_message)
            ).show(requireActivity()) { result ->
                if (result == true) {
                    pendingManualRefresh = true
                    viewModel.forceRebuildTransactionCache()
                }
            }
            true // consume
        }

        // Cache adapter (plain ListAdapter) — shown immediately using pre-built rows from Room.
        // submitList() is a single background DiffUtil + one main-thread handler post: much faster
        // than PagingDataAdapter.submitData() which dispatches through 4+ coroutine contexts.
        val cacheAdapter = CacheTransactionAdapter(viewModel.balanceDashFormat, resources, true, clickHandler)
        // Live adapter (PagingDataAdapter) — activated after the wallet finishes loading.
        val liveAdapter = TransactionAdapter(viewModel.balanceDashFormat, resources, true, clickHandler)

        // Scroll to top when new live transactions arrive at the top of the list.
        // Register once per view; unregister on view destroy to avoid duplicate observers
        // across lifecycle STARTED/STOPPED transitions.
        val scrollObserver = object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    binding.walletTransactionsList.scrollToPosition(0)
                }
            }
        }
        liveAdapter.registerAdapterDataObserver(scrollObserver)
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                liveAdapter.unregisterAdapterDataObserver(scrollObserver)
            }
        })

        binding.transactionFilterBtn.setOnClickListener {
            val dialogFragment = TransactionsFilterDialog(viewModel.transactionsDirection) { direction, _ ->
                viewModel.transactionsDirection = direction
                viewModel.logDirectionChangedEvent(direction)
            }

            dialogFragment.show(requireActivity())
        }

        header = HistoryHeaderAdapter(
            requireContext().getSharedPreferences(
                HistoryHeaderAdapter.PREFS_FILE_NAME,
                Context.MODE_PRIVATE
            )
        )

        header.setOnIdentityRetryClicked { retryIdentityCreation(header) }
        header.setOnIdentityClicked { openIdentityCreation() }
        header.setOnJoinDashPayClicked { onJoinDashPayClicked() }
        header.setOnAcceptInviteCreateClicked { onAcceptInvite() }
        header.setOnAcceptInviteHideClicked { onHideInvite() }

        // Single ConcatAdapter kept for the entire Fragment lifetime.
        // We swap cacheAdapter ↔ liveAdapter inside it (removeAdapter / addAdapter) to avoid
        // the ConcatAdapterController "cannot find wrapper" crash that occurs when two separate
        // ConcatAdapter instances try to call onViewDetachedFromWindow on each other's ViewHolders.
        val concatAdapter = ConcatAdapter(header, cacheAdapter)

        binding.walletTransactionsList.setHasFixedSize(true)
        binding.walletTransactionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.walletTransactionsList.adapter = concatAdapter

        // Log when cache items are actually inserted into the RecyclerView (after DiffUtil).
        cacheAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (firstPageLoadStartTime > 0L && cacheAdapter.itemCount > 0) {
                    log.info("STARTUP cache items visible: {}ms from onViewCreated, {}ms from submitList ({} items)",
                        System.currentTimeMillis() - onViewCreatedTime,
                        System.currentTimeMillis() - firstPageLoadStartTime,
                        cacheAdapter.itemCount)
                    firstPageLoadStartTime = -1L
                }
            }
        })

        // Fast cache path — ListAdapter.submitList() dispatches DiffUtil once on a background
        // thread, then posts a single update to the main thread.  No Paging3 coroutine chain.
        // Note: we collect ALL emissions (including emptyList) so that when the cache is
        // cleared on a blockchain reset, cacheAdapter is also cleared and cacheHasItems
        // becomes false — allowing showEmptyView() to fire correctly.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cachedRows.collect { rows ->
                cacheAdapter.submitList(rows)
                if (rows.isNotEmpty()) {
                    log.info("STARTUP cache submitList: {} rows at {}", rows.size, System.currentTimeMillis())
                    if (firstPageLoadStartTime == 0L) {
                        firstPageLoadStartTime = System.currentTimeMillis()
                    }
                    showTransactionList()
                }
            }
        }

        val horizontalMargin = resources.getDimensionPixelOffset(R.dimen.default_horizontal_padding)
        val verticalMargin = resources.getDimensionPixelOffset(R.dimen.default_vertical_padding)
        binding.walletTransactionsList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                super.getItemOffsets(outRect, view, parent, state)
                outRect.left = horizontalMargin
                outRect.right = horizontalMargin

                if (view.tag == HEADER_ITEM_TAG) {
                    outRect.top = verticalMargin * 2
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncStatus.map { it.isSynced }.distinctUntilChanged().collect { isSynced ->
                header.isSynced = isSynced
                if (inviteHandlerViewModel.isUsingInvite) {
                    // Re-drives the invite through validation on every sync-state
                    // change: an invite checked mid-scan gets the provisional
                    // NOT_SYNCED verdict, and this is what converts it to
                    // VALID/ALREADY_CLAIMED once the scan completes — the user
                    // never has to re-tap the link.
                    processInvitation(
                        inviteHandlerViewModel.invitation.value!!,
                        isLockScreenActive(),
                        revalidate = isSynced
                    )
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncStatus.collect { updateSyncState(it) }
        }

        // Collect live PagingData and submit to the live (PagingDataAdapter) adapter.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transactions.collectLatest { pagingData ->
                log.info("STARTUP submitData called on thread={} at {}", Thread.currentThread().name, System.currentTimeMillis())
                liveAdapter.submitData(pagingData)
            }
        }

        // Handle loading/empty states via liveAdapter's loadStateFlow.
        // Also swaps the RecyclerView from cacheAdapter to liveAdapter once live items arrive.
        viewLifecycleOwner.lifecycleScope.launch {
            liveAdapter.loadStateFlow
                .distinctUntilChanged()
                .collectLatest { loadStates ->
                    val isRefreshing = loadStates.refresh is LoadState.Loading
                    val cacheHasItems = concatAdapter.adapters.contains(cacheAdapter) &&
                        cacheAdapter.currentList.isNotEmpty()
                    // Only show the loading spinner when there's nothing to display yet.
                    val isLoading = isRefreshing && liveAdapter.itemCount == 0 && !cacheHasItems
                    val isEmpty = loadStates.refresh is LoadState.NotLoading &&
                        liveAdapter.itemCount == 0 && !cacheHasItems

                    // Swap cacheAdapter → liveAdapter inside the same ConcatAdapter once live
                    // items are ready.  Keeping one ConcatAdapter instance avoids the
                    // "cannot find wrapper" crash from ConcatAdapterController.
                    if (loadStates.refresh is LoadState.NotLoading && liveAdapter.itemCount > 0 &&
                        concatAdapter.adapters.contains(cacheAdapter)) {
                        log.info("STARTUP swapping to live adapter: {} items at {}",
                            liveAdapter.itemCount, System.currentTimeMillis())
                        val lm = binding.walletTransactionsList.layoutManager as LinearLayoutManager
                        val scrollState = lm.onSaveInstanceState()
                        concatAdapter.removeAdapter(cacheAdapter)
                        concatAdapter.addAdapter(liveAdapter)
                        lm.onRestoreInstanceState(scrollState)
                    }

                    if (!isRefreshing && liveAdapter.itemCount > 0 && firstPageLoadStartTime > 0L) {
                        log.info("STARTUP first live items visible: {}ms from onViewCreated, {}ms from first-load-start ({} items)",
                            System.currentTimeMillis() - onViewCreatedTime,
                            System.currentTimeMillis() - firstPageLoadStartTime,
                            liveAdapter.itemCount)
                        firstPageLoadStartTime = -1L // prevent re-logging on subsequent invalidations
                    }

                    val buildingFromScratch = viewModel.isBuildingCache.value &&
                        liveAdapter.itemCount == 0 && cacheAdapter.currentList.isEmpty()
                    binding.loading.isVisible = isLoading || buildingFromScratch
                    if (isEmpty && header.isEmpty()) showEmptyView() else showTransactionList()
                }
        }

        // Show the "determining transaction history" overlay only when building the cache
        // from scratch (no rows displayed yet). If rows are already visible, suppress the
        // overlay so the existing list stays on screen during background rebuilds.
        viewModel.isBuildingCache.observe(viewLifecycleOwner) { building ->
            val hasRows = viewModel.transactionsLoaded.value &&
                (liveAdapter.itemCount > 0 || cacheAdapter.currentList.isNotEmpty())
            binding.loading.isVisible = building && !hasRows
            if (!building && pendingManualRefresh) {
                pendingManualRefresh = false
                Toast.makeText(requireContext(), R.string.history_refresh_complete, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.blockchainIdentity.observe(viewLifecycleOwner) { identity ->
            if (identity != null) {
                (requireActivity() as? LockScreenActivity)?.imitateUserInteraction()
                header.blockchainIdentityData = identity
            }
        }

        // Transient retry-status hint on the identity processing tile —
        // why the current step is taking longer than usual (e.g. platform
        // consensus core height lagging a fresh funding tx; no IS lock
        // yet). Cleared by the service on success/fresh runs.
        identityCreationStatus.statusHint.observe(viewLifecycleOwner) { hint ->
            header.statusHint = when (hint) {
                RetryStatusHint.CORE_HEIGHT_LAG -> getString(R.string.identity_processing_network_catching_up)
                RetryStatusHint.WAITING_FOR_ISLOCK -> getString(R.string.identity_processing_waiting_confirmation)
                null -> null
            }
        }

        inviteHandlerViewModel.invitation.observe(viewLifecycleOwner) { invitation ->
            val isSynced = viewModel.syncStatus.value.isSynced
            // No longer gated on isSynced: an invite that arrives mid-scan
            // must still get a verdict (the provisional NOT_SYNCED one) and
            // the accompanying notice, instead of silently doing nothing.
            if (invitation != null) {
                processInvitation(invitation, isLockScreenActive())
            }
            header.invitation = invitation
            header.isSynced = isSynced
            if (invitation != null) {
                showTransactionList()
                header.canJoinDashPay = false
            }
        }

        viewModel.isAbleToCreateIdentity.observe(viewLifecycleOwner) { canJoinDashPay ->
            header.canJoinDashPay = canJoinDashPay && header.invitation == null
        }

        val myListener = { onLockScreenDeactivated() }

        registerLockScreenDeactivated(myListener)

        viewLifecycleOwner.observeOnDestroy {
            binding.walletTransactionsList.adapter = null
            unregisterLockScreenDeactivated(myListener)
        }
    }

    private fun onLockScreenDeactivated() {
        lifecycleScope.launch {
            inviteHandlerViewModel.invitation.value?.let {
                // only process for the dialog
                processInvitation(it, isLockScreenActive = false)
            }
        }
    }

    private suspend fun processInvitation(
        invitation: InvitationLinkData,
        isLockScreenActive: Boolean,
        /**
         * Re-drive validation for an invite that only ever got the PROVISIONAL
         * NOT_SYNCED verdict. Set ONLY by the sync-state observer: validation
         * republishes the invitation, so re-validating off the invitation feed
         * itself would loop.
         */
        revalidate: Boolean = false
    ) {
        // NOT_SYNCED is a PROVISIONAL verdict, not a terminal one — the chain
        // was still catching up when the invite was checked. Unlike every
        // other state it must not latch, or the invite would sit behind this
        // early return for the rest of the launch (the live bug: nothing was
        // shown at all and the invite never resolved).
        val state = invitation.validationState
        val provisional = state == InvitationValidationState.NOT_SYNCED
        if (state != null && !provisional) return

        // InviteHandlerViewModel.validateInvitation() carries its own sync
        // gate and answers NOT_SYNCED while the chain is behind, so calling it
        // mid-sync is safe — and it is what lets the dialog below say
        // something instead of leaving the user with silence.
        if ((state == null && invitation.expired) || (provisional && revalidate)) {
            inviteHandlerViewModel.validateInvitation()
        }

        val currentInvitation = inviteHandlerViewModel.invitation.value ?: invitation
        if (!isLockScreenActive) {
            showInviteValidationDialog(currentInvitation)
        }
    }

    private suspend fun showInviteValidationDialog(invitation: InvitationLinkData) {
        when (invitation.validationState) {
            InvitationValidationState.INVALID -> {
                InviteHandler(
                    requireActivity(),
                    viewModel.analytics
                ).showInvalidInviteDialog(invitation.displayName)
                // remove invite
                inviteHandlerViewModel.clearInvitation()
            }

            InvitationValidationState.ALREADY_HAS_IDENTITY -> {
                // show dialog
                InviteHandler(requireActivity(), viewModel.analytics).showUsernameAlreadyDialog()
                // remove invite
                inviteHandlerViewModel.clearInvitation()
            }

            InvitationValidationState.ALREADY_HAS_REQUESTED_USERNAME -> {
                // show dialog
                InviteHandler(requireActivity(), viewModel.analytics).showContestedUsernameAlreadyDialog()
                // remove invite
                inviteHandlerViewModel.clearInvitation()
            }

            InvitationValidationState.VALID -> {

            }

            InvitationValidationState.ALREADY_CLAIMED -> {
                InviteHandler(requireActivity(), viewModel.analytics).showInviteAlreadyClaimedDialog(invitation)
                // remove invite
                inviteHandlerViewModel.clearInvitation()
            }

            InvitationValidationState.NONE -> {

            }

            InvitationValidationState.NOT_SYNCED -> {
                // SAFETY NET, not the primary surface: the invite tile itself
                // already carries the "still syncing" text
                // (accept_invitation_row.xml → join_dashpay_wait, shown while
                // HistoryHeaderAdapter.isSynced is false). This branch is only
                // reachable in the narrow race where the sync state flips
                // between the caller's guard and the validator's own check, so
                // a lightweight message is enough — no modal.
                //
                // Deliberately does NOT clearInvitation(): unlike every
                // terminal branch above, the invite must survive so
                // processInvitation can re-validate it once the scan finishes.
                // Shown once per launch so repeated sync-state emissions
                // cannot turn it into a nag.
                if (!notSyncedInviteNoticeShown) {
                    notSyncedInviteNoticeShown = true
                    Toast.makeText(
                        requireContext(),
                        R.string.invitation_not_synced_message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            else -> {}
        }
    }

    private fun onHideInvite() {
        lifecycleScope.launch {
            inviteHandlerViewModel.clearInvitation()
        }
    }

    private fun onAcceptInvite() {
        val invitation = inviteHandlerViewModel.invitation.value ?: return
        val createUsernameActivityIntent = CreateUsernameActivity.createIntentFromInvite(
            requireContext(),
            invitation,
            inviteHandlerViewModel.fromOnboarding
        )
        startActivity(createUsernameActivityIntent)
    }

    /** The single "Syncing N%" header — engine-agnostic (see L1SyncStatusService). */
    private fun updateSyncState(status: L1SyncUiStatus) {
        val isSynced = status.isSynced
        val percentage = status.percentage

        if (isSynced) {
            binding.syncing.isVisible = false
        } else {
            binding.syncing.isVisible = true
            var syncing = getString(R.string.syncing)

            if (percentage == 0) {
                syncing += "…"
                binding.syncing.text = syncing
            } else {
                val str = SpannableStringBuilder("$syncing $percentage%")
                val start = syncing.length + 1
                val end = str.length
                str.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.syncing.text = str
            }
        }
    }

    fun scrollToTop() {
        binding.walletTransactionsList.scrollToPosition(0)
    }

    private fun showTransactionList() {
        binding.walletTransactionsEmpty.isVisible = false
        binding.walletTransactionsList.isVisible = true
    }

    private fun showEmptyView() {
        // Don't show "no transactions" text while the cache is being built — the loading
        // overlay covers this state and showing both at once is confusing.
        binding.walletTransactionsEmpty.isVisible =
            viewModel.transactionsLoaded.value && !viewModel.isBuildingCache.value
        binding.walletTransactionsList.isVisible = false
    }

    private fun retryIdentityCreation(header: HistoryHeaderAdapter) {
        viewModel.blockchainIdentity.value?.let { blockchainIdentityData ->
            viewModel.logEvent(AnalyticsConstants.UsersContacts.CREATE_USERNAME_TRYAGAIN)
            // check to see if restoring or if an invite was used
            if (blockchainIdentityData.restoring) {
                RestoreIdentityOperation(requireActivity().application)
                    .create(blockchainIdentityData.userId!!, true)
                    .enqueue()
            } else if (!blockchainIdentityData.usingInvite) {
                requireActivity().startService(
                    CreateIdentityService.createIntentForRetry(
                        requireActivity(),
                        false
                    )
                )
            } else {
                // handle errors from using an invite
                viewLifecycleOwner.lifecycleScope.launch {
                    val handler = InviteHandler(requireActivity(), viewModel.analytics)

                    if (handler.handleError(blockchainIdentityData, identityRepository)) {
                        header.blockchainIdentityData = null
                    } else {
                        requireActivity().startService(
                            CreateIdentityService.createIntentForRetryFromInvite(
                                requireActivity(),
                                false
                            )
                        )
                    }
                }
            }
        }
    }

    private fun openIdentityCreation() {
        viewModel.blockchainIdentity.value?.let { blockchainIdentityData ->
            if (blockchainIdentityData.creationStateErrorMessage != null) {
                // are we restoring?
                if (blockchainIdentityData.restoring) {
                    RestoreIdentityOperation(requireActivity().application)
                        .create(blockchainIdentityData.userId!!, true)
                        .enqueue()
                } else {
                    // Do we need to have the user request a new username
                    val errorMessage = blockchainIdentityData.creationStateErrorMessage
                    val needsNewUsername =
                        (blockchainIdentityData.creationState == IdentityCreationState.USERNAME_REGISTERING ||
                                blockchainIdentityData.creationState == IdentityCreationState.USERNAME_SECONDARY_REGISTERING) &&
                                (errorMessage?.contains("Document transitions with duplicate unique properties") == true ||
                                        errorMessage?.contains("missing domain document for") == true ||
                                errorMessage?.contains("DuplicateUniqueIndexError") == true)
                    if (needsNewUsername ||
                        // do we need this, cause the error could be due to a stale node
                        blockchainIdentityData.creationState == IdentityCreationState.REQUESTED_NAME_CHECKING &&
                        (errorMessage?.contains("invalid quorum: quorum not found") != true ||
                                errorMessage.contains("invalid peer certificate: certificate expired") == true)
                    ) {
                        startActivity(
                            CreateUsernameActivity.createIntentReuseTransaction(
                                requireActivity(),
                                blockchainIdentityData
                            )
                        )
                    } else {
                        // we don't know what to do in this case? (not good)
                        Toast.makeText(
                            requireContext(),
                            blockchainIdentityData.creationStateErrorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (blockchainIdentityData.creationState == IdentityCreationState.DONE) {
                safeNavigate(WalletFragmentDirections.homeToSearchUser())
                // hide "Hello Card" after first click
                viewModel.dismissUsernameCreatedCard()
            } else if (blockchainIdentityData.creationState == IdentityCreationState.VOTING &&
                !blockchainIdentityData.usernameSecondary.isNullOrEmpty()
            ) {
                // DUAL creation welcome card (instant secondary usable while
                // the contested primary is in voting): same first-click
                // behavior as DONE, but the dismissal persists via the
                // header's own pref — DONE_AND_DISMISS must not be written
                // while the state machine is at VOTING (it would kill the
                // More screen's voting tile).
                safeNavigate(WalletFragmentDirections.homeToSearchUser())
                header.dismissVotingDualHelloCard()
            } else {
                // not possible to get here?
            }
        }
    }

    private fun onJoinDashPayClicked() {
        viewModel.logEvent(AnalyticsConstants.UsersContacts.JOIN_DASHPAY)
        viewModel.joinDashPay()
    }
}
