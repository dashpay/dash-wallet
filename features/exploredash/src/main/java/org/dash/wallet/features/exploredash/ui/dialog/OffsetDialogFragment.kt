package org.dash.wallet.features.exploredash.ui.dialog

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.dash.wallet.features.exploredash.R

open class OffsetDialogFragment : BottomSheetDialogFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setOnShowListener { dialog ->
            // apply wrap_content height
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(org.dash.wallet.common.R.id.design_bottom_sheet)
            val coordinatorLayout = bottomSheet!!.parent as CoordinatorLayout
            val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
            val marginTop = resources.getDimensionPixelSize(R.dimen.dialog_margin_top)
            bottomSheetBehavior.isFitToContents = false
            bottomSheetBehavior.expandedOffset = marginTop
            bottomSheetBehavior.peekHeight = bottomSheet.height - marginTop
            coordinatorLayout.parent.requestLayout()
        }
        view.findViewById<View>(R.id.collapse_button).setOnClickListener {
            dismiss()
        }
    }
}