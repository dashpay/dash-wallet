/*
 * Copyright 2026 Dash Core Group.
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
package de.schildbach.wallet.ui.username.request

import androidx.fragment.app.Fragment
import org.dash.wallet.common.services.AuthenticationManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import de.schildbach.wallet.ui.dashpay.RetryStatusHint
import de.schildbach.wallet.ui.dashpay.retryStatusHintTextRes
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.observe

/**
 * The ONE submit-status dialog set for the username request flow, shared
 * by every screen a [RequestUserNameViewModel.submit] can be triggered
 * from ([RequestUsernameFragment], [RequestUsernameSecondaryFragment],
 * [VerifyIdentityFragment]) so all four submission paths — non-contested
 * shielded, contested shielded, L1/asset-lock (Dash balance, invite,
 * reuse-transaction) and the dual primary+secondary registration — show
 * identical feedback instead of per-screen copies (Brian: contested
 * submissions bounced with no processing dialog because only one screen
 * implemented one):
 *
 * - submitting → a DISMISSIBLE processing dialog (explicit dismiss button
 *   + cancelable): the creation runs on the app scope / a foreground
 *   service and survives the dialog;
 * - error → retryable error dialog ("Try again" re-submits);
 * - ambiguous → close-only dialog that offers NO retry and claims no
 *   "extra cost" (the outcome may already be on chain — funds safety).
 *
 * Install from `onViewCreated` via [observe]; the observation is bound to
 * the fragment's view lifecycle.
 */
class UsernameSubmitStatusDialogs(
    private val fragment: Fragment,
    private val viewModel: RequestUserNameViewModel,
    private val authManager: AuthenticationManager,
    /**
     * Invoked when the USER closes the processing dialog (the explicit
     * dismiss button or a cancel) — never for the programmatic dismissal
     * that replaces it with a terminal dialog, and never for lifecycle
     * teardown. The L1/asset-lock screens finish to the home screen here:
     * the dialog is the user's "informed when ready" acknowledgement, so
     * the screen must not leave (auto-dismissing the dialog) before it.
     */
    private val onProcessingDismissedByUser: (() -> Unit)? = null
) {
    private var processingDialog: AdaptiveDialog? = null
    private var processingDismissedProgrammatically = false
    private var errorDialogShown = false
    private var poolSyncingDialogShown = false
    private var ambiguousDialogShown = false

    fun observe() {
        // The transient registration status hint (30s "network catching up"
        // watchdog / per-retry "waiting for confirmation") is already shown
        // on the home tile, but during creation the user is watching THIS
        // processing dialog — mirror the hint into it as a live secondary
        // line so the >30s wait is explained here too, not just after the
        // screen finishes to home.
        viewModel.identityCreationStatusHint.observe(fragment.viewLifecycleOwner) { hint ->
            applyStatusHint(hint)
        }
        viewModel.uiState.observe(fragment.viewLifecycleOwner) { state ->
            if (state.usernameRequestSubmitting) {
                showProcessingDialog()
            } else {
                dismissProcessingDialog()
            }
            // Terminal states replace the processing dialog. Each is shown
            // on its rising edge only — uiState re-emissions for unrelated
            // fields must not stack duplicate dialogs.
            if (state.usernameSubmittedError && !errorDialogShown) {
                errorDialogShown = true
                showErrorDialog()
            } else if (!state.usernameSubmittedError) {
                errorDialogShown = false
            }
            // The pool-not-ready refusal is NOT an error: a calm "still
            // preparing" surface (Fix A), not the red network-error dialog.
            if (state.usernameSubmittedPoolSyncing && !poolSyncingDialogShown) {
                poolSyncingDialogShown = true
                showPoolSyncingDialog()
            } else if (!state.usernameSubmittedPoolSyncing) {
                poolSyncingDialogShown = false
            }
            if (state.usernameSubmittedAmbiguous && !ambiguousDialogShown) {
                ambiguousDialogShown = true
                showAmbiguousDialog()
            }
        }
    }

    /** Dismissible: the creation runs on the app scope and survives the dialog. */
    private fun showProcessingDialog() {
        if (processingDialog?.dialog?.isShowing == true) return
        processingDismissedProgrammatically = false
        processingDialog = AdaptiveDialog.progress(
            fragment.getString(R.string.username_creation_processing),
            fragment.getString(R.string.close)
        ).also { dialog ->
            // A hint may already be live (the watchdog fired before the
            // dialog was (re)shown — e.g. after the lock screen tore it
            // down mid-creation). Seed it so the secondary line is present
            // on first frame instead of only on the next emission.
            applyStatusHint(viewModel.identityCreationStatusHint.value, dialog)
            dialog.show(fragment.requireActivity()) { result ->
                // `false` is the explicit dismiss button; `null` is a
                // cancel (tap-outside/back) OR teardown — the RESUMED
                // check tells a user cancel apart from a rotation/finish
                // destroying the dialog under us.
                val userDismissed = !processingDismissedProgrammatically &&
                    (
                        result == false ||
                            fragment.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        )
                if (userDismissed) {
                    onProcessingDismissedByUser?.invoke()
                }
            }
        }
    }

    /**
     * Push the current [RetryStatusHint] into the processing dialog's live
     * secondary line (or clear it when null). Uses the shared
     * [retryStatusHintTextRes] mapping so the copy matches the home tile.
     */
    private fun applyStatusHint(hint: RetryStatusHint?, dialog: AdaptiveDialog? = processingDialog) {
        val text = retryStatusHintTextRes(hint)?.let { fragment.getString(it) }
        dialog?.updateSecondaryMessage(text)
    }

    private fun dismissProcessingDialog() {
        processingDismissedProgrammatically = true
        processingDialog?.dismissAllowingStateLoss()
        processingDialog = null
    }

    private fun showErrorDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            fragment.getString(R.string.something_wrong_title),
            fragment.getString(R.string.there_was_a_network_error),
            fragment.getString(R.string.close),
            fragment.getString(R.string.try_again)
        ).show(fragment.requireActivity()) {
            if (it == true) {
                // The retry is a fresh spend attempt — same auth rule as
                // the original submit (see authenticateThenSubmit).
                fragment.lifecycleScope.launch {
                    authenticateThenSubmit(fragment, authManager, viewModel)
                }
            }
        }
    }

    /**
     * The shielded pool was not ready yet when the submit reached the SDK
     * (still syncing / runtime bringing up) — provably nothing was spent
     * and it will be ready shortly, so this is a calm single-button
     * notice, NOT the red "network error" dialog. Fix B's live gate
     * normally keeps the button disabled until the pool is READY; this
     * covers the residual race.
     */
    private fun showPoolSyncingDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_hourglass,
            fragment.getString(R.string.username_shielded_pool_syncing_title),
            fragment.getString(R.string.username_shielded_pool_syncing),
            fragment.getString(R.string.button_ok),
            null
        ).show(fragment.requireActivity()) { }
    }

    /**
     * The shielded creation's outcome is unconfirmed — it may already be
     * on chain, so this dialog must NOT offer a retry and must not claim
     * "no extra cost" (the generic error dialog said both, observed
     * live). Close-only; the app reconciles automatically.
     */
    private fun showAmbiguousDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            fragment.getString(R.string.username_request_ambiguous_title),
            fragment.getString(R.string.username_request_ambiguous_message),
            fragment.getString(R.string.close),
            null
        ).show(fragment.requireActivity()) { }
    }
}
