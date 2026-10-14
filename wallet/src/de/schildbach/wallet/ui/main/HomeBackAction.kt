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

package de.schildbach.wallet.ui.main

/** What a back press on [MainActivity] does (see [homeBackAction]). */
enum class HomeBackAction {
    /** The lock screen is showing; it handles back itself. */
    IGNORE,

    /** Pop the activity's own fragment back stack. */
    POP_FRAGMENT,

    /**
     * Back on Home with the wallet as the task root: send it to the background,
     * like the system default for a root activity since Android 12, instead of
     * destroying it. Reopening is then instant; auto-logout still applies on resume.
     */
    MOVE_TASK_TO_BACK,

    /**
     * Back on Home inside another app's task (e.g. opened from a dash: link):
     * moving the task back would background that app, so just close.
     */
    FINISH
}

/**
 * The bottom-nav tabs live on the nav host's back stack, which handles back
 * itself while it can pop; this decides only what reaches [MainActivity].
 */
fun homeBackAction(
    lockScreenDisplayed: Boolean,
    fragmentBackStackCount: Int,
    isTaskRoot: Boolean
): HomeBackAction = when {
    lockScreenDisplayed -> HomeBackAction.IGNORE
    fragmentBackStackCount > 0 -> HomeBackAction.POP_FRAGMENT
    isTaskRoot -> HomeBackAction.MOVE_TASK_TO_BACK
    else -> HomeBackAction.FINISH
}
