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

package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.view.View
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogQuickVoteBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

class QuickVoteDialogFragment: OffsetDialogFragment(R.layout.dialog_quick_vote) {
    private val binding by viewBinding(DialogQuickVoteBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.voteBtn.setOnClickListener {
            viewModel.voteForAll()
            dismiss()
        }

        binding.dismissBtn.setOnClickListener { dismiss() }

        viewModel.uiState.observe(viewLifecycleOwner) {
            binding.subtitle.text = getString(R.string.quick_vote_subtitle, it.filteredUsernameRequests.size)
        }
    }
}
