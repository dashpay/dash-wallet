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
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

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

    override val expandToContent = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismissAllowingStateLoss() }
        binding.retryButton.setOnClickListener {
            viewModel.retryNow()
            render(SdkBindBlocker.OTHER)
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
        private const val TAG = "sdk_bind_pending"

        fun showOnce(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            SdkBindPendingDialogFragment().show(fm, TAG)
        }

        fun dismissIfShown(activity: FragmentActivity) {
            val fm = activity.supportFragmentManager
            (fm.findFragmentByTag(TAG) as? SdkBindPendingDialogFragment)?.dismissAllowingStateLoss()
        }
    }
}
