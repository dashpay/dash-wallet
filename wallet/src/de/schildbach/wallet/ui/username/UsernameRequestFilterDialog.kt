/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogUsernameRequestFiltersBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.radio_group.setupRadioGroup
import org.dash.wallet.common.ui.viewBinding

enum class UsernameGroupOption {
    VotingPeriodSoonest,
    VotingPeriodLatest;

    companion object {
        val defaultOption = VotingPeriodSoonest
    }
}

enum class UsernameSortOption {
    DateDescending,
    DateAscending,
    VotesDescending,
    VotesAscending;

    companion object {
        val defaultOption = DateAscending
    }
}

enum class UsernameTypeOption {
    All,
    Approved,
    NotApproved,
    HasBlockedVotes;

    companion object {
        val defaultOption = NotApproved
    }
}

@AndroidEntryPoint
class UsernameRequestFilterDialog : OffsetDialogFragment(R.layout.dialog_username_request_filters) {
    override val backgroundStyle = R.style.PrimaryBackground

    private val binding by viewBinding(DialogUsernameRequestFiltersBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()

    private lateinit var typeOptionsAdapter: RadioGroupAdapter
    private lateinit var sortByOptionsAdapter: RadioGroupAdapter
    private lateinit var groupByOptionsAdapter: RadioGroupAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.applyButton.setOnClickListener {
            applyFilters()
            dismiss()
        }

        setupSortByOptions()
        setupTypeOptions()
        setupCheckBoxes()
        checkResetButton()

        binding.onlyDuplicatesCheckbox.setOnCheckedChangeListener { _, _ -> checkResetButton() }
        binding.onlyLinksCheckbox.setOnCheckedChangeListener { _, _ -> checkResetButton() }
        binding.resetFiltersBtn.setOnClickListener { resetFilters() }
        binding.collapseButton.setOnClickListener { dismiss() }
    }

    private fun setupSortByOptions() {

        val sortByOptionNames = binding.root.resources.getStringArray(R.array.usernames_sort_by_options).mapIndexed { i, it ->
            IconifiedViewItem(getString(if (i < 2) R.string.date else R.string.votes, it))

        }

        val groupByOptionNames = binding.root.resources.getStringArray(R.array.usernames_group_by_options).mapIndexed { i, it ->
            IconifiedViewItem(
                getString(R.string.voting_ends, it)
            )
        }

        val groupOption = viewModel.filterState.value.groupByOption
        val initialGroupIndex = UsernameGroupOption.entries.indexOf(groupOption)
        val groupAdapter = RadioGroupAdapter(initialGroupIndex) { _, _ ->
            checkResetButton()
        }
        binding.groupByFilter.setupRadioGroup(groupAdapter)
        groupAdapter.submitList(groupByOptionNames)
        groupByOptionsAdapter = groupAdapter

        val sortOption = viewModel.filterState.value.sortByOption
        val initialIndex = UsernameSortOption.entries.indexOf(sortOption)
        val adapter = RadioGroupAdapter(initialIndex) { _, _ ->
            checkResetButton()
        }
        binding.sortByFilter.setupRadioGroup(adapter)
        adapter.submitList(sortByOptionNames)
        sortByOptionsAdapter = adapter
    }

    private fun setupTypeOptions() {
        val optionNames = binding.root.resources.getStringArray(R.array.usernames_type_options).map {
            IconifiedViewItem(it)
        }

        val typeOption = viewModel.filterState.value.typeOption
        val initialIndex = UsernameTypeOption.values().indexOf(typeOption)
        val adapter = RadioGroupAdapter(initialIndex) { _, _ ->
            checkResetButton()
        }
        binding.typeFilter.setupRadioGroup(adapter)
        adapter.submitList(optionNames)
        typeOptionsAdapter = adapter
    }

    private fun setupCheckBoxes() {
        binding.onlyDuplicatesCheckbox.isChecked = viewModel.filterState.value.onlyDuplicates
        binding.onlyLinksCheckbox.isChecked = viewModel.filterState.value.onlyLinks
    }

    private fun applyFilters() {
        val groupByOption = UsernameGroupOption.entries[groupByOptionsAdapter.selectedIndex]
        val sortByOption = UsernameSortOption.entries[sortByOptionsAdapter.selectedIndex]
        val typeOption = UsernameTypeOption.entries[typeOptionsAdapter.selectedIndex]
        val onlyDuplicates = binding.onlyDuplicatesCheckbox.isChecked
        val onlyLinks = binding.onlyLinksCheckbox.isChecked

        viewModel.applyFilters(groupByOption, sortByOption, typeOption, onlyDuplicates, onlyLinks)
    }

    private fun checkResetButton() {
        var isEnabled = false

        if (groupByOptionsAdapter.selectedIndex != UsernameGroupOption.defaultOption.ordinal ||
            sortByOptionsAdapter.selectedIndex != UsernameSortOption.defaultOption.ordinal ||
            typeOptionsAdapter.selectedIndex != UsernameTypeOption.defaultOption.ordinal
        ) {
            isEnabled = true
        }

        if (!binding.onlyDuplicatesCheckbox.isChecked) {
            isEnabled = true
        }

        if (binding.onlyLinksCheckbox.isChecked) {
            isEnabled = true
        }

        binding.resetFiltersBtn.isEnabled = isEnabled
    }

    private fun resetFilters() {
        groupByOptionsAdapter.selectedIndex = UsernameGroupOption.defaultOption.ordinal
        sortByOptionsAdapter.selectedIndex = UsernameSortOption.defaultOption.ordinal
        typeOptionsAdapter.selectedIndex = UsernameTypeOption.defaultOption.ordinal
        binding.onlyDuplicatesCheckbox.isChecked = true
        binding.onlyLinksCheckbox.isChecked = false
        binding.resetFiltersBtn.isEnabled = false
    }
}
