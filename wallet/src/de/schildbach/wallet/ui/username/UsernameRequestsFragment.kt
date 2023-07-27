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
import android.view.ViewTreeObserver
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupAdapter
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupView
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRequestsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class UsernameRequestsFragment : Fragment(R.layout.fragment_username_requests) {
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val binding by viewBinding(FragmentUsernameRequestsBinding::bind)
    private var itemList = listOf<UsernameRequestGroupView>()
    private lateinit var keyboardUtil: KeyboardUtil

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = this.binding
        binding.toolbar.setOnClickListener {
            viewModel.prepopulateList()
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.filterBtn.setOnClickListener {
            safeNavigate(UsernameRequestsFragmentDirections.usernameRequestsToFilters())
        }
        val adapter = UsernameRequestGroupAdapter()
        binding.requestGroups.adapter = adapter

        binding.search.setOnFocusChangeListener { _, isFocused ->
            if (isFocused) {
                binding.mainScroll.viewTreeObserver.addOnGlobalLayoutListener(
                    object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            binding.mainScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            binding.mainScroll.smoothScrollBy(
                                0,
                                resources.getDimensionPixelOffset(R.dimen.username_search_focused_scroll)
                            )
                        }
                    }
                )
            }
        }
        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            adapter.submitList(filterByQuery(itemList, text.toString()))
        }
        binding.clearBtn.setOnClickListener { binding.search.text.clear() }
        binding.appliedFiltersPanel.setOnClickListener {
            safeNavigate(UsernameRequestsFragmentDirections.usernameRequestsToFilters())
        }

        keyboardUtil = KeyboardUtil(requireActivity().window, binding.root)
        keyboardUtil.setOnKeyboardShownChanged { isShown ->
            binding.appliedFiltersPanel.isVisible = !viewModel.filterState.value.isDefault() && !isShown
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state.showFirstTimeInfo) {
                lifecycleScope.launch {
                    delay(200)
                    AdaptiveDialog.create(
                        R.drawable.ic_user_list,
                        getString(R.string.voting_duplicates_only_title),
                        getString(R.string.voting_duplicates_only_message),
                        getString(R.string.button_ok)
                    ).showAsync(requireActivity())
                    viewModel.setFirstTimeInfoShown()
                }
            }

            binding.filterSubtitle.text = getString(R.string.n_usernames, state.filteredUsernameRequests.size)
            binding.filterSubtitle.isVisible = state.filteredUsernameRequests.isNotEmpty()
            binding.searchPanel.isVisible = state.filteredUsernameRequests.isNotEmpty()
            binding.noItemsTxt.isVisible = state.filteredUsernameRequests.isEmpty()

            itemList = state.filteredUsernameRequests
            val list = filterByQuery(itemList, binding.search.text.toString())
            val layoutManager = binding.requestGroups.layoutManager as LinearLayoutManager
            val scrollPosition = layoutManager.findFirstVisibleItemPosition()
            adapter.submitList(list)
            binding.requestGroups.scrollToPosition(scrollPosition)
        }

        viewModel.filterState.observe(viewLifecycleOwner) { state ->
            val isDefault = state.isDefault()
            binding.appliedFiltersPanel.isVisible = !isDefault && !keyboardUtil.isKeyboardShown
            val typeOptionNames = binding.root.resources.getStringArray(R.array.usernames_type_options)
            binding.filterTitle.text = typeOptionNames[state.typeOption.ordinal]

            if (!isDefault) {
                populateAppliedFilters(state)
            }
        }
    }

    private fun filterByQuery(items: List<UsernameRequestGroupView>, query: String?): List<UsernameRequestGroupView> {
        if (query.isNullOrEmpty()) {
            return items
        }

        return items.filter { it.username.startsWith(query, true) }
    }

    private fun populateAppliedFilters(state: FiltersUIState) {
        val sortByOptionNames = binding.root.resources.getStringArray(R.array.usernames_sort_by_options)
        val appliedFilterNames = mutableListOf<String>()

        if (state.sortByOption != UsernameSortOption.DateDescending) {
            appliedFilterNames.add(sortByOptionNames[state.sortByOption.ordinal])
        }

        if (state.onlyDuplicates) {
            appliedFilterNames.add(getString(R.string.only_duplicates))
        }

        if (state.onlyLinks) {
            appliedFilterNames.add(getString(R.string.only_links_short))
        }

        binding.filteredByTxt.text = appliedFilterNames.joinToString(", ")
    }
}


