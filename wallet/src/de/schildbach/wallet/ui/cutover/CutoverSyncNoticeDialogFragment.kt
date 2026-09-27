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
package de.schildbach.wallet.ui.cutover

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogCutoverSyncNoticeBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

/**
 * The one-time explainer shown on the FIRST launch after upgrading into the
 * cutover build, before the user can reach a state they would misread.
 *
 * It says the three things that window needs: sending is unavailable until
 * the sync completes, the funds are safe, and the wait happens only once.
 * Live scan progress rides along (the same 1 Hz feed the home header uses).
 *
 * Shown exactly once — dismissal acknowledges it and clears the persisted
 * marker ([de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
 * .CUTOVER_UPGRADE_NOTICE_PENDING]). The marker is armed only on the UPGRADE
 * seam, so a fresh install or a restore (which already comes with its own
 * "syncing" expectation) never sees this.
 */
@AndroidEntryPoint
class CutoverSyncNoticeDialogFragment :
    OffsetDialogFragment(R.layout.dialog_cutover_sync_notice) {

    private val binding by viewBinding(DialogCutoverSyncNoticeBinding::bind)
    private val viewModel by viewModels<CutoverSyncNoticeViewModel>()

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.acknowledgeButton.setOnClickListener { dismissAllowingStateLoss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.progress = state.syncPercent
                    binding.progressText.text = when {
                        state.synced -> getString(R.string.cutover_sync_notice_complete)
                        state.syncPercent <= 0 -> getString(R.string.cutover_sync_notice_progress_starting)
                        else -> getString(R.string.cutover_sync_notice_progress, state.syncPercent)
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // ANY dismissal is the acknowledgment (button, back press, swipe) —
        // the sheet is informational, so there is nothing to re-ask.
        viewModel.acknowledge()
        super.onDismiss(dialog)
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(CutoverSyncNoticeDialogFragment::class.java)
        private const val TAG = "cutover_sync_notice"

        /**
         * Show unless it is already up. Safe to call more than once — the tag
         * check makes it idempotent across the lifecycle churn a startup
         * trigger goes through.
         */
        /**
         * @return true when the sheet is now showing (or was already), false
         *   when it could not be shown and the caller must keep its pending
         *   marker so a later attempt can succeed. Measured on the 2026-09-16
         *   emulator upgrade test: the explainer was armed on the upgrade
         *   launch and never appeared, because the caller cleared its pending
         *   flag before this returned and the fragment manager had already
         *   saved state.
         */
        fun showOnce(activity: FragmentActivity): Boolean {
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(TAG) != null) return true
            if (fm.isStateSaved) {
                log.info("cutover sync explainer not shown yet: fragment state is saved; staying pending")
                return false
            }
            return runCatching { CutoverSyncNoticeDialogFragment().show(fm, TAG) }
                .onFailure { log.warn("cutover sync explainer could not be shown; staying pending", it) }
                .isSuccess
        }
    }
}
