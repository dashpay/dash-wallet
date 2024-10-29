package de.schildbach.wallet.ui.username

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet.ui.username.utils.votingViewModels
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRequestDetailsBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
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
                binding.link.isVisible = false
                binding.link.text = getString(R.string.link_not_provided)
                binding.link.setTextColor(resources.getColor(R.color.content_tertiary, null))
            } else {
                binding.link.isVisible = true
                binding.link.text = request.link
            }

            binding.voteButton.setRoundedRippleBackground(
                if (request.isApproved) R.style.PrimaryButtonTheme_Large_Red else R.style.PrimaryButtonTheme_Large_Blue
            )

            binding.voteButtonText.text = getString(
                if (request.isApproved) R.string.cancel_approval else R.string.vote_to_approve
            )

            viewModel.observeVotesCount(request.normalizedLabel).observe(viewLifecycleOwner) { myVoteCount ->
                binding.voteButton.isEnabled = (myVoteCount < UsernameVote.MAX_VOTES)
            }

            binding.voteButton.setOnClickListener {
                lifecycleScope.launch {
                    val usernameVotes = viewModel.getVotes(request.normalizedLabel)
                    when {
                        (usernameVotes.size == UsernameVote.MAX_VOTES - 1) -> {
                            AdaptiveDialog.create(
                                icon = null,
                                getString(R.string.username_vote_one_left),
                                getString(R.string.username_vote_one_left_message, UsernameVote.MAX_VOTES - 1),
                                getString(R.string.cancel),
                                getString(R.string.button_ok)
                            ).show(requireActivity()) {
                                if (it == true) {
                                    lifecycleScope.launch { doVote(request) }
                                }
                            }
                        }

                        // NOT IN THE DESIGN
                        usernameVotes.size == UsernameVote.MAX_VOTES -> {
                            AdaptiveDialog.create(
                                icon = null,
                                getString(R.string.username_vote_none_left),
                                getString(R.string.username_vote_none_left_message, UsernameVote.MAX_VOTES),
                                getString(R.string.button_ok)
                            ).showAsync(requireActivity())
                        }

                        else -> {
                            lifecycleScope.launch { doVote(request) }
                        }
                    }
                }
            }
        }

        viewModel.selectUsernameRequest(args.requestId)
    }

    private suspend fun doVote(request: UsernameRequest) {
        val lastVote = viewModel.getVotes(request.normalizedLabel).lastOrNull()
        val voteType = when {
            lastVote == null -> UsernameVote.APPROVE
            lastVote.type == UsernameVote.APPROVE && lastVote.identity == request.identity -> UsernameVote.ABSTAIN
            else -> UsernameVote.APPROVE
        }
        if (viewModel.shouldMaybeAskForMoreKeys()) {
            if (viewModel.keysAmount > 0) {
                safeNavigate(
                    UsernameRequestDetailsFragmentDirections.detailsToAddKeys(
                        args.requestId,
                        voteType
                    )
                )
            } else {
                safeNavigate(
                    UsernameRequestDetailsFragmentDirections.detailsToVotingKeyInput(
                        args.requestId,
                        voteType
                    )
                )
            }
        } else {
            viewModel.submitVote(request.requestId, voteType)
        }
    }
}
