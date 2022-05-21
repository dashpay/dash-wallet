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

package org.dash.wallet.common.ui.inputs

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.getRoundedBackground

class InputWrapper(context: Context, attrs: AttributeSet): TextInputLayout(context, attrs) {
    private var isErrorEnabled = false

    init {
        setBackgroundResource(R.drawable.input)
        isExpandedHintEnabled = false
        defaultHintTextColor = resources.getColorStateList(R.color.extra_dark_gray, null)
        setHintTextAppearance(R.style.Caption_ExtraDarkGray)
        setPadding(0, resources.getDimensionPixelOffset(R.dimen.input_vertical_padding), 0, 0)
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        super.addView(child, index, params)

        if (child is EditText) {
            child.background = null
            child.setHintTextColor(resources.getColor(R.color.input_hint_gray, null))
            child.setPadding(
                resources.getDimensionPixelOffset(R.dimen.input_horizontal_padding),
                resources.getDimensionPixelOffset(R.dimen.input_label_padding),
                resources.getDimensionPixelOffset(R.dimen.input_horizontal_padding),
                resources.getDimensionPixelOffset(R.dimen.input_vertical_padding)
            )
        }
    }

    override fun setErrorEnabled(enabled: Boolean) {
        isErrorEnabled = enabled

        if (enabled) {
            background = resources.getRoundedBackground(R.style.InputErrorBackground)
        } else {
            setBackgroundResource(R.drawable.input)
        }
    }

    override fun isErrorEnabled(): Boolean {
        return isErrorEnabled
    }
}