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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVotingRequestDetailsBinding
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate


class VotingRequestDetailsFragment : Fragment(R.layout.fragment_voting_request_details) {
    private val binding by viewBinding(FragmentVotingRequestDetailsBinding::bind)
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    override fun onResume() {
        super.onResume()
        // Developer Mode Feature

        lifecycleScope.launchWhenResumed {
            binding.username.text =
                requestUserNameViewModel.identityConfig.get(BlockchainIdentityConfig.REQUESTED_USERNAME)

            requestUserNameViewModel.requestedUserNameLink.observe(viewLifecycleOwner) {
                it?.let { link ->
                    binding.link.text = link
                    binding.linkLayout.isVisible = link.isEmpty().not()
                    binding.verfiyNowLayout.isVisible = link.isEmpty()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }
        // TODO Mock identity
        binding.identity.text = "90f95ff7bc2438a748dc8470255b888b2a9ea6837bf518d018dc3d6cddf698"
        binding.votingRange.text = "1 Mar – 15 Mar"
        binding.votesNumber.text = getString(R.string.votes, "10")

        binding.verfiyNow.setOnClickListener {
            safeNavigate(
                VotingRequestDetailsFragmentDirections
                    .votingRequestDetailsFragmentToVerifyIdentityFragment(username = binding.username.text.toString())
            )
        }


        binding.linkLayout.setOnClickListener {
            requestUserNameViewModel.requestedUserNameLink.value?.let {
                val browserIntent =
                    Intent()
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(it))

                if (browserIntent.resolveActivity(requireActivity().packageManager) != null) {
                    this.requireContext().startActivity(browserIntent)
                }else{
                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(R.string.cant_open),
                        getString(R.string.invalid_link),
                        getString(android.R.string.ok),
                        ""
                    ).show(requireActivity())
                }

            }
        }

        binding.ivInfo.setOnClickListener {
            safeNavigate(
                VotingRequestDetailsFragmentDirections.votingRequestDetailsFragmentToUsernameVotingInfoFragment()
            )
        }

        binding.cancelRequestButton.setOnClickListener {
            AdaptiveDialog.create(
                R.drawable.ic_warning,
                title = getString(R.string.do_you_really_want_to_cancel),
                message = getString(R.string.if_you_tap_cancel_request),
                positiveButtonText = getString(R.string.cancel_request),
                negativeButtonText = getString(android.R.string.cancel)
            ).show(requireActivity()) {
                if (it == true) {
                    requestUserNameViewModel.cancelRequest()
                    requireActivity().finish()
                }
            }
            findNavController().popBackStack()
        }
    }
}
