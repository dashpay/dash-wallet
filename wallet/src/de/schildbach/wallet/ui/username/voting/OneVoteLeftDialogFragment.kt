/*
 * Copyright 2024 Dash Core Group.
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
import androidx.core.view.isVisible
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogOneVoteLeftBinding
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

class OneVoteLeftDialogFragment: OffsetDialogFragment(R.layout.dialog_one_vote_left) {
    companion object {
        private const val USERNAME_ARG = "usernames"
        fun newInstance(usernames: List<String>): OneVoteLeftDialogFragment{
            val args = Bundle()
            args.putStringArray(USERNAME_ARG, usernames.toTypedArray())
            val fragment = OneVoteLeftDialogFragment()
            fragment.arguments = args
            return fragment
        }
    }
    private val binding by viewBinding(DialogOneVoteLeftBinding::bind)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.okButton.setOnClickListener { dismiss() }

        binding.dismissBtn.setOnClickListener { dismiss() }

        requireArguments().apply {
            val usernameList = getStringArray(USERNAME_ARG)!!
            if (usernameList.size > 1) {
                // hide all the names behind the "Show usernames" button
                binding.showUsernames.isVisible = true
                binding.usernameList.isVisible = false
                binding.usernameList.text = usernameList.joinToString(", ")
            } else {
                // if there is only one username, then show it
                binding.showUsernames.isVisible = false
                binding.usernameList.isVisible = true
                binding.usernameList.text = usernameList.first()
            }
            binding.subtitle.text = getString(R.string.one_vote_left_subtitle, UsernameVote.MAX_VOTES)
        }

        binding.showUsernames.setOnClickListener {
            binding.showUsernames.isVisible = false
            binding.usernameList.isVisible = true
        }
    }
}
