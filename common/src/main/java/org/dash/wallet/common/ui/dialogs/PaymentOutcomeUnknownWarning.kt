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

package org.dash.wallet.common.ui.dialogs

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.LockScreenHost

/**
 * The "this payment may have been sent" warning a payment screen keeps up while its terminal
 * outcome-unknown flag is set.
 *
 * Only the user's Close acknowledges it and runs [onClose] (leaving the screen). Any other
 * dismissal — the lock screen dismisses every dialog on lock or auto-logout, a recreation tears
 * it down — keeps the screen, and the warning is shown again when the screen resumes or the
 * wallet is unlocked. A copy restored after a recreation has lost its Close action, so it is
 * replaced. It cannot be cancelled by Back or an outside tap.
 */
class PaymentOutcomeUnknownWarning(
    private val fragment: Fragment,
    private val onClose: () -> Unit
) {
    companion object {
        const val TAG = "payment_outcome_unknown"

        /**
         * Whether a result of the warning is the user's Close: the dialog's button reports
         * true/false, while a dismissal it did not ask for (lock screen, recreation) reports null.
         */
        fun isExplicitClose(result: Boolean?): Boolean = result != null
    }

    /** The warning this screen shows (or has queued), whose Close still works. */
    private var shown: AdaptiveDialog? = null

    /**
     * From the fragment's onViewCreated: while the view is resumed, shows the warning whenever
     * [outcomeUnknown] is set and it is not already up — on resume, when the flag is set, and on
     * each [unlocked] emission. [onOutcomeUnknown] runs first each time (e.g. disable Confirm).
     */
    fun keepShownWhile(outcomeUnknown: StateFlow<Boolean>, unlocked: Flow<Unit>, onOutcomeUnknown: () -> Unit) {
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                merge(outcomeUnknown.map { }, unlocked).collect {
                    if (outcomeUnknown.value) {
                        onOutcomeUnknown()
                        showIfNeeded()
                    }
                }
            }
        }
    }

    private fun showIfNeeded() {
        val activity = fragment.activity ?: return
        if ((activity as? LockScreenHost)?.lockScreenDisplayed == true) {
            // Never over the lock screen: shown again on unlock.
            return
        }
        if (shown != null) {
            return
        }
        (activity.supportFragmentManager.findFragmentByTag(TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        val warning = AdaptiveDialog.create(
            R.drawable.ic_warning,
            activity.getString(R.string.payment_submission_pending_title),
            activity.getString(R.string.payment_outcome_unknown_message),
            activity.getString(R.string.button_close)
        ).apply { isCancelable = false }
        shown = warning
        warning.show(activity, TAG) { result ->
            if (shown === warning) {
                shown = null
            }
            if (isExplicitClose(result) && fragment.isAdded) {
                onClose()
            }
        }
    }
}
