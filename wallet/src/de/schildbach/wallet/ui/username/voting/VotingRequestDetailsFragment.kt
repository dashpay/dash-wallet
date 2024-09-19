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
import android.text.format.DateFormat
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVotingRequestDetailsBinding
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import java.util.concurrent.TimeUnit


class VotingRequestDetailsFragment : Fragment(R.layout.fragment_voting_request_details) {
    private val binding by viewBinding(FragmentVotingRequestDetailsBinding::bind)
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.verify.setOnClickListener {
            safeNavigate(
                VotingRequestDetailsFragmentDirections
                    .votingRequestDetailsFragmentToVerifyIdentityFragment(username = binding.username.text.toString())
            )
        }

        requestUserNameViewModel.myUsernameRequest.observe(viewLifecycleOwner) { myUsernameRequest ->
            binding.username.text = myUsernameRequest?.username
            binding.identity.text = myUsernameRequest?.identity
            var isVotingOver = false
            val votingResults = myUsernameRequest?.createdAt?.let { startTime ->
                val endTime = startTime + if (Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET) TimeUnit.DAYS.toMillis(14) else TimeUnit.MINUTES.toMillis(90)
                val dateFormat = DateFormat.getMediumDateFormat(requireContext())
                isVotingOver = endTime < System.currentTimeMillis()
                if (isVotingOver) {
                    getString(R.string.request_username_taken_results)
                } else {
                    dateFormat.format(endTime)
                }
            } ?: "Voting Period not found"
            binding.votingRange.text = votingResults
            when {
                isVotingOver -> {
                    binding.link.text = if (myUsernameRequest?.link != null && myUsernameRequest.link != "") {
                        myUsernameRequest.link
                    } else {
                        getString(R.string.none)
                    }
                    binding.linkLayout.isVisible = true
                    binding.verifyNowLayout.isVisible = false
                }
                myUsernameRequest?.link != null && myUsernameRequest.link != "" -> {
                    binding.link.text = myUsernameRequest.link
                    binding.linkLayout.isVisible = true
                    binding.verifyNowLayout.isVisible = false
                }
                else -> {
                    binding.linkLayout.isVisible = false
                    binding.verifyNowLayout.isVisible = true
                }
            }
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
                VotingRequestDetailsFragmentDirections.votingRequestDetailsFragmentToUsernameVotingInfoFragment(true)
            )
        }
    }
}
