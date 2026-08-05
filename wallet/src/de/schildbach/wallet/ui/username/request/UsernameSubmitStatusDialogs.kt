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
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.observe

/**
 * The ONE submit-status handler for the username request flow, shared by
 * every screen a [RequestUserNameViewModel.submit] can be triggered from
 * ([RequestUsernameFragment], [RequestUsernameSecondaryFragment],
 * [VerifyIdentityFragment]) so all four submission paths — non-contested
 * shielded, contested shielded, L1/asset-lock (Dash balance, invite,
 * reuse-transaction) and the dual primary+secondary registration — behave
 * identically instead of each screen rolling its own (Brian: contested
 * submissions bounced with no feedback because only one screen implemented
 * it):
 *
 * - submitting → the flow finishes straight to Home so the user watches
 *   the home identity tile, which already shows richer live progress than
 *   a modal ever did. The creation itself keeps running on the app scope /
 *   a foreground service and is entirely independent of this screen, so
 *   leaving does not interrupt it. (The old "~30s, you can close this"
 *   processing dialog was redundant with that tile and was removed.)
 * - error → retryable error dialog ("Try again" re-submits);
 * - ambiguous → close-only dialog that offers NO retry and claims no
 *   "extra cost" (the outcome may already be on chain — funds safety).
 *
 * The error / pool-syncing / ambiguous surfaces are the SYNCHRONOUS
 * refusals: they are set without ever flipping `usernameRequestSubmitting`
 * true (the creation never started), so the screen is still present to
 * show them. An outcome that arrives AFTER the request started running is
 * surfaced by the home tile (in-progress / error-with-retry) and the
 * executor's sticky state — a subsequent submit of an ambiguous creation
 * is refused synchronously and re-shows the ambiguous dialog, so funds
 * safety holds without the modal.
 *
 * Install from `onViewCreated` via [observe]; the observation is bound to
 * the fragment's view lifecycle.
 */
class UsernameSubmitStatusDialogs(
    private val fragment: Fragment,
    private val viewModel: RequestUserNameViewModel,
    private val authManager: AuthenticationManager,
    /**
     * Invoked once, on the rising edge of `usernameRequestSubmitting`: the
     * request has been handed off and keeps running on the app scope / a
     * foreground service, so the flow finishes to its completion route
     * (Home for non-contested, the More screen's voting tile for
     * contested) where the home identity tile reports progress and the
     * final result. Replaces the old "user closed the processing dialog"
     * hook — the destination is the same, it is just reached without a
     * modal in between.
     */
    private val onSubmitNavigateHome: (() -> Unit)? = null
) {
    private var errorDialogShown = false
    private var poolSyncingDialogShown = false
    private var ambiguousDialogShown = false
    private var navigatedHome = false

    fun observe() {
        viewModel.uiState.observe(fragment.viewLifecycleOwner) { state ->
            // The request has been accepted and is running on the app scope /
            // a foreground service — finish to Home (or the voting tile) so
            // the user watches the home identity tile instead of a modal.
            // Rising edge only: re-emissions for unrelated fields must not
            // re-trigger the route.
            if (state.usernameRequestSubmitting) {
                if (!navigatedHome) {
                    navigatedHome = true
                    onSubmitNavigateHome?.invoke()
                }
            } else {
                navigatedHome = false
            }
            // Terminal states. Each is shown on its rising edge only —
            // uiState re-emissions for unrelated fields must not stack
            // duplicate dialogs. These are the SYNCHRONOUS refusals (no
            // submitting=true flip, so the screen is still present); an
            // outcome reached after the request started is surfaced by the
            // home tile instead.
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
