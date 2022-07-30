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

import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.transactions.TransactionDetailsDialogFragment.Companion.newInstance
import de.schildbach.wallet.ui.transactions.TransactionGroupDetailsFragment
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.WalletTransactionsFragmentBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class WalletTransactionsFragment : Fragment(R.layout.wallet_transactions_fragment) {
    private val viewModel by viewModels<MainViewModel>()
    private val binding by viewBinding(WalletTransactionsFragmentBinding::bind)

    val isHistoryEmpty: Boolean
        get() = (binding.walletTransactionsList.adapter?.itemCount ?: 0) == 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = TransactionAdapter(
            viewModel.balanceDashFormat, resources, true
        ) { rowView, _ ->
            viewModel.logEvent(AnalyticsConstants.Home.TRANSACTION_DETAILS)

            if (rowView.txWrapper != null) {
                val fragment = TransactionGroupDetailsFragment(rowView.txWrapper)
                fragment.show(parentFragmentManager, "transaction_group")
            } else {
                val transactionDetailsDialogFragment = newInstance(rowView.txId)
                transactionDetailsDialogFragment.show(parentFragmentManager, null)
            }
        }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    binding.walletTransactionsList.scrollToPosition(0)
                }
            }
        })

        binding.transactionFilterBtn.setOnClickListener {
            val dialogFragment = TransactionsFilterDialog { direction, _ ->
                viewModel.transactionsDirection = direction
                viewModel.logDirectionChangedEvent(direction)
            }

            dialogFragment.show(childFragmentManager, null)
        }

        binding.walletTransactionsList.setHasFixedSize(true)
        binding.walletTransactionsList.layoutManager = LinearLayoutManager(requireContext())
        binding.walletTransactionsList.adapter = adapter
        binding.walletTransactionsList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val VERTICAL = resources.getDimensionPixelOffset(R.dimen.default_vertical_padding)
            private val HORIZONTAL = resources.getDimensionPixelOffset(R.dimen.default_horizontal_padding)

            override fun getItemOffsets(
                outRect: Rect, view: View, parent: RecyclerView,
                state: RecyclerView.State
            ) {
                super.getItemOffsets(outRect, view, parent, state)
                outRect.left = HORIZONTAL
                outRect.right = HORIZONTAL
                outRect.top = VERTICAL
                outRect.bottom = VERTICAL
            }
        })

        viewModel.isBlockchainSynced.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.blockchainSyncPercentage.observe(viewLifecycleOwner) { updateSyncState() }
        viewModel.transactions.observe(viewLifecycleOwner) { transactionViews ->
            binding.loading.isVisible = false
            adapter.submitList(transactionViews)

            if (transactionViews.isEmpty()) {
                showEmptyView()
            } else {
                showTransactionList()
            }
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
                    StyleSpan(Typeface.BOLD), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.syncing.text = str
            }
        }
    }

    private fun showTransactionList() {
        binding.walletTransactionsEmpty.isVisible = false
        binding.walletTransactionsList.isVisible = true
    }

    private fun showEmptyView() {
        binding.walletTransactionsEmpty.isVisible = true
        binding.walletTransactionsList.isVisible = false
    }
}