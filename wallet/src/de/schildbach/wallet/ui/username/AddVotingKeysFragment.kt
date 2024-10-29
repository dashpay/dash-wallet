package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.ui.username.adapters.IPAddressAdapter
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

class AddVotingKeysFragment : Fragment(R.layout.fragment_add_voting_keys) {
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

        val adapter = IPAddressAdapter() {masternodeIp ->
            viewModel.removeMasternode(masternodeIp)
        }
        binding.ipAddresses.adapter = adapter

        viewModel.masternodes.observe(viewLifecycleOwner) {
            adapter.submitList(it.map { masternode->
                masternode.address
            })
            binding.votesCastText.text = getString(R.string.multiple_votes_cast, it.size)
        }

        binding.dontAskAgainButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.setDontAskAgain()
            }
            binding.dontAskAgainButton.isVisible = true
        }
        lifecycleScope.launch {
            val isFirstTime = withContext(Dispatchers.IO) { viewModel.isFirstTimeVoting() }
            binding.dontAskAgainButton.isVisible = !isFirstTime
            viewModel.setSecondTimeVoting()
        }
    }
}
