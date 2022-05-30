/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.ui.decorators

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class ListMarginDecorator(private val marginLeft: Int, private val marginRight: Int,
                          private val marginTop: Int, private val marginBottom: Int): RecyclerView.ItemDecoration() {
    
    override fun getItemOffsets(outRect: Rect, view: View,
                                parent: RecyclerView, state: RecyclerView.State) {
        with(outRect) {
            left = marginLeft
            top = marginTop
            right = marginRight

            parent.adapter?.itemCount?.let {
                if (parent.getChildAdapterPosition(view) == (it - 1)) {
                    bottom = marginBottom
                }
            }
        }
    }
}