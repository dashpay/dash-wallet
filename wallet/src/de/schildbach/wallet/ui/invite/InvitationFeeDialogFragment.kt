/*
 * Copyright (c) 2024 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.invite

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.sdk.ShieldedSyncStatus
import org.bitcoinj.core.Coin
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogInvitationFeeBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe

@AndroidEntryPoint
class InvitationFeeDialogFragment : OffsetDialogFragment(R.layout.dialog_invitation_fee) {
    private val binding by viewBinding(DialogInvitationFeeBinding::bind)
    private var selectedFee = Constants.DASH_PAY_FEE_CONTESTED
    private var contestedSelected = true

    // Gate inputs. For an L1 invite only [l1Balance] matters; for a private
    // (shielded) invite the fee is funded from the pool, so the gate reads
    // [shieldedBalance]/[shieldedReady] instead (Fix F). A mid-sync shielded
    // balance is a Dash.ZERO placeholder, so it is only trusted at READY.
    private var l1Balance = Coin.ZERO
    private var shieldedBalance = Coin.ZERO
    private var shieldedReady = false

    // The Type-20 exit denominations that actually LEAVE the pool for a
    // private invite (0.1 non-contested / 0.3 contested) — the amount the
    // user "pays". Distinct from the 0.15/0.35 fund-minimum the pool must
    // HOLD (that is the gate threshold, see inviteFeeGate).
    private val shieldedNonContestedFee = Coin.parseCoin("0.1")
    private val shieldedContestedFee = Coin.parseCoin("0.3")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val viewModel by activityViewModels<InvitationFragmentViewModel>()
    private val args by navArgs<InvitationFeeDialogFragmentArgs>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setFeeAmounts()
        setMode(true)
        binding.mixButton.setOnClickListener {
            // Authentication happens on the confirm screen, right before the
            // spend (ConfirmInviteDialogFragment) — the app's standard order is
            // amount-confirm THEN authenticate. The previous PIN prompt here
            // ran before the amount was even shown and discarded its result.
            //
            // Pass the amount that is ACTUALLY withdrawn (Fix G3): for a
            // private invite the Type-20 exit denomination (0.1 / 0.3), for a
            // standard invite the L1 fee (0.03 / 0.25). The shielded spend
            // uses createShieldedInvite(contested) with its own internal
            // denomination, so this value is display-only there; the L1 spend
            // funds sendInviteTransaction with exactly this amount.
            findNavController().navigate(
                InvitationFeeDialogFragmentDirections.toConfirmInviteDialog(
                    withdrawnAmount().value, args.source, args.shielded
                )
            )
        }
        viewModel.walletData.observeTotalBalance().observe(viewLifecycleOwner) { walletBalance ->
            l1Balance = walletBalance
            applyGate()
        }
        if (args.shielded) {
            // Private invite: fund the fee from the shielded pool, so gate on
            // the pool. Bring the runtime up and follow the live balance/status.
            viewModel.ensureShieldedReady()
            viewModel.observeShieldedBalance().observe(viewLifecycleOwner) { balance ->
                shieldedBalance = Coin.valueOf(balance.duffs)
                applyGate()
            }
            viewModel.shieldedSyncStatus.observe(viewLifecycleOwner) { status ->
                shieldedReady = status == ShieldedSyncStatus.READY
                applyGate()
            }
        }
        binding.contestedName.setOnClickListener {
            setMode(true)
        }

        binding.nonContestedName.setOnClickListener {
            setMode(false)
        }
    }

    /**
     * Show the amount that leaves the user for each tile: the L1 fee for a
     * standard invite, or the Type-20 exit denomination for a private one
     * (Fix E — per the product rule, only the amount actually withdrawn).
     */
    private fun setFeeAmounts() {
        val contestedFee = if (args.shielded) shieldedContestedFee else Constants.DASH_PAY_FEE_CONTESTED
        val nonContestedFee = if (args.shielded) shieldedNonContestedFee else Constants.DASH_PAY_FEE
        binding.contestedNameAmount.text =
            getString(R.string.invitation_fee_amount, contestedFee.toPlainString())
        binding.nonContestedNameAmount.text =
            getString(R.string.invitation_fee_amount, nonContestedFee.toPlainString())
    }

    private fun setMode(isContestedName: Boolean) {
        if (isContestedName) {
            binding.contestedName.isSelected = true
            binding.nonContestedName.isSelected = false
            selectedFee = Constants.DASH_PAY_FEE_CONTESTED
            contestedSelected = true
        } else {
            binding.contestedName.isSelected = false
            binding.nonContestedName.isSelected = true
            selectedFee = Constants.DASH_PAY_FEE
            contestedSelected = false
        }
        applyGate()
    }

    /** The amount actually withdrawn for the current selection (Fix G3). */
    private fun withdrawnAmount(): Coin {
        return if (args.shielded) {
            if (contestedSelected) shieldedContestedFee else shieldedNonContestedFee
        } else {
            selectedFee
        }
    }

    /**
     * Apply the pure [inviteFeeGate] decision. BOTH tiles stay selectable
     * regardless of balance (Fix G2); only "Confirm and pay" is gated, on the
     * CURRENTLY selected kind, sourced from the L1 wallet or the shielded pool
     * per [args].shielded. When the selection is unaffordable, name what the
     * user needs (the pool minimum for a private invite, the L1 fee otherwise).
     */
    private fun applyGate() {
        val continueEnabled = inviteFeeGate(
            shielded = args.shielded,
            l1Balance = l1Balance,
            shieldedReady = shieldedReady,
            shieldedBalance = shieldedBalance,
            contestedSelected = contestedSelected
        )
        binding.mixButton.isEnabled = continueEnabled
        binding.insufficientFundsMessage.isVisible = !continueEnabled
        if (!continueEnabled) {
            val required = inviteFeeRequirement(args.shielded, contestedSelected)
            binding.insufficientFundsMessage.text =
                getString(R.string.invitation_cant_afford_message, required.toPlainString())
        }
    }
}
