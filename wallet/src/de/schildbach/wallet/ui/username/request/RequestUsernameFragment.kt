package de.schildbach.wallet.ui.username.request

import android.os.Bundle
import android.os.Handler
import android.text.TextWatcher
import android.text.Editable
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.username.CreateUsernameActions
import de.schildbach.wallet.ui.username.UsernameType
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
open class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    private val args by navArgs<RequestUsernameFragmentArgs>()
    
    // Username type determines which field to use in the ViewModel
    private var usernameType: UsernameType = UsernameType.Primary

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable
    private lateinit var keyboardUtil: KeyboardUtil

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requestUserNameViewModel.setCreateUsernameArgs(dashPayViewModel.createUsernameArgs)
        
        // Get username type from arguments if provided. Consider error states
        usernameType = if (dashPayViewModel.createUsernameArgs?.actions == CreateUsernameActions.REUSE_TRANSACTION) {
            val identityData = requestUserNameViewModel.identity
            if (identityData != null && identityData.creationError) {
                when {
                    identityData.usernameSecondary != null && identityData.creationState == IdentityCreationState.USERNAME_REGISTERING -> UsernameType.Secondary
                    identityData.creationState == IdentityCreationState.USERNAME_REGISTERING -> UsernameType.Primary
                    else -> args.usernameType
                }
            } else {
                args.usernameType
            }
        } else {
            args.usernameType
        }

        binding.title.text = when (usernameType) {
            UsernameType.Primary -> getString(R.string.request_your_username)
            UsernameType.Secondary -> getString(R.string.request_instant_username)
        }

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            val username = text.toString()
            binding.inputWrapper.isEndIconVisible = username.isNotEmpty()

            processUsername(username)
            (requireActivity() as? InteractionAwareActivity)?.imitateUserInteraction()
        }

        binding.usernameInput.setOnEditorActionListener { _, _, _ ->
            if (binding.requestUsernameButton.isEnabled) {
                onContinue()
            }

            true
        }

        if (usernameType == UsernameType.Secondary) {
            val primaryUsername = requestUserNameViewModel.requestedUserName!!
            binding.usernameInput.setText(primaryUsername)
            
            // Set selection to the end so user can only type after the dash
            binding.usernameInput.setSelection(primaryUsername.length)
            
            // Add TextWatcher to prevent editing the prefix part and restrict suffix to digits 2-9
            binding.usernameInput.addTextChangedListener(object : TextWatcher {
                private var isUpdating = false
                
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                
                override fun afterTextChanged(s: Editable?) {
                    if (isUpdating || s == null) return
                    
                    val currentText = s.toString()
                    
                    // Ensure text starts with primary username
                    if (!currentText.startsWith(primaryUsername)) {
                        isUpdating = true
                        s.clear()
                        s.append(primaryUsername)
                        binding.usernameInput.setSelection(primaryUsername.length)
                        isUpdating = false
                        return
                    }
                    
//                    // Check suffix for invalid characters (only allow digits 2-9)
//                    val suffix = currentText.substring(primaryUsername.length)
//                    val validSuffix = suffix.filter { it in '0'..'9' }
//
//                    if (suffix != validSuffix) {
//                        isUpdating = true
//                        s.clear()
//                        s.append(primaryUsername + validSuffix)
//                        binding.usernameInput.setSelection(primaryUsername.length + validSuffix.length)
//                        isUpdating = false
//                    }
                }
            })
        }

        requestUserNameViewModel.inviteBalance.observe(viewLifecycleOwner) {
            processUsername(binding.usernameInput.text.toString())
        }

        binding.inputWrapper.endIconMode = TextInputLayout.END_ICON_CUSTOM
        binding.inputWrapper.setEndIconOnClickListener {
            if (usernameType == UsernameType.Secondary) {
                // For secondary usernames, only clear the suffix part
                val primaryUsername = requestUserNameViewModel.requestedUserName ?: return@setEndIconOnClickListener
                binding.usernameInput.setText(primaryUsername)
                binding.usernameInput.setSelection(primaryUsername.length)
            } else {
                binding.usernameInput.text?.clear()
            }
        }

        binding.requestUsernameButton.isEnabled = false
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
            if (it.usernameSubmittedError) {
                showErrorDialog()
            }

            // Hide voting period elements for Secondary username type (instant usernames)
            binding.votingPeriodProgress.isVisible = it.checkingUsername && usernameType != UsernameType.Secondary
            binding.votingPeriodContainer.isVisible = !it.checkingUsername && usernameType != UsernameType.Secondary

            binding.checkLetters.setImageResource(getCheckMarkImage(it.usernameCharactersValid, it.usernameTooShort))
            binding.checkLength.setImageResource(getCheckMarkImage(it.usernameLengthValid, it.usernameTooShort))

            if (!requestUserNameViewModel.isUsingInvite() || requestUserNameViewModel.isInviteForContestedNames()) {
                binding.checkLetters.setImageResource(
                    getCheckMarkImage(
                        it.usernameCharactersValid,
                        it.usernameTooShort
                    )
                )
                binding.checkLength.setImageResource(
                    getCheckMarkImage(
                        it.usernameLengthValid,
                        it.usernameTooShort
                    )
                )
            } else {
                val charsValid = it.usernameCharactersValid && it.usernameNonContestedChars
                binding.checkLetters.setImageResource(
                    getCheckMarkImage(
                        charsValid,
                        it.usernameTooShort || (!charsValid && it.usernameNonContestedLength)
                    )
                )
                binding.checkLength.setImageResource(
                    getCheckMarkImage(
                        it.usernameNonContestedLength,
                        it.usernameTooShort || (charsValid && !it.usernameNonContestedLength)
                    )
                )
            }
            val isInviteContested = requestUserNameViewModel.isUsingInvite() && requestUserNameViewModel.isInviteForContestedNames()
            if (it.usernameCharactersValid && it.usernameLengthValid && it.usernameCheckSuccess) {
                binding.checkAvailable.setImageResource(getCheckMarkImage(!it.usernameExists))
                binding.checkBalance.setImageResource(getCheckMarkImage(it.enoughBalance))
                // binding.walletBalanceContainer.isVisible = !it.enoughBalance
                if ((!requestUserNameViewModel.isUsingInvite() || isInviteContested) && usernameType != UsernameType.Secondary) {
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
                } else {
                    binding.votingPeriodContainer.isVisible = false
                    // For Secondary username type, always hide wallet balance
                    if (usernameType == UsernameType.Secondary) {
                        binding.walletBalanceContainer.isVisible = false
                    }
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
                // For Secondary username type, enable button if username is valid (no balance check)
                binding.requestUsernameButton.isEnabled = if (usernameType == UsernameType.Secondary) {
                    !it.usernameExists && !it.usernameContestable
                } else {
                    it.enoughBalance && !it.usernameExists
                }

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

        if (dashPayViewModel.createUsernameArgs?.invite != null) {
            requestUserNameViewModel.isInviteMixed.observe(viewLifecycleOwner) {
                binding.inviteWithUnmixedFunds.isVisible = !it
            }
        } else {
            binding.inviteWithUnmixedFunds.isVisible = false
        }
        requestUserNameViewModel.inviteBalance.observe(viewLifecycleOwner) {
            val isInviteForContestedNames = requestUserNameViewModel.isInviteForContestedNames()
            val isInviteContested = requestUserNameViewModel.isUsingInvite() && requestUserNameViewModel.isInviteForContestedNames()
            binding.charLengthRequirement.text = getString(
                if (isInviteContested) {
                    R.string.request_username_length_requirement
                } else {
                    R.string.request_username_length_requirement_noncontested
                }
            )
            
            // Hide length requirement for Secondary usernames
            binding.charLengthRequirement.isVisible = usernameType != UsernameType.Secondary
            binding.checkLength.isVisible = usernameType != UsernameType.Secondary
            binding.allowedCharsRule.text = getString(
                if (isInviteContested) {
                    R.string.request_username_character_requirement
                } else {
                    R.string.request_username_character_requirement_invite_noncontested
                }
            )
            binding.inviteOnlyNoncontested.isVisible = requestUserNameViewModel.isUsingInvite() &&
                    !isInviteForContestedNames
            binding.usernameRequirements.isVisible = requestUserNameViewModel.isUsingInvite() && !isInviteForContestedNames
        }

        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            if (it?.usernameRequested == UsernameRequestStatus.LOST_VOTE || it?.usernameRequested == UsernameRequestStatus.LOCKED) {
                return@observe
            }
            if (it?.creationStateErrorMessage != null) {
                // why are we closing, we should allow the user to chose a new name
                // requireActivity().finish()
            } else if ((it?.creationState?.ordinal ?: 0) > IdentityCreationState.NONE.ordinal) {
                // completeUsername = it.username ?: ""
                // showCompleteState()
                // for now, just go to the home screen
                // requireActivity().finish()
                // Navigate to MoreFragment instead of UsernameRegistrationFragment
                requireActivity().finish()
            }
        }
        binding.nonContestedNameInfoButton.setOnClickListener {
            UsernameTypesDialog().show(requireActivity())
        }
    }

    /**
     * Sets the username in the appropriate ViewModel field based on the username type
     */
    private fun setUsernameInViewModel(username: String) {
        when (usernameType) {
            UsernameType.Primary -> requestUserNameViewModel.requestedUserName = username
            UsernameType.Secondary -> requestUserNameViewModel.requestedUsernameSecondary = username
        }
    }

    private fun processUsername(username: String) {
        binding.requestUsernameButton.isEnabled = false
        if (username.isNotEmpty()) {
            val usernameIsValid = requestUserNameViewModel.checkUsernameValid(username, usernameType)

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
            when (usernameType) {
                UsernameType.Primary -> safeNavigate(
                    RequestUsernameFragmentDirections.requestsToConfirmUsernameRequestDialog(
                        requestUserNameViewModel.requestedUserName!!,
                        usernameType
                    )
                )
                UsernameType.Secondary -> safeNavigate(
                    RequestUsernameSecondaryFragmentDirections.requestsInstantToConfirmUsernameRequestDialog(
                        requestUserNameViewModel.requestedUsernameSecondary
                            ?: binding.usernameInput.text.toString(),
                        usernameType
                    )
                )
            }
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
            requestUserNameViewModel.checkUsername(username)
        }
        handler.postDelayed(checkUsernameNotExistRunnable, 600)
    }

    private fun onContinue() {
        KeyboardUtil.hideKeyboard(requireContext(), binding.usernameInput)

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
                setUsernameInViewModel(binding.usernameInput.text.toString())
                if (it == true) {
                    // Use primary directions for both types - verify fragment will handle both
                    safeNavigate(
                        RequestUsernameFragmentDirections.requestUsernameFragmentToVerifyIdentityFragment(
                            binding.usernameInput.text.toString(),
                            usernameType
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
                setUsernameInViewModel(binding.usernameInput.text.toString())
                checkViewConfirmDialog()
            }
        }
    }
}
