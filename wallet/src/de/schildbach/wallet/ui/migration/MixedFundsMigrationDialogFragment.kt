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

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationAction
import de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogMixedFundsMigrationBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
 * The choice is FORCED: there is no "leave it" option, and the sheet cannot
 * be dismissed by back press, outside tap, or swipe until one of the two
 * migrations has actually recorded an outcome (which is also what sets the
 * permanent latch, `DashPayConfig.MIXED_FUNDS_MIGRATION_DONE`). If the
 * activity is recreated (e.g. the lock screen engages) before a choice was
 * made, `MainViewModel` re-fires the prompt on the next opportunity.
 *
 * AFTER a choice broadcast (STARTED), the sheet swaps to a PROCESSING
 * presentation (spinner + "your funds are on the way") that must stay
 * visible until the migration's result actually is — the persisted
 * in-flight marker (`DashPayConfig.MIXED_FUNDS_MIGRATION_IN_FLIGHT`), not
 * the sheet, carries that state, so a lock-screen teardown or activity
 * recreation re-shows this sheet directly in the processing presentation
 * (`MainViewModel` re-fires the prompt while the marker is set). The sheet
 * is dismissible in that window (the marker keeps the state); when the
 * result lands the sheet briefly shows the success message and
 * auto-dismisses, and when the honesty window lapses it swaps to the
 * "could not confirm" guidance — dismissing THAT acknowledges (clears) the
 * marker.
 */
@AndroidEntryPoint
class MixedFundsMigrationDialogFragment :
    OffsetDialogFragment(R.layout.dialog_mixed_funds_migration) {

    private val binding by viewBinding(DialogMixedFundsMigrationBinding::bind)
    private val viewModel by viewModels<MixedFundsMigrationViewModel>()

    @Inject
    lateinit var restartService: RestartService

    override val expandToContent = true

    /** One auto-dismiss per landed result — see the success handling below. */
    private var autoDismissScheduled = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.shieldButton.setOnClickListener { viewModel.shieldMixedFunds() }
        binding.combineButton.setOnClickListener { viewModel.combineIntoUnmixedBalance() }

        // FAILURE-state actions. A failed attempt spent nothing
        // (NOT_ATTEMPTED) and left the migration-done latch unset, so after
        // the restart the startup prompt detects the mixed funds again and
        // re-offers the normal choices (CoinJoinFundsMigrationService
        // .shouldPrompt).
        binding.restartButton.setOnClickListener {
            restartService.performRestart(requireActivity(), true)
        }
        // The fallback offered when SHIELDING failed: run the existing
        // combine ("keep it spendable") migration instead.
        binding.transferUnmixedButton.setOnClickListener { viewModel.combineIntoUnmixedBalance() }

        // FORCED choice: no back press / outside tap / swipe until one of the
        // two migrations has recorded an outcome (see the class KDoc).
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
                    // The pre-decision presentation: the forced two-option
                    // choice (also the UNCONFIRMED retry surface).
                    val choiceMode = !failed && !processingAny &&
                        !state.resultTimedOut && !state.resultLanded

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
                    binding.restartButton.isVisible = failed
                    // A failed SHIELD additionally offers the existing
                    // combine migration as the fallback; a failed COMBINE
                    // offers "Restart" only.
                    binding.transferUnmixedButton.isVisible = shieldFailed
                    // Dismissable only once a choice has actually run —
                    // STARTED/UNCONFIRMED (and every post-broadcast
                    // presentation: processing, landed, timed-out) mark the
                    // migration handled; NOT_ATTEMPTED spent nothing, so the
                    // sheet stays up on its failure buttons ("Restart"
                    // relaunches the app; the fallback runs the combine).
                    // Dismissing the processing sheet is fine — the marker
                    // keeps the state, and the sheet re-shows on the next
                    // (re)creation while it is set.
                    isCancelable = state.outcome == MixedFundsMigrationOutcome.STARTED ||
                        state.outcome == MixedFundsMigrationOutcome.UNCONFIRMED ||
                        awaiting || state.resultTimedOut || state.resultLanded

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
                            getString(R.string.mixed_funds_migration_started)
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

                    if (state.resultLanded && !autoDismissScheduled) {
                        autoDismissScheduled = true
                        viewLifecycleOwner.lifecycleScope.launch {
                            delay(SUCCESS_DISMISS_DELAY_MS)
                            dismissAllowingStateLoss()
                        }
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Dismissing the TIMED-OUT guidance is its acknowledgment: the
        // marker is dropped so the sheet stops re-appearing. Every other
        // dismissal leaves the marker alone — a still-processing migration
        // must re-show on the next (re)creation.
        if (viewModel.uiState.value.resultTimedOut) {
            viewModel.acknowledgeTimedOutResult()
        }
        super.onDismiss(dialog)
    }

    companion object {
        private const val TAG = "mixed_funds_migration"

        /**
         * How long the success presentation ("your funds are on the way",
         * spinner gone) stays up before the sheet auto-dismisses onto the
         * now-updated balance.
         */
        private const val SUCCESS_DISMISS_DELAY_MS = 1_500L

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
