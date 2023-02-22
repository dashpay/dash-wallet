package org.dash.wallet.features.exploredash.ui.bottomsheetbehavior

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior

class ExploreMapBottomSheetBehavior<V : ViewGroup>(context: Context, attrs: AttributeSet?) :
    CoordinatorLayout.Behavior<V>(context, attrs) {
    override fun onDependentViewChanged(parent: CoordinatorLayout, child: V, dependency: View): Boolean {
        val behavior = (dependency.layoutParams as CoordinatorLayout.LayoutParams).behavior as BottomSheetBehavior<*>?
        val peekHeight = behavior!!.peekHeight
        val actualPeek = if (peekHeight >= 0) peekHeight else (parent.height * 1.0 / 16.0 * 9.0).toInt()
        if (dependency.top >= actualPeek) {
            val dy: Int = dependency.top - parent.height
            child.translationY = (dy / 2).toFloat()
        }
        return super.onDependentViewChanged(parent, child, dependency)
    }

    override fun layoutDependsOn(parent: CoordinatorLayout, child: V, dependency: View): Boolean {
        return dependency is ConstraintLayout
    }
}
