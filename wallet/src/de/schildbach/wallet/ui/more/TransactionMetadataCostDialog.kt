/*
 * Copyright (c) 2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.ui.more

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogTransactionMetadataCostsBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class TransactionMetadataCostDialog : OffsetDialogFragment(R.layout.dialog_transaction_metadata_costs) {
    private val binding by viewBinding(DialogTransactionMetadataCostsBinding::bind)
    private val viewModel: TransactionMetadataSettingsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.selectedExchangeRate.observe(viewLifecycleOwner) {
            binding.costInfo.text = getString(
                R.string.transaction_metadata_costs_description,
                viewModel.getBalanceInLocalFormat()
            )
        }
    }
}