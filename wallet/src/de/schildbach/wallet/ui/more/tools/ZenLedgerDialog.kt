/*
 * Copyright (c) 2024 Dash Core Group
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

package de.schildbach.wallet.ui.more.tools

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogZenledgerBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.openCustomTab

@AndroidEntryPoint
class ZenLedgerDialog : OffsetDialogFragment(R.layout.dialog_zenledger) {
    private val binding by viewBinding(DialogZenledgerBinding::bind)
    val viewModel by viewModels<ZenLedgerViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.exportAllTransactions.setOnClickListener {
            lifecycleScope.launch {
                if (viewModel.isSynced()) {
                    if (AdaptiveDialog.create(
                            null,
                            getString(R.string.zenledger_export_title),
                            getString(R.string.zenledger_export_permission),
                            getString(R.string.button_cancel),
                            getString(R.string.permission_allow)
                        ).showAsync(requireActivity()) == true
                    ) {
                        if (viewModel.sendTransactionInformation()) {
                            requireActivity().openCustomTab(viewModel.signUpUrl!!)
                            dismiss()
                        } else {
                            AdaptiveDialog.create(
                                null,
                                getString(R.string.zenledger_export_title),
                                getString(R.string.zenledger_export_error),
                                getString(R.string.button_close)
                            ).showAsync(requireActivity())
                        }
                    } else {
                        AdaptiveDialog.create(
                            null,
                            getString(R.string.chain_syncing),
                            getString(R.string.chain_syncing_default_message),
                            getString(R.string.button_close)
                        ).showAsync(requireActivity())
                    }
                }
            }
        }
    }
}
