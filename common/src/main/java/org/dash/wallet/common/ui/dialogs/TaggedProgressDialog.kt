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
import androidx.fragment.app.FragmentManager

/**
 * A screen's blocking progress dialog, kept under one [tag] in [fragmentManager] so that it
 * stays single across a recreation of the screen.
 *
 * The FragmentManager restores a progress dialog that was up when the screen was recreated;
 * it has no buttons and cannot be cancelled, and the recreated screen holds no reference to
 * it. Tracking only the dialog this instance showed would add a second one on the next
 * "loading", and leave the restored one blocking the screen after "done". So [show] replaces
 * whatever is under the tag, and [hide] removes whatever is under it.
 */
class TaggedProgressDialog(
    private val fragmentManager: () -> FragmentManager,
    private val tag: String = DEFAULT_TAG
) {
    companion object {
        const val DEFAULT_TAG = "progress"
    }

    /** The dialog this instance showed; its add may still be pending, so it is not yet found by tag. */
    private var shown: DialogFragment? = null

    fun show(message: String) {
        hide()
        val dialog = AdaptiveDialog.progress(message)
        shown = dialog
        dialog.show(fragmentManager(), tag)
    }

    fun hide() {
        // Removals are queued after any pending add, so a dialog whose show is still pending
        // goes too.
        shown?.dismissAllowingStateLoss()
        shown = null
        (fragmentManager().findFragmentByTag(tag) as? DialogFragment)?.dismissAllowingStateLoss()
    }
}
