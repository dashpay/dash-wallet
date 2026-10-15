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

import android.app.Activity
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Run [block] against [host], but only while this coroutine is still running
 * AND the host is still usable. Returns whether [block] ran.
 *
 * For the error handlers around the blocking receive-address reads. Those reads
 * park on a `Thread.sleep` that does not answer cancellation (up to
 * `CutoverUiDataService.BINDING_WAIT_MS` waiting for the SDK engine to bind), so
 * seconds can pass between a screen going away and the read unwinding into its
 * `catch`.
 *
 * Both checks are load-bearing, and neither implies the other:
 *
 *  * CANCELLATION. A cancelled `withContext` discards a SUCCESSFUL result and
 *    resumes the caller with `CancellationException`, but an exception thrown by
 *    its body is delivered as ITSELF — `DispatchedTask.run` consults the job
 *    only when the resumed result carries no exception. So a `catch` for a
 *    failed read runs even though the job was cancelled while the read was
 *    parked, and the handler would otherwise show a toast for a screen the user
 *    has left. A view-scoped coroutine is also cancelled when only the VIEW is
 *    destroyed — navigating forward with the fragment kept on the back stack —
 *    and there `Fragment.getContext` is still non-null, so this check is the
 *    only thing standing between that failure and a toast over the next screen.
 *  * ATTACHMENT. Cancellation is cooperative and merely requested; a
 *    fragment-scoped coroutine resuming seconds after `onDestroy` finds the
 *    fragment detached, and `requireContext()` / `requireActivity()` throw
 *    `IllegalStateException` there. Passing the NULLABLE accessor turns that
 *    crash into a no-op.
 *
 * Deliberately NOT `ensureActive()`. Every caller's handler ends at the UI it is
 * guarding — there is nothing further to abort — so raising a
 * `CancellationException` out of a `catch` block would replace plain control
 * flow with a throw for no gain. Callers that DO have work after the handler
 * already end it with their own `return`.
 */
suspend fun <T : Any> runIfStillAttached(host: T?, block: (T) -> Unit): Boolean {
    if (!currentCoroutineContext().isActive) {
        return false
    }

    val attached = host ?: return false
    block(attached)

    return true
}

/**
 * Toast a failure that surfaced inside a coroutine — shown only while this
 * fragment is still attached and the coroutine still running. Returns whether
 * it was shown.
 *
 * Takes [Fragment.getContext], never `requireContext()`: that throw is exactly
 * the crash this exists to prevent. Deliberately NOT the application context
 * either — these are "the wallet has no address yet, try again" prompts that
 * belong to the screen the user is on, so a user who has left should not be
 * interrupted by one.
 */
suspend fun Fragment.toastIfStillAttached(
    @StringRes messageRes: Int,
    duration: Int = Toast.LENGTH_LONG
): Boolean = runIfStillAttached(context) { Toast.makeText(it, messageRes, duration).show() }

/**
 * [toastIfStillAttached] for an activity. An activity context is never nulled,
 * so "still usable" is instead "not already going away" — and the cancellation
 * check is what keeps a toast from arriving after the user left.
 */
suspend fun Activity.toastIfStillAttached(
    @StringRes messageRes: Int,
    duration: Int = Toast.LENGTH_LONG
): Boolean = runIfStillAttached(takeIf { !it.isFinishing && !it.isDestroyed }) {
    Toast.makeText(it, messageRes, duration).show()
}
