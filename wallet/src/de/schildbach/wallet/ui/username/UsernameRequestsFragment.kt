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
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class UsernameRequestsFragment : Fragment(R.layout.fragment_username_requests) {
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val binding by viewBinding(FragmentUsernameRequestsBinding::bind)
    private var itemList = listOf<UsernameRequestGroupView>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setOnClickListener {
            viewModel.prepopulateList()
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.filterBtn.setOnClickListener {
            safeNavigate(UsernameRequestsFragmentDirections.usernameRequestsToFilters())
        }
        val adapter = UsernameRequestGroupAdapter()
        binding.requestGroups.adapter = adapter

        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            adapter.submitList(filterByQuery(itemList, text.toString()))
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

            binding.filterSubtitle.text = getString(R.string.n_duplicates, state.usernameRequests.size)
            binding.filterSubtitle.isVisible = state.usernameRequests.isNotEmpty()
            binding.searchPanel.isVisible = state.usernameRequests.isNotEmpty()
            binding.noItemsTxt.isVisible = state.usernameRequests.isEmpty()

            itemList = state.usernameRequests
            val list = filterByQuery(itemList, binding.search.text.toString())
            val layoutManager = binding.requestGroups.layoutManager as LinearLayoutManager
            val scrollPosition = layoutManager.findFirstVisibleItemPosition()
            adapter.submitList(list)
            binding.requestGroups.scrollToPosition(scrollPosition)
        }
    }

    private fun filterByQuery(items: List<UsernameRequestGroupView>, query: String?): List<UsernameRequestGroupView> {
        if (query.isNullOrEmpty()) {
            return items
        }

        return items.filter { it.username.startsWith(query, true) }
    }
}
