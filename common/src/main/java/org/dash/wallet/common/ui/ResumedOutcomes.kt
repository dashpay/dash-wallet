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

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * From the fragment's onViewCreated: hands each outcome a view model retains in [outcomes] to
 * [handle], only while the view and its activity are resumed and the wallet is unlocked.
 *
 * - AdaptiveDialog.showAsync shows nothing (returns null) unless the activity is resumed, and the
 *   view resumes before the activity's lifecycle does, so an outcome taken earlier would be lost.
 * - The lock screen covers a resumed screen without stopping it, and dismisses its dialogs only
 *   once, when it locks: an outcome (a failure, or a question whose answer sends) is never taken
 *   while it is up, and is tried again on each [unlocked] emission.
 * - Delivery runs from its own main-thread message, outside the FragmentManager's transaction
 *   execution, where a synchronous dialog commit (showNow) throws.
 *
 * [take] claims the outcome (true once, e.g. a compare-and-set to null) so it is handled once
 * across recreations. [handle] then runs in the view's scope, started undispatched while still
 * resumed: a pause while its dialog is up does not cancel it, only the view's destruction does.
 * It returns whether the user answered; one the lock screen dismissed unanswered (it dismisses
 * every dialog when it locks) is given back with [putBack] and shown again on unlock.
 */
fun <T : Any> Fragment.handleWhenResumed(
    outcomes: StateFlow<T?>,
    unlocked: Flow<Unit>,
    take: (T) -> Boolean,
    putBack: (T) -> Unit,
    handle: suspend (T) -> Boolean
) {
    val owner = viewLifecycleOwner
    // Dispatchers.Main, not the scope's Main.immediate: posted, so outside lifecycle dispatch.
    owner.lifecycleScope.launch(Dispatchers.Main) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            merge(outcomes.map { }, unlocked).collect {
                val activity = requireActivity()
                activity.lifecycle.withResumed {
                    val outcome = outcomes.value
                    if (outcome != null &&
                        (activity as? LockScreenHost)?.lockScreenDisplayed != true &&
                        take(outcome)
                    ) {
                        owner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            if (!handle(outcome) && (activity as? LockScreenHost)?.lockScreenDisplayed == true) {
                                putBack(outcome)
                            }
                        }
                    }
                }
            }
        }
    }
}
