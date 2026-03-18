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
import kotlinx.coroutines.flow.filterNot
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.InvitationValidationState
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
import org.dash.wallet.features.exploredash.ui.dashspend.dialogs.GiftCardDetailsDialog
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class WalletTransactionsFragment : Fragment(R.layout.wallet_transactions_fragment) {
    companion object {
        private const val HEADER_ITEM_TAG = "header"
        private val log = LoggerFactory.getLogger(WalletTransactionsFragment::class.java)
    }

    private var firstPageLoadStartTime: Long = 0L
    private var onViewCreatedTime: Long = 0L

    private val viewModel by activityViewModels<MainViewModel>()
    private val binding by viewBinding(WalletTransactionsFragmentBinding::bind)
    private val inviteHandlerViewModel by activityViewModels<InviteHandlerViewModel>()

    val isHistoryEmpty: Boolean
        get() = (binding.walletTransactionsList.adapter?.itemCount ?: 0) == 0

    private lateinit var header: HistoryHeaderAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreatedTime = System.currentTimeMillis()
        log.info("STARTUP WalletTransactionsFragment.onViewCreated at {}", onViewCreatedTime)

        val clickHandler = { rowView: HistoryRowView, _: Int, isProfileClick: Boolean ->
            if (rowView is TransactionRowView) {
                if (isProfileClick && rowView.contact != null) {
                    requireContext().startActivity(DashPayUserActivity.createIntent(requireContext(), rowView.contact))
                } else {
                    // For rows loaded from the display cache, txWrapper is null.
                    // Fall back to the live wrapper list so CoinJoin/CrowdNode groups still open.
                    val txWrapper = rowView.txWrapper ?: viewModel.getTransactionWrapper(rowView.id)
                    val fragment = when {
                        txWrapper != null -> {
                            viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                            TransactionGroupDetailsFragment(txWrapper)
                        }
                        ServiceName.isDashSpend(rowView.service) -> {
                            viewModel.logEvent(AnalyticsConstants.DashSpend.DETAILS_GIFT_CARD)
                            GiftCardDetailsDialog.newInstance(Sha256Hash.wrap(rowView.id))
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

        viewLifecycleOwner.observeOnDestroy {
            binding.walletTransactionsList.adapter = null
        }

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
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cachedRows
                .filterNot { it.isEmpty() }
                .collect { rows ->
                    log.info("STARTUP cache submitList: {} rows at {}", rows.size, System.currentTimeMillis())
                    if (firstPageLoadStartTime == 0L) {
                        firstPageLoadStartTime = System.currentTimeMillis()
                    }
                    cacheAdapter.submitList(rows)
                    showTransactionList()
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

        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) {
            header.isSynced = it
            if (inviteHandlerViewModel.isUsingInvite) {
                lifecycleScope.launch {
                    processInvitation(inviteHandlerViewModel.invitation.value!!, isSynced = it, isLockScreenActive())
                }
            }
            updateSyncState()
        }
        viewModel.blockchainSyncPercentage.observe(viewLifecycleOwner) { updateSyncState() }

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
                    val cacheHasItems = cacheAdapter.currentList.isNotEmpty()
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

                    binding.loading.isVisible = isLoading || viewModel.isBuildingCache.value
                    if (isEmpty && header.isEmpty()) showEmptyView() else showTransactionList()
                }
        }

        // Show the "determining transaction history" overlay while the cache is being built.
        viewModel.isBuildingCache.observe(viewLifecycleOwner) { building ->
            binding.loading.isVisible = building
        }

        viewModel.blockchainIdentity.observe(viewLifecycleOwner) { identity ->
            if (identity != null) {
                (requireActivity() as? LockScreenActivity)?.imitateUserInteraction()
                header.blockchainIdentityData = identity
            }
        }

        inviteHandlerViewModel.invitation.observe(viewLifecycleOwner) { invitation ->
            val isSynced = viewModel.isBlockchainSynced.value == true
            if (invitation != null && isSynced) {
                processInvitation(invitation, viewModel.isBlockchainSynced.value == true, isLockScreenActive())
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
                processInvitation(
                    it,
                    viewModel.isBlockchainSynced.value == true,
                    false
                )
            }
        }
    }

    private suspend fun processInvitation(invitation: InvitationLinkData, isSynced: Boolean, isLockScreenActive: Boolean) {
        if (invitation.validationState != null) return
        if (isSynced) {
            if (invitation.expired) {
                inviteHandlerViewModel.validateInvitation()
            }

            val currentInvitation = inviteHandlerViewModel.invitation.value ?: invitation
            if (!isLockScreenActive) {
                showInviteValidationDialog(currentInvitation)
            }
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
        val createUsernameActivityIntent = CreateUsernameActivity.createIntentFromInvite(
            requireContext(),
            inviteHandlerViewModel.invitation.value!!,
            inviteHandlerViewModel.fromOnboarding
        )
        startActivity(createUsernameActivityIntent)
    }

    private fun updateSyncState() {
        val isSynced = viewModel.isBlockchainSynced.value
        val percentage = viewModel.blockchainSyncPercentage.value

        if (isSynced != null && isSynced) {
            binding.syncing.isVisible = false
        } else {
            binding.syncing.isVisible = true
            var syncing = getString(R.string.syncing)

            if (percentage == null || percentage == 0) {
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

                    if (handler.handleError(blockchainIdentityData)) {
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
