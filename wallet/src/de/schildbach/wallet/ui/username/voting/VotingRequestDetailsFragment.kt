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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentVotingRequestDetailsBinding
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate

class VotingRequestDetailsFragment : Fragment(R.layout.fragment_voting_request_details) {
    private val binding by viewBinding(FragmentVotingRequestDetailsBinding::bind)
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }
        // TODO Mock identity
        binding.identity.text = "90f95ff7bc2438a748dc8470255b888b2a9ea6837bf518d018dc3d6cddf698"

        lifecycleScope.launchWhenCreated {
            binding.username.text =
                requestUserNameViewModel.dashPayConfig.get(DashPayConfig.REQUESTED_USERNAME)

            val link = requestUserNameViewModel.dashPayConfig.get(DashPayConfig.REQUESTED_USERNAME_LINK)

            binding.link.isVisible = link.isNullOrEmpty().not()
            binding.verfiyNowLayout.isVisible = link.isNullOrEmpty()

            binding.link.text = link
        }

        binding.verfiyNow.setOnClickListener {
            safeNavigate(
                VotingRequestDetailsFragmentDirections.votingRequestDetailsFragmentToConfirmUsernameRequestDialog()
            )
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
