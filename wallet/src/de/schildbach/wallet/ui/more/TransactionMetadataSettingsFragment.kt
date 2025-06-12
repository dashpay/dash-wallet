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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.safeNavigate
import java.text.SimpleDateFormat
import java.util.Date

@AndroidEntryPoint
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionMetadataSettingsFragment : Fragment(R.layout.fragment_transaction_metadata_settings) {
    private val viewModel: TransactionMetadataSettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                TransactionMetadataSettingsScreen(onBackClick = {
                    findNavController().popBackStack()
                }, onInfoButtonClick = {
                    // info button
                    safeNavigate(TransactionMetadataSettingsFragmentDirections.toInfoDialog(
                        firstTime = false,
                        useNavigation = true
                    ))
                },
                onSaveToNetwork = {
                    saveToNetwork()
                }, viewModel
                )
            }
        }
    }

    private fun saveToNetwork() {
        // ask the user
        val hasTxs = viewModel.hasPastTransactionsToSave.value
        val settings = viewModel.filterState.value
        val lastSaveDate = if (viewModel.lastSaveDate.value != 0L) {
            SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM).format(viewModel.lastSaveDate.value)
        } else {
            SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM).format(viewModel.firstUnsavedTxDate)
        }
        val notSaving = !(settings.saveToNetwork || settings.savePastTxToNetwork)
        if (hasTxs && notSaving) {
            AdaptiveDialog.create(
                null,
                getString(R.string.transaction_metadata_save_new_tx_title),
                getString(
                    R.string.transaction_metadata_you_have_n_tx,
                    viewModel.unsavedTxCount, // TODO: placeholder for actual tx count
                    lastSaveDate
                ),
                getString(R.string.transaction_metadata_save_transactions),
                getString(R.string.transaction_metadata_continue_without_saving)
            ).show(requireActivity()) { saveTxs ->
                if (saveTxs == false) {
                    viewModel.saveToNetwork(true)
                    findNavController().popBackStack()
                } else if (saveTxs == true) {
                    viewModel.saveToNetwork(false)
                    findNavController().popBackStack()
                }
            }
        } else {
            // save to network
            viewModel.saveToNetwork(false)
            findNavController().popBackStack()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            viewModel.loadLastWorkId()
        }
    }
}