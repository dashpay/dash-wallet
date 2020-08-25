package org.dash.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.Gravity
import android.widget.LinearLayout
import kotlin.math.roundToInt


class ShortcutsPane(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    init {
        setBackgroundResource(R.drawable.white_background_rounded)
        minimumHeight = dpToPx(80)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun dpToPx(dp: Int): Int {
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        return (dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT)).roundToInt()
    }
}
