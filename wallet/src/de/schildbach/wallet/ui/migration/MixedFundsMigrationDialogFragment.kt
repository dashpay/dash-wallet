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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
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
 * Mirrors the iOS destination sheet: Later defers the startup offer while
 * Tools remains reachable, failures retry without restarting, and success
 * stays visible until Done. A pending shield retries its existing lock.
 *
 * AFTER a choice broadcast (STARTED), the sheet swaps to a PROCESSING
 * presentation (spinner + "your funds are on the way") that must stay
 * visible until the migration's result actually is — the persisted
 * in-flight marker (`DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT`), not
 * the sheet, carries that state, so a lock-screen teardown or activity
 * recreation re-shows this sheet directly in the processing presentation
 * (`MainViewModel` re-fires the prompt while the marker is set). The sheet
 * cannot be dismissed during processing. Closing terminal guidance retains
 * the recovery marker; it is cleared only when the result becomes visible.
 */
@AndroidEntryPoint
class MixedFundsMigrationDialogFragment :
    OffsetDialogFragment(R.layout.dialog_mixed_funds_migration) {

    private val binding by viewBinding(DialogMixedFundsMigrationBinding::bind)
    // Auto-lock dismisses this fragment. Keep the spend and its UI state on
    // the activity so unlocking can reopen progress without cancelling it.
    private val viewModel by activityViewModels<MixedFundsMigrationViewModel>()

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.shieldButton.setOnClickListener { viewModel.shieldMixedFunds() }
        binding.combineButton.setOnClickListener { viewModel.combineIntoUnmixedBalance() }

        // Definitive failures can retry; pending shields resume their lock.
        binding.restartButton.setOnClickListener {
            viewModel.retryMigration()
        }
        // The fallback offered when SHIELDING failed: run the existing
        // combine ("keep it spendable") migration instead.
        binding.transferUnmixedButton.setOnClickListener { viewModel.combineIntoUnmixedBalance() }
        binding.closeButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val state = viewModel.uiState.value
                if (state.outcome == null && !state.awaitingResult && !state.resultTimedOut && !state.resultLanded) {
                    viewModel.deferMigration()
                }
                dismissAllowingStateLoss()
            }
        }

        // Choice dismissal uses Later so it records the balance deferral.
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val amount = state.amount

                    // FAILURE state: an attempt ran and provably spent
                    // nothing (NOT_ATTEMPTED). The sheet swaps to the
                    // per-scenario error presentation — which buttons it
                    // offers depends on WHICH action failed.
                    val failed = state.outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED
                    val shieldFailed = failed &&
                        state.attemptedAction == MixedFundsMigrationAction.SHIELD

                    // POST-BROADCAST processing window: this session's
                    // STARTED outcome or the persisted in-flight marker (a
                    // resumed sheet after lock-screen teardown / activity
                    // recreation). Spinner + "on the way" until the result
                    // is user-visible.
                    val awaiting = !state.inProgress && !state.resultLanded && !state.resultTimedOut &&
                        (state.awaitingResult || state.outcome == MixedFundsMigrationOutcome.STARTED)
                    val processingAny = state.inProgress || awaiting
                    // An unconfirmed transfer is terminal, not a new choice.
                    val choiceMode = !failed && !processingAny &&
                        !state.resultTimedOut && !state.resultLanded &&
                        state.outcome != MixedFundsMigrationOutcome.UNCONFIRMED

                    binding.subtitle.text = if (amount == null) {
                        ""
                    } else {
                        getString(R.string.mixed_funds_migration_message, amount.toPlainString())
                    }
                    // The "choose where to keep it" copy only makes sense
                    // while a choice (or failure-retry) is on offer.
                    binding.subtitle.isVisible = choiceMode || failed
                    binding.coinjoinRemoved.isVisible = choiceMode || failed

                    val actionable = amount != null && choiceMode
                    binding.shieldButton.isVisible = choiceMode
                    binding.shieldNote.isVisible = choiceMode
                    binding.combineButton.isVisible = choiceMode
                    binding.combineNote.isVisible = choiceMode
                    binding.shieldButton.isEnabled = actionable
                    binding.combineButton.isEnabled = actionable
                    binding.restartButton.isVisible = failed ||
                        (state.resultTimedOut && state.attemptedAction == MixedFundsMigrationAction.SHIELD)
                    binding.transferUnmixedButton.isVisible = false
                    isCancelable = !processingAny && !choiceMode
                    binding.closeButton.isVisible = !processingAny
                    binding.closeButton.setText(when {
                        choiceMode -> R.string.mixed_funds_migration_later_action
                        state.resultLanded -> R.string.mixed_funds_migration_done_action
                        else -> R.string.button_close
                    })

                    binding.progressGroup.isVisible = processingAny
                    binding.progressText.setText(
                        if (state.inProgress) {
                            R.string.mixed_funds_migration_in_progress
                        } else {
                            R.string.mixed_funds_migration_started
                        }
                    )

                    val message = when {
                        // The result landed while the sheet was up: brief
                        // success presentation, then auto-dismiss below.
                        state.resultLanded ->
                            getString(if (state.attemptedAction == MixedFundsMigrationAction.SHIELD)
                                R.string.mixed_funds_migration_shield_success
                            else R.string.mixed_funds_migration_wallet_success)
                        // The honesty window lapsed with no visible result:
                        // stop spinning, say so. Dismissal acknowledges.
                        state.resultTimedOut ->
                            getString(R.string.mixed_funds_migration_unconfirmed)
                        failed ->
                            if (shieldFailed) {
                                getString(R.string.mixed_funds_migration_shield_failed)
                            } else {
                                getString(R.string.mixed_funds_migration_combine_failed)
                            }
                        state.outcome == MixedFundsMigrationOutcome.UNCONFIRMED ->
                            getString(R.string.mixed_funds_migration_unconfirmed)
                        // While awaiting, the started message lives in the
                        // progress row next to the spinner.
                        else -> null
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
            // TEMPORARY: hard-suppressed at BOTH the prompt gate and here so
            // no caller (startup trigger, resume re-show, pending flag) can
            // present the sheet — see the note on the constant.
            if (de.schildbach.wallet.service.platform.sdk.MIXED_FUNDS_PROMPT_HARD_SUPPRESSED) return
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            MixedFundsMigrationDialogFragment().show(fm, TAG)
        }
    }
}
