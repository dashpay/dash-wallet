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
import androidx.lifecycle.Lifecycle
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
    private var ambiguousDialogShown = false

    fun observe() {
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
                viewModel.submit()
            }
        }
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
