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

package org.dash.wallet.common.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

open class OffsetDialogFragment<T: ViewGroup> : BottomSheetDialogFragment() {
    companion object {
        private const val FULLSCREEN_DIFF = 80
    }

    protected open val forceExpand: Boolean = false
    @DrawableRes protected open val background: Int = R.drawable.white_background_rounded

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            bottomSheet?.let {
                bottomSheet.setBackgroundResource(background)
                val displayHeight = requireContext().resources.displayMetrics.heightPixels

                val rootLayout = view.findViewById<T>(R.id.root_layout)
                    ?: throw NoSuchElementException("Offset dialog root must have 'root_layout' id")

                val height = if (forceExpand) displayHeight - FULLSCREEN_DIFF else bottomSheet.height
                rootLayout.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, height)

                val coordinatorLayout = bottomSheet.parent as CoordinatorLayout
                val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
                val marginTop = resources.getDimensionPixelSize(R.dimen.offset_dialog_margin_top)

                if (forceExpand || bottomSheet.height + FULLSCREEN_DIFF > displayHeight) {
                    // apply top offset
                    bottomSheetBehavior.isFitToContents = false
                    bottomSheetBehavior.expandedOffset = marginTop

                    if (forceExpand) {
                        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        bottomSheetBehavior.peekHeight = bottomSheet.height - marginTop
                    }
                } else {
                    // apply wrap_content height
                    bottomSheetBehavior.peekHeight = bottomSheet.height
                }

                coordinatorLayout.parent.requestLayout()
            }
        }
        view.findViewById<View>(R.id.collapse_button).setOnClickListener {
            dismiss()
        }
        dialog?.window?.callback = UserInteractionAwareCallback(dialog?.window?.callback, requireActivity())
    }
}