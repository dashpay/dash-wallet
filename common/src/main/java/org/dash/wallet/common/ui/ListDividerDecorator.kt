package org.dash.wallet.common.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView


class ListDividerDecorator(
    private val dividerDrawable: Drawable,
    private val showAfterLast: Boolean = false,
    @LayoutRes private val headerViewType: Int? = null
) : RecyclerView.ItemDecoration() {

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)

        if ((parent.adapter?.itemCount ?: 0) == 0) {
            return
        }

        val divider = Rect()
        dividerDrawable.getPadding(divider)

        for (i in 0 until parent.childCount) {
            if (i >= parent.childCount - 1 && !showAfterLast) {
                continue
            }

            if (headerViewType != null) {
                val viewType = parent.adapter?.getItemViewType(i)

                if (viewType == headerViewType) {
                    continue
                }
            }

            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val topBound = child.bottom + params.bottomMargin
            val bottomBound = topBound + dividerDrawable.intrinsicHeight
            dividerDrawable.setBounds(0, topBound, parent.width, bottomBound)
            dividerDrawable.draw(c)

        }
    }
}