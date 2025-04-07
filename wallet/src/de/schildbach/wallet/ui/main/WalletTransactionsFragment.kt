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
import android.content.Intent
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
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.CreateUsernameActivity
import de.schildbach.wallet.ui.DashPayUserActivity
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.SearchUserActivity
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.HistoryHeaderAdapter
import de.schildbach.wallet.ui.invite.InviteHandler
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment
import de.schildbach.wallet.ui.transactions.TransactionGroupDetailsFragment
import de.schildbach.wallet.ui.transactions.TransactionRowView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.WalletTransactionsFragmentBinding
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.features.exploredash.ui.ctxspend.dialogs.GiftCardDetailsDialog
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class WalletTransactionsFragment : Fragment(R.layout.wallet_transactions_fragment) {
    companion object {
        private const val HEADER_ITEM_TAG = "header"
    }

    private val viewModel by activityViewModels<MainViewModel>()
    private val binding by viewBinding(WalletTransactionsFragmentBinding::bind)

    val isHistoryEmpty: Boolean
        get() = (binding.walletTransactionsList.adapter?.itemCount ?: 0) == 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = TransactionAdapter(
            viewModel.balanceDashFormat,
            resources,
            true
        ) { rowView, _, isProfileClick ->
            if (rowView is TransactionRowView) {
                if (isProfileClick && rowView.contact != null) {
                    requireContext().startActivity(DashPayUserActivity.createIntent(requireContext(), rowView.contact))
                } else {
                    val fragment = if (rowView.txWrapper != null) {
                        viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                        TransactionGroupDetailsFragment(rowView.txWrapper)
                    } else if (rowView.service == ServiceName.CTXSpend) {
                        viewModel.logEvent(AnalyticsConstants.DashSpend.DETAILS_GIFT_CARD)
                        GiftCardDetailsDialog.newInstance(Sha256Hash.wrap(rowView.id))
                    } else {
                        viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)
                        TransactionDetailsDialogFragment.newInstance(Sha256Hash.wrap(rowView.id))
                    }

                    fragment.show(requireActivity())
                }
            }
        }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0 && isAdded && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    binding.walletTransactionsList.scrollToPosition(0)
                }
            }
        })

        binding.transactionFilterBtn.setOnClickListener {
            val dialogFragment = TransactionsFilterDialog(viewModel.transactionsDirection) { direction, _ ->
                viewModel.transactionsDirection = direction
                viewModel.logDirectionChangedEvent(direction)
            }

            dialogFragment.show(requireActivity())
        }

        val header = HistoryHeaderAdapter(
            requireContext().getSharedPreferences(
                HistoryHeaderAdapter.PREFS_FILE_NAME,
                Context.MODE_PRIVATE
            )
        )

        header.setOnIdentityRetryClicked { retryIdentityCreation(header) }
        header.setOnIdentityClicked { openIdentityCreation() }
        header.setOnJoinDashPayClicked { onJoinDashPayClicked() }

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

        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.blockchainSyncPercentage.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.transactions.observe(viewLifecycleOwner) { transactionViews ->
            val currentLifecycle = viewLifecycleOwner.lifecycle
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                binding.loading.isVisible = false

                if (transactionViews.isEmpty()) {
                    showEmptyView()
                } else {
                    val groupedByDate = transactionViews.entries
                        .sortedByDescending { it.key }
                        .map {
                            val outList = mutableListOf<HistoryRowView>()
                            outList.add(HistoryRowView(null, it.key))
                            outList.apply { addAll(it.value) }
                        }.reduce { acc, list -> acc.apply { addAll(list) } }

                    adapter.submitList(groupedByDate) {
                        // Check again if the view is still in a valid state before any post-update operations
                        if (isAdded && currentLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            showTransactionList()
                        }
                    }
                }
            }
        }

        viewModel.blockchainIdentity.observe(viewLifecycleOwner) { identity ->
            if (identity != null) {
                (requireActivity() as? LockScreenActivity)?.imitateUserInteraction()
                header.blockchainIdentityData = identity
            }
        }

        viewModel.isAbleToCreateIdentityLiveData.observe(viewLifecycleOwner) { canJoinDashPay ->
            header.canJoinDashPay = canJoinDashPay
        }

        viewLifecycleOwner.observeOnDestroy {
            binding.walletTransactionsList.adapter = null
        }
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
        binding.walletTransactionsEmpty.isVisible = viewModel.transactionsLoaded
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
                        blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.USERNAME_REGISTERING &&
                                (errorMessage.contains("Document transitions with duplicate unique properties") ||
                                        errorMessage.contains("missing domain document for"))
                    if (needsNewUsername ||
                        // do we need this, cause the error could be due to a stale node
                        blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.REQUESTED_NAME_CHECKING &&
                        !errorMessage.contains("invalid quorum: quorum not found")
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
            } else if (blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.DONE) {
                startActivity(Intent(requireActivity(), SearchUserActivity::class.java))
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
