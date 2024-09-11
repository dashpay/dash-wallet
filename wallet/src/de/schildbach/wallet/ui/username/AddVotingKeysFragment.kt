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

        binding.submitButton.setRoundedRippleBackground(
            if (!args.approve) R.style.PrimaryButtonTheme_Large_Red else R.style.PrimaryButtonTheme_Large_Blue
        )
        binding.submitButton.setOnClickListener {
            if (args.approve) {
                viewModel.vote(args.requestId)
            } else {
                viewModel.block(args.requestId)
            }
            findNavController().popBackStack(R.id.usernameRequestsFragment, false)
        }

        binding.addKeyButton.setOnClickListener {
            safeNavigate(AddVotingKeysFragmentDirections.addKeysToVotingKeyInput(args.requestId, args.approve))
        }

        val adapter = IPAddressAdapter()
        binding.ipAddresses.adapter = adapter

        viewModel.masternodes.observe(viewLifecycleOwner) {
            adapter.submitList(it.map { masternode->
                masternode.address
            })
            binding.votesCastText.text = getString(R.string.multiple_votes_cast, it.size)
        }
    }
}
