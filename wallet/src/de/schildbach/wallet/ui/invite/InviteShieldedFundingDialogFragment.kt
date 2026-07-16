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
package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.shielded.ShieldedBalanceActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment

/**
 * Create-invitation shielded-funding decision sheet (Figma 25163:53221) —
 * the start destination of the create-invitation flow (`nav_create_invite`),
 * shown BEFORE the fee/confirm step. It mirrors the create-username flow's
 * "Make your username private" decision: inform the user their invitation
 * funds can be shielded, show the shielded contested/non-contested cost, and
 * offer "Shield your funds first" (opens the internal-transfer flow) or
 * "Continue without privacy" (proceeds to the existing fee dialog).
 *
 * When the shielded features are OFF or the platform is unsupported the
 * sheet is skipped entirely and the flow forwards straight to the fee
 * dialog, exactly as before this decision existed.
 */
@AndroidEntryPoint
class InviteShieldedFundingDialogFragment :
    OffsetDialogFragment(R.layout.dialog_invite_shielded_funding) {

    private val viewModel by viewModels<InviteShieldedFundingViewModel>()
    private val args by navArgs<InviteShieldedFundingDialogFragmentArgs>()

    /** Guards the one-shot forward-to-fee-dialog navigation. */
    private var forwarded = false

    /**
     * Always show the sheet at its full content height — matching the
     * username payment sheet, whose default half-expanded state clipped the
     * bottom button on the S21.
     */
    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (view as ComposeView).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.uiState.collectAsState()

                // Skip decision: the shielded features are off / unsupported.
                // Wait for the flag read to resolve, then forward to the fee
                // dialog without ever rendering a sheet.
                val skip = state.resolved &&
                    (
                        !Constants.SUPPORTS_PLATFORM ||
                            state.prompt == InviteShieldedFundingPrompt.NONE
                        )

                if (skip) {
                    LaunchedEffect(Unit) { toFeeDialog(shielded = false) }
                    return@setContent
                }

                if (!state.resolved) {
                    // Undecided: render nothing until the flag read completes.
                    return@setContent
                }

                InviteShieldedFundingSheet(
                    nonContestedShieldedCost = state.nonContestedShieldedCost.toPlainString(),
                    contestedShieldedCost = state.contestedShieldedCost.toPlainString(),
                    canShieldMinimum = state.canShieldMinimum,
                    canCreatePrivateInvite = state.canCreatePrivateInvite,
                    onCreatePrivateInvite = {
                        // The shielded pool can already fund an invite — go to
                        // the fee step in SHIELDED mode; the invitation is then
                        // funded directly from the pool (L2) at confirm.
                        toFeeDialog(shielded = true)
                    },
                    onShieldFirst = {
                        // Shield funds in the internal-transfer flow; leaving
                        // the invite flow returns the user to the originating
                        // screen, from which they can re-start the invitation.
                        startActivity(
                            ShieldedBalanceActivity.createIntent(requireContext(), shieldFirst = true)
                        )
                        dismiss()
                    },
                    onContinueWithoutPrivacy = { toFeeDialog(shielded = false) },
                    onClose = ::dismiss
                )
            }
        }
    }

    private fun toFeeDialog(shielded: Boolean) {
        if (forwarded) return
        forwarded = true
        findNavController().navigate(
            InviteShieldedFundingDialogFragmentDirections.toInvitationFeeDialog(args.source, shielded)
        )
    }
}
