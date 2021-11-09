/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.common.ui

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.recyclerview.widget.RecyclerView

class ListDividerDecorator(
    private val dividerDrawable: Drawable,
    private val showAfterLast: Boolean = false,
    private val divideDifferentTypes: Boolean = false,
    private val marginStart: Int = 0
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

            dividerDrawable.setBounds(marginStart, topBound, parent.width, bottomBound)
            dividerDrawable.draw(c)
        }
    }
}