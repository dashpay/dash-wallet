package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRequestDetailsBinding
import org.dash.wallet.common.ui.setRoundedRippleBackground
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

@AndroidEntryPoint
class UsernameRequestDetailsFragment : Fragment(R.layout.fragment_username_request_details) {
    private val binding by viewBinding(FragmentUsernameRequestDetailsBinding::bind)
    private val viewModel by votingViewModels<UsernameRequestsViewModel>()
    private val args by navArgs<UsernameRequestDetailsFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        viewModel.selectedUsernameRequest.observe(viewLifecycleOwner) { request ->
            binding.username.text = request.username
            binding.identity.text = request.identity

            if (request.link.isNullOrEmpty()) {
                binding.link.text = getString(R.string.link_not_provided)
                binding.link.setTextColor(resources.getColor(R.color.content_tertiary, null))
            } else {
                binding.link.text = request.link
            }

            binding.voteButton.setRoundedRippleBackground(
                if (request.isApproved) R.style.PrimaryButtonTheme_Large_Red else R.style.PrimaryButtonTheme_Large_Blue
            )

            binding.voteButtonText.setTextColor(
                resources.getColor(
                    if (request.isApproved) R.color.system_red else R.color.dash_white,
                    null
                )
            )

            binding.voteButtonText.text = getString(
                if (request.isApproved) R.string.cancel_approval else R.string.vote_to_approve
            )

            binding.voteButton.setOnClickListener {
                if (request.isApproved) {
                    viewModel.revokeVote(args.requestId)
                    findNavController().popBackStack(R.id.usernameRequestsFragment, false)
                } else {
                    if (viewModel.keysAmount > 0) {
                        safeNavigate(UsernameRequestDetailsFragmentDirections.detailsToAddKeys(args.requestId))
                    } else {
                        safeNavigate(UsernameRequestDetailsFragmentDirections.detailsToVotingKeyInput(args.requestId))
                    }
                }
            }
        }

        viewModel.selectUsernameRequest(args.requestId)
    }
}
