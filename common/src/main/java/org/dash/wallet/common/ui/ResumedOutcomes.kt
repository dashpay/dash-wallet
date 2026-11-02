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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * From the fragment's onViewCreated: hands each outcome a view model retains in [outcomes] to
 * [handle], only while the view and its activity are resumed. AdaptiveDialog.showAsync shows nothing (returns null)
 * unless the activity is resumed, so an outcome taken while the screen is stopped or being
 * recreated would be lost; one that arrives then stays retained until the screen resumes.
 *
 * [take] claims the outcome (true once, e.g. a compare-and-set to null) so it is handled once
 * across recreations. [handle] then runs in the view's scope, started undispatched while still
 * resumed: a pause while its dialog is up does not cancel it, only the view's destruction does.
 */
fun <T : Any> Fragment.handleWhenResumed(
    outcomes: Flow<T?>,
    take: (T) -> Boolean,
    handle: suspend (T) -> Unit
) {
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            outcomes.filterNotNull().collect { outcome ->
                // The view resumes before the activity's lifecycle does, and showAsync checks the
                // activity's.
                requireActivity().lifecycle.withResumed {
                    if (take(outcome)) {
                        owner.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { handle(outcome) }
                    }
                }
            }
        }
    }
}
