/*
 * Copyright 2024 Dash Core Group
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

package de.schildbach.wallet.ui.username

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.username.voting.RequestUserNameViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentUsernameRegistrationBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.viewBinding
import org.dashj.platform.sdk.platform.Names


@AndroidEntryPoint
@ExperimentalCoroutinesApi
class UsernameRegistrationFragment : Fragment(R.layout.fragment_username_registration) {
    private val binding by viewBinding(FragmentUsernameRegistrationBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUsernameViewModel: RequestUserNameViewModel by activityViewModels()

    private val slideInAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.slide_in_bottom) }
    private val fadeOutAnimation by lazy { AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out) }
    private lateinit var completeUsername: String
    private var isProcessing = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chooseUsernameTitle.text = getText(R.string.choose_your_username)
        binding.closeBtn.setOnClickListener {
            closeActivity()
        }
        binding.processingIdentityDismissBtn.setOnClickListener { closeActivity() }

        initViewModel()
    }

    @SuppressLint("ResourceType")
    private fun closeActivity() {
        if (requestUsernameViewModel.isUsernameContestable()) {
            startActivity(MainActivity.createIntent(requireContext(), R.id.moreFragment))
        } else {
            // go to the home screen
            startActivity(MainActivity.createIntent(requireContext(), R.id.walletFragment))
        }
        requireActivity().finish()
    }

    private fun initViewModel() {
        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            completeUsername = it?.username ?: ""
            when {
                it?.creationStateErrorMessage != null -> {
                    requireActivity().finish()
                }

                it?.creationState == BlockchainIdentityData.CreationState.DONE ||
                        it?.creationState == BlockchainIdentityData.CreationState.VOTING -> {
                    showCompleteState()
                }

                it?.creationState?.ordinal in BlockchainIdentityData.CreationState.UPGRADING_WALLET.ordinal until BlockchainIdentityData.CreationState.DONE.ordinal -> {
                    showProcessingState()
                }
            }
        }
    }

    private fun showCompleteState() {
        binding.processingIdentity.visibility = View.GONE
        binding.chooseUsernameTitle.visibility = View.GONE
        binding.orbitView.findViewById<View>(R.id.placeholder_user_icon).visibility = View.GONE
        binding.identityComplete.visibility = View.VISIBLE
        binding.orbitView.findViewById<View>(R.id.dashpay_user_icon).visibility = View.VISIBLE
        binding.orbitView.findViewById<TextView>(R.id.username_1st_letter).text = completeUsername[0].toString()

        val text = getString(
            if (Names.isUsernameContestable(completeUsername)) {
                R.string.request_complete_message
            } else {
                R.string.identity_complete_message
            },
            completeUsername
        )

        val spannableContent = SpannableString(text)
        val start = text.indexOf(completeUsername)
        val end = start + completeUsername.length
        spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        binding.identityCompleteText.text = spannableContent
        binding.identityCompleteButton.setOnClickListener { closeActivity() }
    }

    private fun showProcessingState() {
        if (!isProcessing) {
            val username = requestUsernameViewModel.requestedUserName!!
                val text = getString(if (Names.isUsernameContestable(completeUsername)) {
                    R.string.username_being_requested
                } else {
                    R.string.username_being_created
                }, username
            )

            binding.processingIdentity.visibility = View.VISIBLE
            binding.chooseUsernameTitle.startAnimation(fadeOutAnimation)
            binding.processingIdentity.startAnimation(slideInAnimation)
            (binding.processingIdentityLoadingImage.drawable as AnimationDrawable).start()

            val spannableContent = SpannableString(text)
            val start = text.indexOf(username)
            val end = start + username.length
            spannableContent.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
            binding.processingIdentityMessage.text = spannableContent
            isProcessing = true
        }
    }
}
