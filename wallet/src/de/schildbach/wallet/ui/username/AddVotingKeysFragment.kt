package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.ui.username.adapters.IPAddressAdapter
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentAddVotingKeysBinding
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

        binding.submitButton.setOnClickListener {
            viewModel.vote(args.requestId)
            findNavController().popBackStack(R.id.usernameRequestsFragment, false)
        }

        binding.addKeyButton.setOnClickListener {
            safeNavigate(AddVotingKeysFragmentDirections.addKeysToVotingKeyInput(args.requestId))
        }

        val adapter = IPAddressAdapter()
        binding.ipAddresses.adapter = adapter

        viewModel.masternodeIPs.observe(viewLifecycleOwner) {
            adapter.submitList(it)
            binding.votesCastText.text = getString(R.string.multiple_votes_cast, it.size)
        }
    }
}
