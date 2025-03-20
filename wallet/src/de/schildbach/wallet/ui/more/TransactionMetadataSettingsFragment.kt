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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentTransactionMetadataSettingsBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class TransactionMetadataSettingsFragment : Fragment(R.layout.fragment_transaction_metadata_settings) {
    private val binding by viewBinding(FragmentTransactionMetadataSettingsBinding::bind)
    private val viewModel: TransactionMetadataSettingsViewModel by viewModels()
    private val args by navArgs<TransactionMetadataSettingsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = getString(R.string.transaction_metadata_title)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.quickVoteButton.setOnClickListener {
            safeNavigate(TransactionMetadataSettingsFragmentDirections.toInfoDialog(false))
        }
        viewModel.saveToNetwork.observe(viewLifecycleOwner) {

        }

        if (args.turnOnSaveData) {
            lifecycleScope.launch {
                viewModel.saveDataToNetwork(true)
            }
        }
    }
}