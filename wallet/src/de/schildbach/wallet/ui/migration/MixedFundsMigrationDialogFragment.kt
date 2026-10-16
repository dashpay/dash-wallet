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
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject

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
 * Each move — and Restart — asks for confirmation first; Later never does.
 * A FRESH spend (either move, or Try again after a definitive failure) also
 * requires PIN/biometric authentication, like every other send; resuming
 * the transaction already in flight does not.
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

    @Inject
    lateinit var authManager: AuthenticationManager

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Every button that spends asks first: these broadcast real, irreversible
        // transactions, so a stray tap (MO-1099) must not be enough. Later does not.
        binding.shieldButton.setOnClickListener {
            confirmThen(MixedFundsMigrationAction.SHIELD) { viewModel.shieldMixedFunds() }
        }
        binding.combineButton.setOnClickListener {
            confirmThen(MixedFundsMigrationAction.COMBINE) { viewModel.combineIntoUnmixedBalance() }
        }

        // Definitive failures can retry; pending shields resume their lock.
        binding.restartButton.setOnClickListener {
            val state = viewModel.uiState.value
            // A timed-out/unconfirmed transfer resumes the existing transaction;
            // a failed one runs its move again, so it asks that move's question
            // (and, being a fresh spend, authenticates).
            confirmThen(if (state.retryStartsFreshSpend()) state.attemptedAction else null) {
                viewModel.retryMigration()
            }
        }
        // The fallback offered when SHIELDING failed: run the existing
        // combine ("keep it spendable") migration instead.
        binding.transferUnmixedButton.setOnClickListener {
            confirmThen(MixedFundsMigrationAction.COMBINE) { viewModel.combineIntoUnmixedBalance() }
        }
        binding.closeButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val state = viewModel.uiState.value
                if (state.outcome == null && !state.awaitingResult && !state.resultTimedOut && !state.resultLanded) {
                    viewModel.deferMigration()
                }
                viewModel.onSheetClosed()
                dismissAllowingStateLoss()
            }
        }

        // Choice dismissal uses Later so it records the balance deferral.
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        // Re-read the offered amount on every showing: the ViewModel is
        // activity-scoped and its first read usually ran pre-sync (null), so
        // a stale value would leave a prompt with nothing to act on.
        viewModel.refreshAmount()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val amount = state.amount

                    // FAILURE (NOT_ATTEMPTED, provably nothing spent), the
                    // POST-BROADCAST processing window (this session's STARTED
                    // or the persisted marker), and the terminal "could not
                    // confirm" guidance — see [presentation].
                    val presentation = state.presentation()
                    val failed = presentation.failed
                    val shieldFailed = failed &&
                        state.attemptedAction == MixedFundsMigrationAction.SHIELD
                    val processingAny = presentation.processing
                    val choiceMode = presentation.choiceMode

                    binding.subtitle.text = if (amount == null) {
                        ""
                    } else {
                        getString(R.string.mixed_funds_migration_message, amount.toPlainString())
                    }
                    // The "choose where to keep it" copy only makes sense
                    // while a choice (or failure-retry) is on offer.
                    binding.subtitle.isVisible = choiceMode || failed
                    binding.coinjoinRemoved.isVisible = choiceMode || failed

                    // Nothing to offer: the fresh read found no mixed funds
                    // (moved elsewhere, or the post-cutover SDK view cannot
                    // be read). Never leave a choice sheet up whose choices
                    // are all disabled — close without recording a deferral.
                    if (choiceMode && state.outcome == null && state.amountChecked && amount == null &&
                        !state.migrationPending
                    ) {
                        dismissAllowingStateLoss()
                        return@collect
                    }

                    val actionable = amount != null && state.amountChecked && choiceMode
                    binding.shieldButton.isVisible = choiceMode
                    binding.shieldNote.isVisible = choiceMode
                    binding.combineButton.isVisible = choiceMode
                    binding.combineNote.isVisible = choiceMode
                    binding.shieldButton.isEnabled = actionable
                    binding.combineButton.isEnabled = actionable
                    binding.restartButton.isVisible = presentation.showRestart
                    binding.transferUnmixedButton.isVisible = presentation.showTransferUnmixed
                    isCancelable = presentation.cancelable
                    binding.closeButton.isVisible = presentation.showClose
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

    /** True while a confirmation is up, so a second tap cannot stack another. */
    private var confirming = false

    /**
     * Ask before running [action]: [move] picks the shield or combine wording,
     * null the generic restart wording. Runs [action] only on Confirm. A
     * non-null [move] is a FRESH spend and additionally requires PIN/biometric
     * authentication; a cancelled or failed authentication runs nothing. If
     * the sheet is torn down while asking (e.g. auto-lock), the coroutine is
     * cancelled and nothing runs.
     */
    private fun confirmThen(move: MixedFundsMigrationAction?, action: () -> Unit) {
        if (confirming) return
        confirming = true
        val (title, message) = when (move) {
            MixedFundsMigrationAction.SHIELD ->
                R.string.mixed_funds_migration_confirm_shield_title to R.string.mixed_funds_migration_confirm_shield_message
            MixedFundsMigrationAction.COMBINE ->
                R.string.mixed_funds_migration_confirm_combine_title to R.string.mixed_funds_migration_confirm_combine_message
            null ->
                R.string.mixed_funds_migration_confirm_restart_title to R.string.mixed_funds_migration_confirm_restart_message
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val confirmed = AdaptiveDialog.create(
                    R.drawable.ic_warning_yellow_circle,
                    getString(title),
                    getString(message),
                    getString(R.string.button_cancel),
                    getString(R.string.button_confirm)
                ).showAsync(requireActivity())
                if (confirmed != true) return@launch
                if (move != null && authManager.authenticate(requireActivity()) == null) return@launch
                action()
            } finally {
                confirming = false
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
