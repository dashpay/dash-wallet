package de.schildbach.wallet.ui.username.voting

import android.os.Bundle
import android.os.Handler
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.UsernameRequest
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
import org.dashj.platform.dashpay.UsernameRequestStatus
import java.util.Date

@AndroidEntryPoint
class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable
    private lateinit var keyboardUtil: KeyboardUtil

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            binding.inputWrapper.isEndIconVisible = username.isNotEmpty()

            if (username.isNotEmpty()) {
                val usernameIsValid = requestUserNameViewModel.checkUsernameValid(username)

                if (usernameIsValid) { // ensure username meets basic rules before making a Platform query
                    checkUsername(username)
                } else {
                    if (this::checkUsernameNotExistRunnable.isInitialized) {
                        handler.removeCallbacks(checkUsernameNotExistRunnable)
                        dashPayViewModel.searchUsername(null)
                    }
                }
            } else {
                requestUserNameViewModel.reset()
            }

            (requireActivity() as? InteractionAwareActivity)?.imitateUserInteraction()
        }

        binding.usernameInput.setOnEditorActionListener { _, _, _ ->
            if (binding.requestUsernameButton.isEnabled) {
                onContinue()
            }

            true
        }

        binding.inputWrapper.endIconMode = TextInputLayout.END_ICON_CUSTOM
        binding.inputWrapper.setEndIconOnClickListener {
            binding.usernameInput.text?.clear()
        }

        binding.requestUsernameButton.setOnClickListener {
            onContinue()
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

            binding.votingPeriodProgress.isVisible = it.checkingUsername
            binding.votingPeriodContainer.isVisible = !it.checkingUsername

            binding.checkLetters.setImageResource(getCheckMarkImage(it.usernameCharactersValid, it.usernameTooShort))
            binding.checkLength.setImageResource(getCheckMarkImage(it.usernameLengthValid, it.usernameTooShort))

            if (it.usernameCharactersValid && it.usernameLengthValid && it.usernameCheckSuccess) {
                binding.checkAvailable.setImageResource(getCheckMarkImage(!it.usernameExists))
                binding.checkBalance.setImageResource(getCheckMarkImage(it.enoughBalance))
                binding.walletBalanceContainer.isVisible = !it.enoughBalance

                if (it.usernameContestable || it.usernameContested) {
                    val startDate = Date(it.votingPeriodStart)
                    val endDate = Date(startDate.time + UsernameRequest.VOTING_PERIOD_MILLIS)
                    if (it.votingPeriodStart == -1L && System.currentTimeMillis() - it.votingPeriodStart > UsernameRequest.VOTING_PERIOD_MILLIS) {
                        binding.votingPeriodContainer.isVisible = false
                    } else if (it.votingPeriodStart == -1L && System.currentTimeMillis() - it.votingPeriodStart > UsernameRequest.SUBMIT_PERIOD_MILLIS) {
                        binding.votingPeriodContainer.isVisible = false
                    } else {
                        val dateFormat = DateFormat.getMediumDateFormat(context)
                        binding.votingPeriod.text = getString(
                            R.string.request_voting_range,
                            dateFormat.format(endDate)
                        )
                        binding.votingPeriodContainer.isVisible = true
                    }
                } else {
                    binding.votingPeriodContainer.isVisible = false
                }

                binding.usernameAvailableContainer.isVisible = true
                when {
                    it.usernameBlocked && it.usernameContestable -> {
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_unavailable)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(false))
                        binding.votingPeriodContainer.isVisible = false
                    }
                    it.usernameExists -> {
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_taken)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(false, false))
                        binding.votingPeriodContainer.isVisible = false
                    }
                    it.usernameContestable && (it.votingPeriodStart == -1L && System.currentTimeMillis() - it.votingPeriodStart > UsernameRequest.VOTING_PERIOD_MILLIS) -> {
                        // the submission period has ended, let us just say the username is taken
                        binding.usernameAvailableMessage.text = getString(R.string.request_username_taken)
                        binding.checkAvailable.setImageResource(getCheckMarkImage(false, false))
                        binding.votingPeriodContainer.isVisible = false
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

        keyboardUtil = KeyboardUtil(requireActivity().window, binding.root)
        val binding = this.binding
        keyboardUtil.setOnKeyboardShownChanged { isShown ->
            val params = binding.topStack.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = resources.getDimensionPixelSize(if (isShown) {
                R.dimen.create_username_shift
            } else {
                R.dimen.zero_dp
            })
            binding.topStack.layoutParams = params
        }

        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            if (it?.usernameRequested == UsernameRequestStatus.LOST_VOTE || it?.usernameRequested == UsernameRequestStatus.LOCKED) {
                return@observe
            }
            if (it?.creationStateErrorMessage != null) {
                //why are we closing, we should allow the user to chose a new name
                //requireActivity().finish()
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

    private fun onContinue() {
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
}
