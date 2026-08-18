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
import javax.inject.Inject
import org.dash.wallet.common.services.AuthenticationManager
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.main.MainActivity
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
import org.dashj.platform.sdk.platform.Names
import java.util.Date

@AndroidEntryPoint
open class RequestUsernameFragment : Fragment(R.layout.fragment_request_username) {

    @Inject
    lateinit var authManager: AuthenticationManager
    private val binding by viewBinding(FragmentRequestUsernameBinding::bind)

    private val dashPayViewModel: DashPayViewModel by activityViewModels()
    private val requestUserNameViewModel by activityViewModels<RequestUserNameViewModel>()
    private val args by navArgs<RequestUsernameFragmentArgs>()
    
    // Username type determines which field to use in the ViewModel
    private var usernameType: UsernameType = UsernameType.Primary

    private var handler: Handler = Handler()
    private lateinit var checkUsernameNotExistRunnable: Runnable
    private lateinit var keyboardUtil: KeyboardUtil

    // Guards the post-completion route/finish: the identity observer can emit
    // repeatedly and the dialog dismiss can race it, but the completion must
    // route and finish exactly once (a contested completion would otherwise
    // stack a second More-screen activity).
    private var completionHandled = false

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

        // The submit-status handler (navigate-home-on-submit / error /
        // ambiguous) is the SHARED component every username-flow screen
        // installs — see UsernameSubmitStatusDialogs (Brian: submitting
        // silently dropped back to the entry screen with no feedback).
        UsernameSubmitStatusDialogs(this, requestUserNameViewModel, authManager) {
            // The request has been handed off: the creation keeps running
            // (foreground service / app scope) and the home identity tile
            // reports the result, so finish there now instead of showing a
            // modal. Route contested completions to the More screen's
            // voting tile, non-contested ones back to Home.
            finishAfterCompletion()
        }.observe()

        // One ADVISORY platform-health probe per screen entry: warn when
        // the platform side lags the local chain (asset-lock operations
        // will retry for extra minutes) — never gates the button.
        requestUserNameViewModel.checkNetworkHealth()

        requestUserNameViewModel.uiState.observe(viewLifecycleOwner) {
            binding.networkSlowContainer.isVisible = it.networkSlow

            // Hide voting period elements for Secondary username type (instant usernames)
            binding.votingPeriodProgress.isVisible = it.checkingUsername && usernameType != UsernameType.Secondary
            binding.votingPeriodContainer.isVisible = !it.checkingUsername && usernameType != UsernameType.Secondary

            // The row LABELS follow the same split as below (see the
            // inviteBalance observer): the invite-for-contested flow shows
            // the general validity rules; everyone else shows the
            // NON-CONTESTED QUALIFIERS ("contains 2–9" / "20–23 chars") —
            // and the checkmarks must be computed from the SAME rule set as
            // the labels (they previously mixed general validity under
            // qualifier labels: a 15-char name showed green on "Between 20
            // and 23 characters" — Brian).
            val inviteContestedRows = requestUserNameViewModel.isUsingInvite() &&
                requestUserNameViewModel.isInviteForContestedNames()
            if (inviteContestedRows) {
                binding.checkLetters.setImageResource(
                    getCheckMarkImage(it.usernameCharactersValid, it.usernameTooShort)
                )
                binding.checkLength.setImageResource(
                    getCheckMarkImage(it.usernameLengthValid, it.usernameTooShort)
                )
            } else {
                // The qualifiers are meet-ONE-of, so each row is LITERAL:
                // green only when ITS rule matched, neutral when unmatched
                // (the other rule may still qualify — an unmatched rule is
                // not an error), red only for a real validity problem on
                // that dimension (illegal character / over the 23-char max).
                binding.checkLetters.setImageResource(
                    getCheckMarkImage(
                        check = it.usernameCharactersValid && it.usernameNonContestedChars,
                        empty = it.usernameTooShort ||
                            (it.usernameCharactersValid && !it.usernameNonContestedChars)
                    )
                )
                binding.checkLength.setImageResource(
                    getCheckMarkImage(
                        check = it.usernameLengthValid && it.usernameNonContestedLength,
                        empty = it.usernameTooShort ||
                            (it.usernameLengthValid && !it.usernameNonContestedLength)
                    )
                )
            }
            val isInviteContested = requestUserNameViewModel.isUsingInvite() && requestUserNameViewModel.isInviteForContestedNames()
            // The button's enabled state + label follow the pure gate
            // (see usernameSubmitButtonState): the shielded funding path
            // reflects the LIVE pool status, so while it is still syncing
            // the button is a disabled "Preparing shielded balance…" pending
            // state that re-enables automatically at READY. Computed once
            // here so the red insufficient-funds surface can be suppressed in
            // lock-step with it (Fix D).
            val buttonState = usernameSubmitButtonState(
                usernameType = usernameType,
                // The EFFECTIVE source, not the raw field: a contested name
                // the pool cannot fund falls back to the Dash-balance path
                // (Mo-973), and that submission must not gate on shielded
                // pool readiness it does not use.
                paymentSource = requestUserNameViewModel.effectivePaymentSourceFor(it.usernameContestable),
                shieldedSyncStatus = it.shieldedSyncStatus,
                enoughBalance = it.enoughBalance,
                usernameExists = it.usernameExists,
                usernameContestable = it.usernameContestable,
                fundingNoteAnchored = it.fundingNoteAnchored
            )
            // While the shielded pool is still preparing, its balance is a
            // mid-sync placeholder — the affordability gate reads `false` and
            // would flash the red "insufficient/unavailable funds" row for a
            // moment before the pool settles (Fix D). Treat balance as unknown
            // (neutral, not red) until the pool is READY; legitimate red
            // errors resume the instant the button leaves PreparingShielded.
            val shieldedPreparing = buttonState == UsernameSubmitButtonState.PreparingShielded
            if (it.usernameCharactersValid && it.usernameLengthValid && it.usernameCheckSuccess) {
                binding.checkAvailable.setImageResource(getCheckMarkImage(!it.usernameExists))
                binding.checkBalance.setImageResource(
                    getCheckMarkImage(it.enoughBalance, empty = shieldedPreparing)
                )
                // binding.walletBalanceContainer.isVisible = !it.enoughBalance
                if ((!requestUserNameViewModel.isUsingInvite() || isInviteContested) && usernameType != UsernameType.Secondary) {
                    binding.walletBalanceContainer.isVisible = !it.enoughBalance && !shieldedPreparing
                    if (it.requiredAmount.isNotEmpty()) {
                        // The settling variant explains the non-obvious case:
                        // the DISPLAY balance covers the fee but the funds the
                        // asset-lock build can actually select (final BIP44
                        // coins) do not — the plain "you need X DASH" copy
                        // would contradict the balance the user can see.
                        binding.balanceRequirementText.text = if (it.fundsSettling) {
                            getString(
                                R.string.request_username_balance_settling,
                                it.requiredAmount
                            )
                        } else {
                            getString(
                                R.string.request_username_balance_requirement_amount,
                                it.requiredAmount
                            )
                        }
                    }

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
                // Apply the button gate computed above (Fix B): the shielded
                // funding path reflects the LIVE pool status, so while it is
                // still syncing the button is a disabled "Preparing shielded
                // balance…" pending state that re-enables automatically at
                // READY — never a stale-cache enabled button that lets a
                // submit reach the SDK and bounce. L1 path is unaffected.
                binding.requestUsernameButton.isEnabled =
                    buttonState == UsernameSubmitButtonState.Enabled
                binding.requestUsernameButton.setText(
                    if (buttonState == UsernameSubmitButtonState.PreparingShielded) {
                        R.string.username_preparing_shielded_balance
                    } else {
                        R.string.request_username
                    }
                )
                // The shield is still confirming/syncing (button reads
                // "Preparing shielded balance…") — surface the privacy-window
                // advisory just above the button until the pool reaches READY.
                binding.shieldedWaitContainer.isVisible =
                    buttonState == UsernameSubmitButtonState.PreparingShielded

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
                // Fail-closed surface: the button is disabled either way
                // (usernameCheckSuccess is false), but a lookup failure has
                // to SAY so — silence here read as "available" before the
                // check was made fail-closed.
                binding.usernameAvailableContainer.isVisible = it.usernameCheckFailed
                if (it.usernameCheckFailed) {
                    binding.usernameAvailableMessage.text = getString(R.string.username_check_failed)
                    binding.checkAvailable.setImageResource(getCheckMarkImage(false, false))
                }
                binding.requestUsernameButton.isEnabled = false
                // No completed check yet — the "Preparing…" label only
                // applies where the button would otherwise be enabled, so
                // keep the normal label here.
                binding.requestUsernameButton.setText(R.string.request_username)
                binding.shieldedWaitContainer.isVisible = false
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

        requestUserNameViewModel.inviteBalance.observe(viewLifecycleOwner) {
            // ONE tier value drives the notice, the requirement rows and (via
            // computeBalanceGate) the submit button, so the screen can no longer
            // contradict itself the way it did on the S22: a notice reading
            // "only a non-contested username" above a contested name with the
            // Request Username button enabled.
            val inviteTier = requestUserNameViewModel.inviteTier()
            val isInviteForContestedNames = inviteTier == InviteUsernameTier.CONTESTED
            val isInviteContested = requestUserNameViewModel.isUsingInvite() && isInviteForContestedNames
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
            // Only claim "non-contested only" when the invite's funding is
            // actually readable and says so. When it is not (a shielded invite
            // whose link carries no note value) the copy must not assert a
            // restriction the app cannot verify.
            binding.inviteOnlyNoncontestedMessage.setText(
                if (inviteTier == InviteUsernameTier.UNKNOWN) {
                    R.string.request_username_invitation_unknown_tier_message
                } else {
                    R.string.request_username_invitation_only_noncontested_message
                }
            )
            // "The username must meet one of these criteria" — shown to
            // EVERYONE who sees the non-contested qualifier rows (not just
            // invites): without it the meet-one-of semantics are invisible
            // and the rows read as two hard requirements (Brian).
            binding.usernameRequirements.isVisible =
                !isInviteContested && usernameType != UsernameType.Secondary
            // …but "MUST meet" is only true when we know the invitation is
            // non-contested. On an unreadable invite the rows describe what a
            // non-contested invitation would need, nothing more — otherwise
            // they contradict the notice directly above them, which tells the
            // user a contested name may still be requested.
            binding.usernameRequirements.setText(
                if (requestUserNameViewModel.isUsingInvite() &&
                    inviteTier == InviteUsernameTier.UNKNOWN
                ) {
                    R.string.request_username_requirements_message_invite_unknown_tier
                } else {
                    R.string.request_username_requirements_message_invite_noncontested
                }
            )
        }

        dashPayViewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            if (it?.usernameRequested == UsernameRequestStatus.LOST_VOTE || it?.usernameRequested == UsernameRequestStatus.LOCKED) {
                return@observe
            }
            if (it?.creationStateErrorMessage != null) {
                // why are we closing, we should allow the user to chose a new name
                // requireActivity().finish()
            } else if ((it?.creationState?.ordinal ?: 0) > IdentityCreationState.NONE.ordinal) {
                // A submit this session finishes to Home the moment it is
                // handed off (the submitting rising edge, via
                // UsernameSubmitStatusDialogs' navigate-home callback), so
                // this identity-state path only serves entries that find a
                // creation ALREADY in flight (no submit this session): flip
                // straight to the completion route. The submitting guard
                // keeps the two from racing / double-routing. Contested
                // completions route to the More screen's voting tile instead
                // of Home (see finishAfterCompletion).
                if (!requestUserNameViewModel.uiState.value.usernameRequestSubmitting) {
                    finishAfterCompletion()
                }
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

    /**
     * End the create-username flow, routing to where the completed username
     * belongs: a CONTESTED / in-voting username has no home welcome tile —
     * its status lives on the More screen's username-voting tile — so bring
     * MainActivity forward on the More tab (the established
     * `createIntent(destination)` path, mirroring ShieldedTransferExecutor's
     * post-success route) before finishing. Non-contested completions return
     * to Home, where fix (a)'s welcome tile shows. Shared by both finish
     * sites (the L1 processing-dialog dismiss and the identity-state
     * observer) and guarded so it runs exactly once.
     *
     * An already-registered INSTANT name outranks the contested one: when
     * the identity has a usable username the wallet is ready, so the
     * completion lands on Home even with a contested request still in
     * voting (see [usernameCompletionRoute]/[hasUsableUsername]).
     */
    private fun finishAfterCompletion() {
        if (completionHandled) return
        completionHandled = true
        finishUsernameCreationToCompletionRoute(dashPayViewModel, requestUserNameViewModel)
    }

    private suspend fun checkViewConfirmDialog() {
        // TODO: Can we cancel the request?
        if (requestUserNameViewModel.hasUserCancelledVerification()) {
            authenticateThenSubmit(this, authManager, requestUserNameViewModel)
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

/**
 * The ONE post-completion route-and-finish for the create-username flow,
 * shared by EVERY exit of the username processing dialog so they cannot
 * disagree: [RequestUsernameFragment]'s explicit dismiss / back / cancel,
 * its auto-dismiss (the identity state machine reaching a terminal state,
 * ~30s in), and [VerifyIdentityFragment]'s two equivalents — which used to
 * `finish()` blindly back to whatever screen happened to be underneath.
 *
 * The destination itself is [usernameCompletionRoute]'s pure decision.
 */
internal fun Fragment.finishUsernameCreationToCompletionRoute(
    dashPayViewModel: DashPayViewModel,
    requestUserNameViewModel: RequestUserNameViewModel
) {
    val identityData = dashPayViewModel.blockchainIdentity.value
    // The PRIMARY name of this creation: the persisted identity's username
    // when available, else the shared ViewModel's requested name (every
    // fragment of a dual flow shares the activity-scoped ViewModel, so this
    // holds the contested primary even when the SECONDARY screen is the one
    // finishing).
    val primaryName = identityData?.username
        ?: requestUserNameViewModel.requestedUserName
    val primaryContestable = try {
        primaryName?.let { Names.isUsernameContestable(it) } == true
    } catch (e: Exception) {
        false
    }
    val route = usernameCompletionRoute(
        creationState = identityData?.creationState,
        usernameContestable = requestUserNameViewModel.uiState.value.usernameContestable,
        primaryUsernameContestable = primaryContestable,
        // PERSISTED secondary name only — the requested-name field would
        // claim a usable username before the secondary pass registered it.
        usableUsernameActive = hasUsableUsername(
            creationState = identityData?.creationState,
            usernameSecondary = identityData?.usernameSecondary
        )
    )
    if (route == UsernameCompletionRoute.MORE) {
        startActivity(MainActivity.createIntent(requireContext(), R.id.moreFragment))
    } else {
        startActivity(MainActivity.createIntent(requireContext(), R.id.walletFragment))
    }
    requireActivity().finish()
}
