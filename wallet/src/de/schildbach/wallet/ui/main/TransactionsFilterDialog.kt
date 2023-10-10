/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.ui.main

import android.os.Bundle
import android.view.View
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import de.schildbach.wallet.transactions.TxFilterType
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogTransactionsFilterBinding
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.IconSelectMode
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.viewBinding

class TransactionsFilterDialog(
    private val selectedOption: TxFilterType,
    private val clickListener: (TxFilterType, DialogFragment) -> Unit
) : OffsetDialogFragment(R.layout.dialog_transactions_filter) {
    @StyleRes
    override val backgroundStyle: Int = R.style.PrimaryBackground
    private val binding by viewBinding(DialogTransactionsFilterBinding::bind)
    private val options = listOf(TxFilterType.ALL, TxFilterType.SENT, TxFilterType.RECEIVED)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = RadioGroupAdapter(options.indexOf(selectedOption)) { _, index ->
            clickListener.invoke(options[index], this)
            dismiss()
        }
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.transaction_filter_separator_start)
        )
        binding.directionList.addItemDecoration(decorator)
        binding.directionList.adapter = adapter
        adapter.submitList(
            options.map {
                when (it) {
                    TxFilterType.ALL -> IconifiedViewItem(
                        getString(R.string.all_transactions),
                        iconRes = R.drawable.ic_filter_all,
                        iconSelectMode = IconSelectMode.None
                    )
                    TxFilterType.SENT -> IconifiedViewItem(
                        getString(R.string.sent_transactions),
                        iconRes = R.drawable.ic_filter_sent,
                        iconSelectMode = IconSelectMode.None
                    )
                    TxFilterType.RECEIVED -> IconifiedViewItem(
                        getString(R.string.received_transactions),
                        iconRes = R.drawable.ic_filter_received,
                        iconSelectMode = IconSelectMode.None
                    )
                    TxFilterType.GIFT_CARD -> IconifiedViewItem(
                        getString(R.string.explore_filter_gift_cards),
                        iconRes = R.drawable.ic_filter_gift_card,
                        iconSelectMode = IconSelectMode.None
                    )
                }
            }
        )
    }
}
