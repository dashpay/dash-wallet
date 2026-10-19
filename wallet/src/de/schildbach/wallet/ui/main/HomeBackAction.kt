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
     * Pop the nav host's back stack: a sub-screen goes back to its parent,
     * and a bottom-nav tab (More, Payments, …) goes back to Home.
     */
    POP_NAVIGATION,

    /**
     * Back on Home with the wallet as the task root: swallow it — the wallet
     * is neither closed nor sent to the background.
     */
    CONSUME,

    /**
     * Back on Home inside another app's task (e.g. opened from a dash: link):
     * swallowing back would strand the user in the wallet, so just close and
     * return to that app.
     */
    FINISH
}

/**
 * Decides what a back press that reaches [MainActivity] does.
 *
 * The nav host's own back callback is NOT relied on for the tabs:
 * [de.schildbach.wallet.ui.LockScreenActivity]'s activity-scoped callback is
 * re-added on every start, so after the activity has been stopped once it
 * takes priority and every back press lands here — which is how back on More
 * used to leave the app. [navCanPop] (the nav back stack has a previous
 * entry) covers that path.
 */
fun homeBackAction(
    lockScreenDisplayed: Boolean,
    fragmentBackStackCount: Int,
    navCanPop: Boolean,
    isTaskRoot: Boolean
): HomeBackAction = when {
    lockScreenDisplayed -> HomeBackAction.IGNORE
    fragmentBackStackCount > 0 -> HomeBackAction.POP_FRAGMENT
    navCanPop -> HomeBackAction.POP_NAVIGATION
    isTaskRoot -> HomeBackAction.CONSUME
    else -> HomeBackAction.FINISH
}
