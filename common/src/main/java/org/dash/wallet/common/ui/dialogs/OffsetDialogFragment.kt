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

package org.dash.wallet.common.ui.dialogs

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.LayoutRes
import androidx.annotation.StyleRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

open class OffsetDialogFragment(@LayoutRes private val layout: Int) : BottomSheetDialogFragment() {
    protected open val forceExpand: Boolean = false
    @StyleRes protected open val backgroundStyle: Int = R.style.SecondaryBackground

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        setStyle(STYLE_NORMAL, R.style.OffsetDialog)
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.setOnShowListener { dialog ->
            if (!this@OffsetDialogFragment.isAdded) {
                return@setOnShowListener
            }

            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            bottomSheet?.let {
                bottomSheet.background = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.offset_dialog_background,
                    resources.newTheme().apply { applyStyle(backgroundStyle, true) }
                )

                val marginTop = resources.getDimensionPixelSize(R.dimen.offset_dialog_margin_top)
                val displayHeight = requireContext().resources.displayMetrics.heightPixels
                val height = if (forceExpand) displayHeight - marginTop else bottomSheet.height
                view.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height)

                val statusBar = Rect()
                val window = requireActivity().window
                window.decorView.getWindowVisibleDisplayFrame(statusBar)

                val coordinatorLayout = bottomSheet.parent as CoordinatorLayout
                val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

                if (forceExpand || (bottomSheet.height + marginTop + statusBar.top) > displayHeight) {
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

        view.findViewById<View?>(R.id.collapse_button)?.setOnClickListener {
            dismiss()
        }

        dialog?.window?.callback = UserInteractionAwareCallback(dialog?.window?.callback, requireActivity())
    }

    fun show(activity: FragmentActivity) {
        activity.lifecycleScope.launchWhenResumed {
            show(activity.supportFragmentManager, "offset_dialog")
        }
    }
}
