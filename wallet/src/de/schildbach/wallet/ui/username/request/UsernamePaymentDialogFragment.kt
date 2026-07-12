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

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

/**
 * Bottom-sheet host of the shielded-funds payment sheets at the
 * create-username decision point (Figma flow canvas 555:811):
 * "Select your payment option" (1856:1805) when the shielded balance can
 * pay the username fee, "Make your username private" (1856:1519) when it
 * can't. The caller decides which via [UsernamePaymentUIState.prompt] and
 * receives the outcome through the [REQUEST_KEY] fragment result.
 */
@AndroidEntryPoint
class UsernamePaymentDialogFragment : OffsetDialogFragment(R.layout.dialog_username_payment) {

    companion object {
        const val TAG = "username_payment_dialog"

        /** Fragment-result key (delivered on the FragmentManager the dialog is shown on). */
        const val REQUEST_KEY = "username_payment_request"

        /** Result bundle: which action was taken — [ACTION_CONTINUE] or [ACTION_SHIELD_FIRST]. */
        const val RESULT_ACTION = "action"

        /** Result bundle: the chosen [UsernamePaymentSource] name (present with [ACTION_CONTINUE]). */
        const val RESULT_SOURCE = "source"

        /** Proceed with the flow, paying from [RESULT_SOURCE]. */
        const val ACTION_CONTINUE = "continue"

        /** Open the shielded internal-transfer flow so the user can shield funds first. */
        const val ACTION_SHIELD_FIRST = "shield_first"

        private const val ARG_PROMPT = "prompt"

        fun newInstance(prompt: UsernamePaymentPrompt) = UsernamePaymentDialogFragment().apply {
            arguments = bundleOf(ARG_PROMPT to prompt.name)
        }
    }

    private val paymentViewModel by activityViewModels<UsernamePaymentViewModel>()

    /**
     * Always show the sheet at its full content height (bottom-anchored,
     * adapting to the device screen) — the default half-expanded state
     * clipped "Continue without privacy" at the bottom on the S21.
     */
    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prompt = arguments?.getString(ARG_PROMPT)
            ?.let { runCatching { UsernamePaymentPrompt.valueOf(it) }.getOrNull() }
            ?: UsernamePaymentPrompt.MAKE_USERNAME_PRIVATE

        (view as ComposeView).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                when (prompt) {
                    UsernamePaymentPrompt.SELECT_PAYMENT_OPTION -> {
                        val state by paymentViewModel.uiState.collectAsState()
                        SelectPaymentOptionSheet(
                            selectedSource = state.selectedSource,
                            onSelect = paymentViewModel::selectSource,
                            onContinue = {
                                state.selectedSource?.let { deliver(ACTION_CONTINUE, it) }
                            },
                            onClose = ::dismiss
                        )
                    }
                    else -> {
                        val state by paymentViewModel.uiState.collectAsState()
                        MakeUsernamePrivateSheet(
                            minShieldAmount = state.shieldedFundingRequirement?.toPlainString() ?: "0.1",
                            canShieldMinimum = state.canShieldMinimum,
                            onShieldFirst = { deliver(ACTION_SHIELD_FIRST) },
                            onContinueWithoutPrivacy = {
                                deliver(ACTION_CONTINUE, UsernamePaymentSource.DASH_BALANCE)
                            },
                            onClose = ::dismiss
                        )
                    }
                }
            }
        }
    }

    private fun deliver(action: String, source: UsernamePaymentSource? = null) {
        setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_ACTION to action, RESULT_SOURCE to source?.name)
        )
        dismiss()
    }
}
