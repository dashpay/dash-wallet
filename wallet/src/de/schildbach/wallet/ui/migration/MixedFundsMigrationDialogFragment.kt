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
package de.schildbach.wallet.ui.migration

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogMixedFundsMigrationBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

/**
 * The one-time post-upgrade MIXED-FUNDS sheet.
 *
 * Mixing was removed from the app; wallets that mixed in the past still hold
 * coins on a keychain the app's normal spend paths no longer reach. This
 * offers the two migrations, both of which EMPTY that keychain so the
 * situation cannot recur:
 *
 * - **Move to shielded balance** — the funds stay private.
 * - **Keep it spendable in Dash** — the funds return to the ordinary balance,
 *   which UNDOES the mixing. The sheet states that plainly (the
 *   `mixed_funds_migration_combine_note` string) rather than burying it.
 *
 * Not cancellable by back/outside tap while an option is running.
 *
 * DISMISSAL has two distinct meanings, which the button copy reflects:
 * tapping "Leave it and don't ask again" records the permanent latch, while
 * swiping the sheet away only defers it (the per-launch latch in
 * `MainViewModel` stops it re-firing this launch). A user who is not ready to
 * decide keeps a path back to their funds; a user who is done is not nagged.
 */
@AndroidEntryPoint
class MixedFundsMigrationDialogFragment :
    OffsetDialogFragment(R.layout.dialog_mixed_funds_migration) {

    private val binding by viewBinding(DialogMixedFundsMigrationBinding::bind)
    private val viewModel by viewModels<MixedFundsMigrationViewModel>()

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.shieldButton.setOnClickListener { viewModel.shieldMixedFunds() }
        binding.combineButton.setOnClickListener { viewModel.combineIntoUnmixedBalance() }
        binding.notNowButton.setOnClickListener {
            viewModel.dismissForever()
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val amount = state.amount
                    binding.subtitle.text = if (amount == null) {
                        ""
                    } else {
                        getString(R.string.mixed_funds_migration_message, amount.toFriendlyString())
                    }

                    val actionable = amount != null && !state.inProgress &&
                        state.outcome != MixedFundsMigrationOutcome.STARTED
                    binding.shieldButton.isEnabled = actionable
                    binding.combineButton.isEnabled = actionable
                    binding.notNowButton.isEnabled = !state.inProgress
                    // The sheet must stay put while a ~30s proof is running:
                    // a dismissal mid-flight would leave the user with no
                    // feedback for an operation that is already spending.
                    isCancelable = !state.inProgress

                    binding.progressGroup.isVisible = state.inProgress

                    val message = when (state.outcome) {
                        MixedFundsMigrationOutcome.STARTED ->
                            getString(R.string.mixed_funds_migration_started)
                        MixedFundsMigrationOutcome.NOT_ATTEMPTED ->
                            getString(R.string.mixed_funds_migration_failed)
                        MixedFundsMigrationOutcome.UNCONFIRMED ->
                            getString(R.string.mixed_funds_migration_unconfirmed)
                        null -> null
                    }
                    binding.errorText.isVisible = message != null
                    binding.errorText.text = message ?: ""
                }
            }
        }
    }

    companion object {
        private const val TAG = "mixed_funds_migration"

        /**
         * Show the sheet unless it is already up. Safe to call more than once
         * — the tag check makes it idempotent for the STARTED/stopped
         * lifecycle churn a startup trigger goes through.
         */
        fun showOnce(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            MixedFundsMigrationDialogFragment().show(fm, TAG)
        }
    }
}
