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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

open class OffsetDialogFragment(@LayoutRes private val layout: Int) : BottomSheetDialogFragment() {
    protected open val forceExpand: Boolean = false
    @StyleRes protected open val backgroundStyle: Int = R.style.SecondaryBackground

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep style, just move it here
        setStyle(STYLE_NORMAL, R.style.OffsetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Edge-to-edge bottom handling for gesture nav / Android 15
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                v.paddingBottom + sys.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        dialog?.setOnShowListener { dialog ->
            if (!this@OffsetDialogFragment.isAdded) return@setOnShowListener

            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.background = ResourcesCompat.getDrawable(
                    resources,
                    R.drawable.offset_dialog_background,
                    resources.newTheme().apply { applyStyle(backgroundStyle, true) }
                )

                val marginTop = resources.getDimensionPixelSize(R.dimen.offset_dialog_margin_top)

                // Only use full height for expanded dialogs
                if (forceExpand) {
                    sheet.layoutParams = (sheet.layoutParams as CoordinatorLayout.LayoutParams).apply {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                }

                BottomSheetBehavior.from(sheet).apply {
                    isFitToContents = false
                    skipCollapsed = true

                    if (forceExpand) {
                        expandedOffset = marginTop
                        state = BottomSheetBehavior.STATE_EXPANDED
                    } else {
                        // still allow partially visible sheet but use same offset logic
                        expandedOffset = marginTop
                    }
                }

                (sheet.parent as? CoordinatorLayout)?.parent?.requestLayout()
            }
        }

        view.findViewById<View?>(R.id.collapse_button)?.setOnClickListener {
            dismiss()
        }

        dialog?.window?.callback =
            UserInteractionAwareCallback(dialog?.window?.callback, requireActivity())
    }

    fun show(activity: FragmentActivity) {
        activity.lifecycleScope.launchWhenResumed {
            show(activity.supportFragmentManager, "offset_dialog")
        }
    }
}
