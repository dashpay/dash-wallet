/*
 *
 *  * Copyright 2021 Dash Core Group
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.dash.wallet.common.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.dash.wallet.common.R


open class OffsetDialogFragment<T: ViewGroup> : BottomSheetDialogFragment() {
    @DrawableRes protected open val background: Int = R.drawable.white_background_rounded

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            bottomSheet?.let {
                bottomSheet.setBackgroundResource(background)
                
                val rootLayout = view.findViewById<T>(R.id.root_layout)
                rootLayout.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, bottomSheet.height)

                val coordinatorLayout = bottomSheet.parent as CoordinatorLayout
                val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

                val marginTop = resources.getDimensionPixelSize(R.dimen.offset_dialog_margin_top)
                val displayHeight = requireContext().resources.displayMetrics.heightPixels

                if (bottomSheet.height + 80 > displayHeight) {
                    // apply top offset
                    bottomSheetBehavior.isFitToContents = false
                    bottomSheetBehavior.expandedOffset = marginTop
                    bottomSheetBehavior.peekHeight = bottomSheet.height - marginTop
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
    }
}