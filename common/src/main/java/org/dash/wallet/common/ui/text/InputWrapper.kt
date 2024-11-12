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

package org.dash.wallet.common.ui.text

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import androidx.core.view.updateMarginsRelative
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputLayout
import org.dash.wallet.common.R

class InputWrapper(context: Context, attrs: AttributeSet): TextInputLayout(context, attrs) {
    private var isErrorEnabled = false

    init {
        setBackgroundResource(R.drawable.input)
        isExpandedHintEnabled = false
        defaultHintTextColor = resources.getColorStateList(R.color.content_secondary, null)
        setHintTextAppearance(R.style.Overline_Secondary)
        setCounterTextAppearance(R.style.Overline_Secondary)
        setCounterOverflowTextAppearance(R.style.Overline_Red)
        initStyle(attrs, R.styleable.TextInputLayout)
        setPadding()

        endIconMode = END_ICON_CUSTOM
        endIconDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_clear_input, null)
        setEndIconOnClickListener {
            editText?.setText("")  // Clear text in EditText
        }
    }

    @SuppressLint("PrivateResource")
    private fun initStyle(set: AttributeSet, attrs: IntArray) {
        context.withStyledAttributes(set, attrs, 0) {
            val drawable = getDrawable(R.styleable.TextInputLayout_endIconDrawable)
            endIconDrawable = drawable ?: ResourcesCompat.getDrawable(resources, R.drawable.ic_clear_input, null)
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        super.addView(child, index, params)

        if (child is EditText) {
            child.background = null
            child.setHintTextColor(resources.getColor(R.color.input_hint_gray, null))

            val endPadding = if (isCounterEnabled) {
                0
            } else {
                resources.getDimensionPixelOffset(R.dimen.input_horizontal_padding)
            }

            child.setPaddingRelative(
                resources.getDimensionPixelOffset(R.dimen.input_horizontal_padding),
                resources.getDimensionPixelOffset(R.dimen.input_top_padding),
                endPadding,
                resources.getDimensionPixelOffset(R.dimen.input_bottom_padding)
            )

            if (isHintEnabled) {
                val childParams = (child.layoutParams as MarginLayoutParams)
                childParams.updateMarginsRelative(top = resources.getDimensionPixelOffset(R.dimen.input_label_margin))
            }

            child.doOnTextChanged { text, _, _, _ ->
                if (isCounterEnabled) {
                    setErrorEnabled((text?.length ?: 0) > counterMaxLength)
                }
            }
        }
    }

    override fun setErrorEnabled(enabled: Boolean) {
        isErrorEnabled = enabled

        if (enabled) {
            setBackgroundResource(R.drawable.input_error)
        } else {
            setBackgroundResource(R.drawable.input)
        }
    }

    override fun isErrorEnabled(): Boolean {
        return isErrorEnabled
    }

    override fun setCounterEnabled(enabled: Boolean) {
        super.setCounterEnabled(enabled)

        if (enabled) {
            val counter = findViewById<TextView>(com.google.android.material.R.id.textinput_counter)
            val params = (counter.layoutParams as MarginLayoutParams)
            params.updateMarginsRelative(
                top = resources.getDimensionPixelOffset(R.dimen.char_counter_top_padding),
                end = resources.getDimensionPixelOffset(R.dimen.char_counter_end_padding)
            )
        }
    }

    private fun setPadding() {
        var topPadding = 0
        var startPadding = 0
        var endPadding = 0
        var bottomPadding = 0

        if (isHintEnabled) {
            topPadding = resources.getDimensionPixelOffset(R.dimen.input_bottom_padding)
        }

        if (isCounterEnabled) {
            bottomPadding = resources.getDimensionPixelOffset(R.dimen.char_counter_bottom_padding)
        }

        setPaddingRelative(startPadding, topPadding, endPadding, bottomPadding)
    }
}
