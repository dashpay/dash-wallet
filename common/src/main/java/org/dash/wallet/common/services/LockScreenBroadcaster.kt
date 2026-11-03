/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.dash.wallet.common.data.SingleLiveEvent

// TODO: this class is created as a transitional measure for dismissing AlertDialogs.
// Instead of using it, consider AdaptiveDialog or derive your dialog from DialogFragment.
// That way, it will be dismissed automatically.
class LockScreenBroadcaster {
    val activatingLockScreen = SingleLiveEvent<Void>()

    /**
     * Fired when the lock screen is dismissed (successful unlock). Lets feature modules restore
     * UI the lock screen tore down — e.g. re-show a result dialog that was auto-dismissed when
     * the wallet locked (LockScreenActivity dismisses all DialogFragments on lock).
     */
    val deactivatingLockScreen = SingleLiveEvent<Void>()

    private val _unlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits once the lock screen has been dismissed and the content is visible again. Unlike
     * [deactivatingLockScreen], every collector receives it, and it is not replayed.
     */
    val unlocked: SharedFlow<Unit> = _unlocked.asSharedFlow()

    fun notifyUnlocked() {
        _unlocked.tryEmit(Unit)
    }
}
