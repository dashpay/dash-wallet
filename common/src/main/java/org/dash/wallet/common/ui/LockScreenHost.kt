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

package org.dash.wallet.common.ui

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.NavHostFragment

/** An activity that can cover its content with the wallet's lock screen. */
interface LockScreenHost {
    /** True while the lock screen covers the activity's content. */
    val lockScreenDisplayed: Boolean
}

/**
 * When the lock screen comes up: tells each [LockScreenAware] fragment, and dismisses every
 * dialog in [fragmentManager] and its navigation hosts. Transactions still queued are run first,
 * so a dialog whose show() was committed but not yet executed (AdaptiveDialog.showAsync) is
 * dismissed too, rather than appearing above the PIN screen once its transaction runs.
 */
fun dismissDialogsForLockScreen(fragmentManager: FragmentManager) {
    try {
        fragmentManager.executePendingTransactions()
    } catch (e: IllegalStateException) {
        // Already executing transactions (or destroyed): nothing is left queued to run here.
    }
    fragmentManager.fragments.forEach { fragment ->
        // check to see if the activity is valid and fragment is added to its activity
        if (fragment.activity != null && fragment.isAdded) {
            if (fragment is LockScreenAware) {
                fragment.onLockScreenActivated()
            }

            if (fragment is DialogFragment) {
                fragment.dismissAllowingStateLoss()
            } else if (fragment is NavHostFragment) {
                dismissDialogsForLockScreen(fragment.childFragmentManager)
            }
        }
    }
}
