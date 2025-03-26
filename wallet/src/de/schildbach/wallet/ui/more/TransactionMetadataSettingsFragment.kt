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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentTransactionMetadataSettingsBinding
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.radio_group.IconSelectMode
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.radio_group.setupRadioGroup
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class TransactionMetadataSettingsFragment : Fragment(R.layout.fragment_transaction_metadata_settings) {
    private val binding by viewBinding(FragmentTransactionMetadataSettingsBinding::bind)
    private val viewModel: TransactionMetadataSettingsViewModel by viewModels()
    private val args by navArgs<TransactionMetadataSettingsFragmentArgs>()
    private lateinit var saveFrequencyOptionsAdapter: RadioGroupAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = getString(R.string.transaction_metadata_title)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        setupSaveFrequencyOptions()

        binding.quickVoteButton.setOnClickListener {
            safeNavigate(TransactionMetadataSettingsFragmentDirections.toInfoDialog(false, true))
        }
        binding.saveDataSwitch.setOnClickListener {
            lifecycleScope.launch {
                viewModel.saveDataToNetwork(binding.saveDataSwitch.isChecked)
            }
        }
        viewModel.saveToNetwork.filterNotNull().observe(viewLifecycleOwner) { checked ->
            binding.saveDataSwitch.isChecked = checked
            binding.settingsContainer.isVisible = checked
        }
        viewModel.filterState.observe(viewLifecycleOwner) {
            binding.paymentCategoriesCheck.isChecked = it.savePaymentCategory
            binding.taxCategoriesCheck.isChecked = it.saveTaxCategory
            binding.fiatPricesCheck.isChecked = it.saveExchangeRates
            binding.privateMemosCheck.isChecked = it.savePrivateMemos
            saveFrequencyOptionsAdapter.selectedIndex = TxMetadataSaveFrequency.entries.indexOf(viewModel.filterState.value.saveFrequency)
        }

        binding.paymentCategoriesCheck.setOnClickListener {
            savePreferences(viewModel.filterState.value.copy(savePaymentCategory = binding.paymentCategoriesCheck.isChecked))
        }
        binding.taxCategoriesCheck.setOnClickListener {
            savePreferences(viewModel.filterState.value.copy(saveTaxCategory = binding.taxCategoriesCheck.isChecked))
        }
        binding.fiatPricesCheck.setOnClickListener {
            savePreferences(viewModel.filterState.value.copy(saveExchangeRates = binding.fiatPricesCheck.isChecked))
        }
        binding.privateMemosCheck.setOnClickListener {
            savePreferences(viewModel.filterState.value.copy(savePrivateMemos = binding.privateMemosCheck.isChecked))
        }

        if (args.turnOnSaveData) {
            lifecycleScope.launch {
                viewModel.saveDataToNetwork(true)
            }
        }
    }

    private fun setupSaveFrequencyOptions() {
        val sortByOptionNames = binding.root.resources.getStringArray(R.array.transaction_metadata_save_frequency)
            .mapIndexed { _, it ->
                IconifiedViewItem(it, iconSelectMode = IconSelectMode.None)
            }

        val saveFrequency = viewModel.filterState.value.saveFrequency
        val initialIndex = TxMetadataSaveFrequency.entries.indexOf(saveFrequency)
        val adapter = RadioGroupAdapter(initialIndex) { _, _ ->
            savePreferences(viewModel.filterState.value.copy(saveFrequency = TxMetadataSaveFrequency.entries[saveFrequencyOptionsAdapter.selectedIndex]))
        }
        // TODO: we can customize the appearance with a new extension fun instead of this:
        binding.sortByFilter.setupRadioGroup(adapter, false)
        adapter.submitList(sortByOptionNames)
        saveFrequencyOptionsAdapter = adapter
    }

    private fun savePreferences(settings: TransactionMetadataSettings) {
        lifecycleScope.launch {
            viewModel.savePreferences(settings)
        }
    }
}