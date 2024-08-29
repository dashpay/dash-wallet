package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentRequestUsernameBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import java.util.Date
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            binding.requestUsernameButton.isEnabled = username.isNotEmpty() &&
                requestUserNameViewModel.canAffordNonContestedUsername()

            binding.inputWrapper.isEndIconVisible = username.isNotEmpty()
            // TODO: Replace with api to verify username
            val isUsernameValid = binding.usernameInput.text.contentEquals("test") ||
                binding.usernameInput.text.contentEquals("admin")


            if (username != null) {
                val usernameIsValid = requestUserNameViewModel.checkUsernameValid(username)

                if (usernameIsValid) { // ensure username meets basic rules before making a Platform query
                    // usernameAvailabilityValidationInProgressState()
                    checkUsername(username)
                } else {
//                binding.usernameExistsReqProgress.visibility = View.INVISIBLE
//                binding.usernameExistsReqLabel.visibility = View.GONE
//                binding.usernameExistsReqImg.visibility = View.GONE
//                binding.registerBtn.isEnabled = false
                    if (this::checkUsernameNotExistRunnable.isInitialized) {
                        handler.removeCallbacks(checkUsernameNotExistRunnable)
                        dashPayViewModel.searchUsername(null)
                    }
                }
            }
            (requireActivity() as? InteractionAwareActivity)?.imitateUserInteraction()

            // binding.usernameRequested.isVisible = isUsernameValid
        }

        binding.inputWrapper.endIconMode = TextInputLayout.END_ICON_CUSTOM
        binding.inputWrapper.setEndIconOnClickListener {
            binding.usernameInput.text?.clear()
        }

        binding.requestUsernameButton.setOnClickListener {
            if (requestUserNameViewModel.uiState.value.usernameContestable) {
                AdaptiveDialog.create(
                    R.drawable.ic_verify_identity,
                    getString(R.string.verify_your_identity),
                    getString(
                        R.string.if_somebody
                    ),
                    getString(
                        R.string.skip
                    ),
                    getString(
                        R.string.verify
                    )
                ).show(requireActivity()) {
                    requestUserNameViewModel.requestedUserName = binding.usernameInput.text.toString()
                    if (it == true) {
                        safeNavigate(
                            RequestUsernameFragmentDirections.requestUsernameFragmentToVerifyIdentityFragment(
                                binding.usernameInput.text.toString()
                            )
                        )
                    } else {
                        lifecycleScope.launch {
                            checkViewConfirmDialog()
                        }
                    }
                }
            } else {
                lifecycleScope.launch {
                    requestUserNameViewModel.requestedUserName = binding.usernameInput.text.toString()
                    checkViewConfirmDialog()
                }
            }
        }

        binding.usernameVotingInfoBtn.setOnClickListener {
            safeNavigate(RequestUsernameFragmentDirections.requestsToUsernameVotingInfoFragment(true))
        }

        lifecycleScope.launchWhenCreated {
            delay(250) // Wait for the dialog animation to finish before raising keyboard
            showKeyboard()
        }

        requestUserNameViewModel.uiState.observe(viewLifecycleOwner) {
//            if (it.usernameSubmittedSuccess) {
//                requireActivity().finish()
//            }

            if (it.usernameSubmittedError) {
                showErrorDialog()
            }

            binding.checkLetters.setImageResource(getCheckMarkImage(it.usernameCharactersValid, it.usernameTooShort))
            binding.checkLength.setImageResource(getCheckMarkImage(it.usernameLengthValid, it.usernameTooShort))
            if (it.usernameCharactersValid && it.usernameLengthValid && it.usernameCheckSuccess) {
                binding.checkAvailable.setImageResource(getCheckMarkImage(!it.usernameExists))
                binding.checkBalance.setImageResource(getCheckMarkImage(it.enoughBalance))
                binding.walletBalanceContainer.isVisible = !it.enoughBalance

                if (it.usernameContestable || it.usernameContested) {
                    val startDate = Date(it.votingPeriodStart)
                    val endDate = Date(startDate.time + TimeUnit.DAYS.toMillis(14))
                    val dateFormat = DateFormat.getMediumDateFormat(context)
                    binding.votingPeriod.text = getString(
                        R.string.request_voting_range,
                        dateFormat.format(endDate)
                    )
                    binding.votingPeriodContainer.isVisible = true
                } else {
                    binding.votingPeriodContainer.isVisible = false
                }

                binding.usernameAvailableContainer.isVisible = true
                when {
                    it.usernameBlocked && it.usernameContestable -> {
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_unavailable)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(false))
                    }
                    it.usernameExists -> {
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_taken)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(false, false))
                    }
                    it.usernameContestable -> {
                        // voting period container will be visible
                        binding.usernameAvailableContainer.isVisible = false
                    }
                    else -> {
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_available)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(true))
                    }
                }
                binding.requestUsernameButton.isEnabled = it.enoughBalance

                if (it.usernameRequestSubmitting) {
                    binding.usernameInput.isFocusable = false
                    hideKeyboard()
                }

                if (it.usernameVerified) {
                    binding.usernameInput.isFocusable = false
                    hideKeyboard()
                    checkViewConfirmDialog()
                }

            } else {
                binding.votingPeriodContainer.isVisible = false
                binding.walletBalanceContainer.isVisible = false
                binding.usernameAvailableContainer.isVisible = false
                binding.requestUsernameButton.isEnabled = false
            }
        }

        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            if (it?.creationStateErrorMessage != null) {
                requireActivity().finish()
            } else if ((it?.creationState?.ordinal ?: 0) > BlockchainIdentityData.CreationState.NONE.ordinal) {
                // completeUsername = it.username ?: ""
                // showCompleteState()
                // for now, just go to the home screen
                //requireActivity().finish()
                safeNavigate(RequestUsernameFragmentDirections.requestsToUsernameRegistrationFragment())
            }
        }
    }

    private fun getCheckMarkImage(check: Boolean, empty: Boolean = false): Int {
        return when {
            empty -> R.drawable.ic_check_circle_empty
            check -> R.drawable.ic_check_circle_green
            else -> R.drawable.ic_error_circle
        }
    }

    private suspend fun checkViewConfirmDialog() {
        // TODO: Can we cancel the request?
        if (requestUserNameViewModel.hasUserCancelledVerification()) {
            requestUserNameViewModel.submit()
        } else {
            safeNavigate(
                RequestUsernameFragmentDirections.requestsToConfirmUsernameRequestDialog(
                    requestUserNameViewModel.requestedUserName!!
                )
            )
        }
    }

    private fun showKeyboard() {
        binding.usernameInput.requestFocus()
        KeyboardUtil.showSoftKeyboard(requireContext(), binding.usernameInput)
    }

    private fun hideKeyboard() {
        KeyboardUtil.hideKeyboard(requireContext(), binding.usernameInput)
    }

    private fun showErrorDialog() {
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.something_wrong_title),
            getString(R.string.there_was_a_network_error),
            getString(R.string.close),
            getString(R.string.try_again)

        )
        dialog.show(requireActivity()) {
            if (it == true) {
                requestUserNameViewModel.submit()
            }
        }
    }

    private fun checkUsername(username: String) {
        if (this::checkUsernameNotExistRunnable.isInitialized) {
            handler.removeCallbacks(checkUsernameNotExistRunnable)
        }
        checkUsernameNotExistRunnable = Runnable {
            //dashPayViewModel.searchUsername(username)
            requestUserNameViewModel.checkUsername(username)
        }
        handler.postDelayed(checkUsernameNotExistRunnable, 600)
    }
}
