package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRequestDetailsBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

class UsernameRequestDetailsFragment : Fragment(R.layout.fragment_username_request_details) {
    private val binding by viewBinding(FragmentUsernameRequestDetailsBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val args by navArgs<UsernameRequestDetailsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.voteButton.setOnClickListener {
            Toast.makeText(requireContext(), "Not implemented", Toast.LENGTH_SHORT).show()
        }

        viewModel.selectedUsernameRequest.observe(viewLifecycleOwner) {
            binding.username.text = it.username
            binding.identity.text = it.identity

            if (it.link.isNullOrEmpty()) {
                binding.link.text = getString(R.string.link_not_provided)
                binding.link.setTextColor(resources.getColor(R.color.content_tertiary, null))
            } else {
                binding.link.text = it.link
            }
        }

        viewModel.selectUsernameRequest(args.requestId)
    }
}
