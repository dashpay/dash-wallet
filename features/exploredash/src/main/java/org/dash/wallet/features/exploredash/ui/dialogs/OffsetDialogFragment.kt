package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.dash.wallet.features.exploredash.R


open class OffsetDialogFragment : BottomSheetDialogFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.setOnShowListener { dialog ->
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(org.dash.wallet.common.R.id.design_bottom_sheet)
            val rootLayout = view.findViewById<LinearLayout>(R.id.root_layout)
            rootLayout.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                bottomSheet?.height ?: FrameLayout.LayoutParams.MATCH_PARENT)

            val coordinatorLayout = bottomSheet!!.parent as CoordinatorLayout
            val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

            val marginTop = resources.getDimensionPixelSize(R.dimen.dialog_margin_top)
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
        view.findViewById<View>(R.id.collapse_button).setOnClickListener {
            dismiss()
        }
    }
}