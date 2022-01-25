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

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.RecyclerView

class ListDividerDecorator(
    private val dividerDrawable: Drawable,
    private val showAfterLast: Boolean = false,
    private val divideDifferentTypes: Boolean = false,
    private val marginStart: Int = 0,
    private val marginEnd: Int = 0
) : RecyclerView.ItemDecoration() {

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)

        val itemCount = (parent.adapter?.itemCount ?: 0)

        if (itemCount == 0) {
            return
        }

        for (i in 0 until parent.childCount) {
            if (!showAfterLast && (i >= parent.childCount - 1 || i >= itemCount - 1)) {
                continue
            }

            if (!divideDifferentTypes && i < itemCount - 1) {
                val viewType = parent.adapter?.getItemViewType(i)
                val nextViewType = parent.adapter?.getItemViewType(i + 1)

                if (viewType != nextViewType) {
                    continue
                }
            }

            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val topBound = child.bottom + params.bottomMargin
            val bottomBound = topBound + dividerDrawable.intrinsicHeight

            dividerDrawable.setBounds(marginStart, topBound, parent.width - marginEnd, bottomBound)
            dividerDrawable.draw(c)
        }
    }
}