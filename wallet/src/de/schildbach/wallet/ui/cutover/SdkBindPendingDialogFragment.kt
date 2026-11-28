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

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.SdkBindBlocker
import de.schildbach.wallet.service.platform.sdk.SdkBindPendingTexts
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogSdkBindPendingBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory

/**
 * The foreground half of the "SDK setup pending" surface
 * (docs/upgrade-memory-and-sync-plan.md, Phase 1a item 3). Shown by
 * [de.schildbach.wallet.ui.main.MainActivity] whenever
 * [SdkBindPendingViewModel.blocker] is non-null while the activity is
 * started, so it comes back on every return to the app until the bind
 * succeeds; it dismisses itself the moment the blocker clears.
 *
 * With the app on screen the device is unlocked, so a DEVICE_LOCKED blocker
 * is about to be retried (BlockchainServiceImpl drives a retry on the
 * foreground edge) and the sheet reads as "finishing". The blockers that
 * need the user ([SdkBindBlocker.needsUser]) show the error text and a
 * retry button.
 */
@AndroidEntryPoint
class SdkBindPendingDialogFragment :
    OffsetDialogFragment(R.layout.dialog_sdk_bind_pending) {

    private val binding by viewBinding(DialogSdkBindPendingBinding::bind)
    private val viewModel by viewModels<SdkBindPendingViewModel>()

    /** @see showRetryInFlight */
    private var retryFeedbackJob: Job? = null

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismissAllowingStateLoss() }
        binding.retryButton.setOnClickListener {
            viewModel.retryNow()
            showRetryInFlight()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.blocker.collect { blocker ->
                    if (blocker == null) {
                        dismissAllowingStateLoss()
                    } else {
                        render(blocker)
                    }
                }
            }
        }
    }

    /**
     * Acknowledge the retry tap, then GUARANTEE the controls come back.
     *
     * Rendering OTHER shows the spinner and hides the retry button, because
     * OTHER does not need the user. That was previously the whole story, and it
     * stranded the sheet: if the retry produced the SAME blocker, the service
     * assigned an equal value to its StateFlow, which conflates, so no emission
     * reached the collector and nothing re-rendered. The user was left with a
     * spinner and no way to try again short of dismissing the sheet.
     *
     * So the in-flight state is TIME-BOXED rather than event-terminated. A real
     * emission — a different blocker, or null on success — still wins, because
     * the collector renders or dismisses on it and this job's late write is
     * cancelled below by the next tap or by view destruction. If nothing
     * arrives, the current blocker is re-rendered from the StateFlow's value
     * and the button returns.
     */
    private fun showRetryInFlight() {
        retryFeedbackJob?.cancel()
        render(SdkBindBlocker.OTHER)
        retryFeedbackJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(RETRY_FEEDBACK_MS)
            viewModel.blocker.value?.let { render(it) }
        }
    }

    private fun render(blocker: SdkBindBlocker) {
        // With the app on screen the device is unlocked, so a "device locked"
        // classification is stale by definition: the foreground retry is
        // already running. Show it as finishing rather than asking the user
        // to unlock a phone they are holding unlocked.
        val effective = if (blocker == SdkBindBlocker.DEVICE_LOCKED) SdkBindBlocker.OTHER else blocker
        val texts = SdkBindPendingTexts.forBlocker(requireContext(), effective)
        binding.title.text = texts.title
        binding.message.text = texts.message
        binding.progressBar.isVisible = !effective.needsUser
        binding.retryButton.isVisible = effective.needsUser
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkBindPendingDialogFragment::class.java)
        private const val TAG = "sdk_bind_pending"

        /** How long the retry tap shows as in flight before the controls return. */
        private const val RETRY_FEEDBACK_MS = 2_000L

        /**
         * @return whether the sheet is now on screen — shown here, or already
         *   showing. False means the showing was REFUSED (saved fragment
         *   state), and the caller must keep it pending: the blocker that
         *   triggered this is a StateFlow value that will not re-emit on its
         *   own. Same contract as
         *   [CutoverSyncNoticeDialogFragment.showOnce].
         */
        fun showOnce(activity: FragmentActivity): Boolean {
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(TAG) != null) return true
            if (fm.isStateSaved) {
                log.info("SDK bind pending sheet not shown yet: fragment state is saved; staying pending")
                return false
            }
            return runCatching { SdkBindPendingDialogFragment().show(fm, TAG) }
                .onFailure { log.warn("SDK bind pending sheet could not be shown; staying pending", it) }
                .isSuccess
        }

        fun dismissIfShown(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(TAG) as? SdkBindPendingDialogFragment)?.dismissAllowingStateLoss()
        }
    }
}
