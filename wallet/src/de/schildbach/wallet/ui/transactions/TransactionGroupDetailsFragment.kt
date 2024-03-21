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
package de.schildbach.wallet.ui.transactions

import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
import de.schildbach.wallet.ui.main.TransactionAdapter
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionGroupDetailsBinding
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.currencySymbol
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet

@AndroidEntryPoint
class TransactionGroupDetailsFragment() : OffsetDialogFragment(R.layout.transaction_group_details) {
    private val viewModel: TransactionGroupViewModel by viewModels()
    private val binding by viewBinding(TransactionGroupDetailsBinding::bind)

    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    private var transactionWrapper: TransactionWrapper? = null

    constructor(transactionWrapper: TransactionWrapper) : this() {
        this.transactionWrapper = transactionWrapper
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.dashAmount.setFormat(viewModel.dashFormat)

        when (transactionWrapper) {
            is FullCrowdNodeSignUpTxSet -> {
                binding.groupTitle.text = getString(R.string.crowdnode_account)
                binding.icon.setImageResource(R.drawable.ic_crowdnode_logo)
                binding.detailsTitle.text = getString(R.string.crowdnode_tx_set_title)
                binding.detailsMessage.text = getString(R.string.crowdnode_tx_set_explainer)
            }
            is CoinJoinMixingTxSet -> {
                binding.groupTitle.text = getString(R.string.coinjoin)
                binding.icon.setImageResource(R.drawable.ic_coinjoin)
                binding.detailsTitle.text = getString(R.string.crowdnode_tx_set_title)
                binding.detailsMessage.text = getString(R.string.coinjoin_transaction_group)
            }
        }

        val adapter = TransactionAdapter(viewModel.dashFormat, resources) { item, _, _ ->
            if (item is TransactionRowView) {
                TransactionDetailsDialogFragment
                    .newInstance(item.txId)
                    .show(requireActivity())
            }
        }

        binding.transactions.adapter = adapter
        val divider = ResourcesCompat.getDrawable(resources, R.drawable.list_divider, null)
        binding.transactions.addItemDecoration(
            ListDividerDecorator(
                divider!!,
                showAfterLast = false,
                marginStart = resources.getDimensionPixelOffset(R.dimen.transaction_row_divider_margin_start)
            )
        )

        viewModel.transactions.observe(viewLifecycleOwner) { transactions ->
            adapter.submitList(transactions)
        }

        viewModel.dashValue.observe(viewLifecycleOwner) {
            if (it.isNegative) {
                binding.dashAmount.setAmount(it.negate())
                binding.amountSignal.text = "-"
            } else {
                binding.dashAmount.setAmount(it)
                binding.amountSignal.text = "+"
            }
            setFiatValue()
        }

        viewModel.exchangeRate.observe(viewLifecycleOwner) {
            setFiatValue()
        }

        transactionWrapper?.let { viewModel.init(it) }
    }

    private fun setFiatValue() {
        val dashValue = viewModel.dashValue.value
        val exchangeRate = viewModel.exchangeRate.value

        if (dashValue != null && exchangeRate != null) {
            binding.fiatValue.isVisible = true
            binding.fiatValue.setFiatAmount(
                dashValue,
                exchangeRate,
                Constants.LOCAL_FORMAT,
                exchangeRate.fiat?.currencySymbol
            )
        } else {
            binding.fiatValue.isVisible = false
        }
    }
}
