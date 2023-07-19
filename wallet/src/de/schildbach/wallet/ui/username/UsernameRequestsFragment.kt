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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.adapters.UsernameRequestGroupAdapter
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
    private val viewModel: UsernameRequestsViewModel by viewModels()
    private val binding by viewBinding(FragmentUsernameRequestsBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.filterBtn.setOnClickListener {
//            viewModel.prepopulateList()
            safeNavigate(UsernameRequestsFragmentDirections.usernameRequestsToFilters())
        }
        val adapter = UsernameRequestGroupAdapter()
        binding.requestGroups.adapter = adapter

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

            adapter.submitList(state.usernameRequests)
            binding.filterSubtitle.text = getString(R.string.n_duplicates, state.usernameRequests.size)
            binding.filterSubtitle.isVisible = state.usernameRequests.isNotEmpty()
            binding.searchPanel.isVisible = state.usernameRequests.isNotEmpty()
            binding.noItemsTxt.isVisible = state.usernameRequests.isEmpty()
        }
    }
}
