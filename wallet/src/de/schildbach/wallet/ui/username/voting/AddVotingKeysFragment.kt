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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.ui.username.adapters.IPAddressAdapter
import de.schildbach.wallet.ui.username.adapters.MasternodeEntry
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAddVotingKeysBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.ui.setRoundedRippleBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.slf4j.LoggerFactory

class AddVotingKeysFragment : Fragment(R.layout.fragment_add_voting_keys) {
    companion object {
        private val log = LoggerFactory.getLogger(AddVotingKeysFragment::class.java)
    }

    private val binding by viewBinding(FragmentAddVotingKeysBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val args by navArgs<AddVotingKeysFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        viewModel.masternodes.observe(viewLifecycleOwner) {
            binding.submitButton.isEnabled = it.isNotEmpty()
        }
        binding.submitButton.setRoundedRippleBackground(
            if (args.vote != UsernameVote.APPROVE) R.style.PrimaryButtonTheme_Large_Red else R.style.PrimaryButtonTheme_Large_Blue
        )
        binding.submitButton.setOnClickListener {
            viewModel.submitVote(args.requestId, args.vote)
            findNavController().popBackStack(R.id.usernameRequestsFragment, false)
        }

        binding.addKeyButton.setOnClickListener {
            safeNavigate(AddVotingKeysFragmentDirections.addKeysToVotingKeyInput(args.requestId, args.vote))
        }

        val adapter = IPAddressAdapter() { masternodeIP ->
            log.info("removing masternode: {}", masternodeIP)
            viewModel.removeMasternode(masternodeIP)
        }
        binding.ipAddresses.adapter = adapter

        viewModel.masternodes.observe(viewLifecycleOwner) {
            log.info("updating masternode list: {}", it.map { mn -> mn.address })
            adapter.submitList(
                it.map { masternode ->
                    val canDelete = viewModel.isImported(masternode)
                    MasternodeEntry(masternode.address, canDelete)
                }
            )
            binding.votesCastText.text = getString(R.string.multiple_votes_cast, it.size)
        }

        binding.dontAskAgainButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.setDontAskAgain()
            }
            binding.dontAskAgainButton.isVisible = false
        }
        lifecycleScope.launch {
            val isFirstTime = withContext(Dispatchers.IO) { viewModel.isFirstTimeVoting() }
            binding.dontAskAgainButton.isVisible = !isFirstTime
            withContext(Dispatchers.IO) { viewModel.setSecondTimeVoting() }
        }
    }
}
