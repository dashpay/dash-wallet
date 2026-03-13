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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.slf4j.LoggerFactory
import org.dash.wallet.common.services.analytics.AnalyticsConstants
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

        val adapter = TransactionAdapter(
            viewModel.balanceDashFormat,
            resources,
            true
        ) { rowView, _, isProfileClick ->
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
                            // Group row tapped before live rebuild finished — ignore.
                            log.warn("tapped group row {} before live wrappers available", rowView.id)
                            null
                        }
                    }

                    fragment?.show(requireActivity())
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // these observers had exceptions after the view was destroyed
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val observer = object : RecyclerView.AdapterDataObserver() {
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        if (positionStart == 0) {
                            binding.walletTransactionsList.scrollToPosition(0)
                        }
                    }
                }

                adapter.registerAdapterDataObserver(observer)
                try {
                    awaitCancellation() // Keeps the block alive
                } finally {
                    adapter.unregisterAdapterDataObserver(observer)
                }
            }
        }

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

        binding.walletTransactionsList.setHasFixedSize(true)
        binding.walletTransactionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.walletTransactionsList.adapter = ConcatAdapter(header, adapter)

        viewLifecycleOwner.observeOnDestroy {
            binding.walletTransactionsList.adapter = null
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

        // Collect PagingData and submit to adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transactions.collectLatest { pagingData ->
                log.info("STARTUP submitData called on thread={} at {}", Thread.currentThread().name, System.currentTimeMillis())
                adapter.submitData(pagingData)
            }
        }

        // Handle loading/empty states via loadStateFlow
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.loadStateFlow
                .distinctUntilChanged()
                .collectLatest { loadStates ->
                    val isRefreshing = loadStates.refresh is LoadState.Loading
                    // Only show the loading spinner when there's nothing to display yet.
                    // When a background refresh (cache → live, or metadata invalidation) is in
                    // progress the adapter already has items, so we suppress the spinner to avoid
                    // a jarring "items → spinner → same items" flash.
                    val isLoading = isRefreshing && adapter.itemCount == 0
                    val isEmpty = loadStates.refresh is LoadState.NotLoading && adapter.itemCount == 0

                    if (isRefreshing && firstPageLoadStartTime == 0L) {
                        firstPageLoadStartTime = System.currentTimeMillis()
                    } else if (!isRefreshing && adapter.itemCount > 0 && firstPageLoadStartTime > 0L) {
                        log.info("STARTUP first items visible: {}ms from onViewCreated, {}ms from first-load-start ({} items)",
                            System.currentTimeMillis() - onViewCreatedTime,
                            System.currentTimeMillis() - firstPageLoadStartTime,
                            adapter.itemCount)
                        firstPageLoadStartTime = -1L // prevent re-logging on subsequent invalidations
                    }

                    binding.loading.isVisible = isLoading
                    if (isEmpty && header.isEmpty()) showEmptyView() else showTransactionList()
                }
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
        binding.walletTransactionsEmpty.isVisible = viewModel.transactionsLoaded.value
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
