package org.dash.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.roundToInt


class ShortcutButton(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {

    companion object {
        const val DEFAULT_MARGIN_DP = 10
    }

    private var squareDimen: Int = 1

    init {
        setBackgroundResource(R.drawable.white_button_background_2)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val square = MeasureSpec.getSize(heightMeasureSpec)
        if (square > squareDimen) {
            squareDimen = square
        }
        setMeasuredDimension(squareDimen, squareDimen)
    }

    private fun dpToPx(dp: Int): Int {
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        return (dp * (displayMetrics.xdpi / DisplayMetrics.DENSITY_DEFAULT)).roundToInt()
    }
}
