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

package org.dash.wallet.common.ui.enter_amount

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.TableLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import org.dash.wallet.common.R
import java.text.DecimalFormatSymbols

class NumericKeyboardView : TableLayout, View.OnClickListener {

    companion object {
        private val NUMERIC_BUTTONS_RES_ID = arrayOf(
                R.id.btn_1, R.id.btn_2, R.id.btn_3,
                R.id.btn_4, R.id.btn_5, R.id.btn_6,
                R.id.btn_7, R.id.btn_8, R.id.btn_9,
                R.id.btn_0)

        private val FUNCTION_BUTTONS_RES_ID = arrayOf(
                R.id.btn_function, R.id.btn_back
        )

        private val ALL_BUTTONS_RES_ID = NUMERIC_BUTTONS_RES_ID + FUNCTION_BUTTONS_RES_ID
    }

    var isDecSeparatorEnabled: Boolean = false
        set(value) {
            field = value
            val functionButton = findViewById<TextView>(R.id.btn_function)

            if (value) {
                functionButton.setTypeface(null, Typeface.BOLD)
                functionButton.text = DecimalFormatSymbols.getInstance().decimalSeparator.toString()
            } else {
                functionButton.setTypeface(null, Typeface.NORMAL)
                functionButton.setText(android.R.string.cancel)
            }
        }

    var isFunctionEnabled: Boolean = true
        set(value) {
            field = value
            findViewById<View>(R.id.btn_function).isEnabled = value
        }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs, 0, 0)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs) {
        init(attrs, defStyleAttr, defStyleAttr)
    }

    private fun init(attributeSet: AttributeSet, defStyleAttr: Int, defStyleRes: Int) {
        context.withStyledAttributes(attributeSet, R.styleable.NumericKeyboardView, defStyleAttr, defStyleRes) {
            isFunctionEnabled = getBoolean(R.styleable.NumericKeyboardView_nk_functionEnabled, isFunctionEnabled)
            isDecSeparatorEnabled = getBoolean(R.styleable.NumericKeyboardView_nk_decSeparatorEnabled, isDecSeparatorEnabled)
        }
    }

    init {
        inflate(context, R.layout.numeric_keyboard_view, this)
        isStretchAllColumns = true
        for (btnResId in ALL_BUTTONS_RES_ID) {
            findViewById<View>(btnResId).setOnClickListener(this)
        }
        findViewById<View>(R.id.btn_back).setOnLongClickListener {
            onBackClick(true)
            true
        }
    }

    var onKeyboardActionListener: OnKeyboardActionListener? = null

    override fun onClick(v: View?) {
        when (v!!.id) {
            R.id.btn_function -> onFunctionClick()
            R.id.btn_back -> onBackClick(false)
            else -> onNumberClick((v.tag as String).toInt())
        }
    }

    override fun setEnabled(enabled: Boolean) {
        for (btnResId in ALL_BUTTONS_RES_ID) {
            findViewById<View>(btnResId).isClickable = enabled
            findViewById<View>(btnResId).visibility = if(enabled) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun onFunctionClick() {
        onKeyboardActionListener?.onFunction()
    }

    private fun onBackClick(longClick: Boolean) {
        onKeyboardActionListener?.onBack(longClick)
    }

    private fun onNumberClick(number: Int) {
        onKeyboardActionListener?.onNumber(number)
    }

    interface OnKeyboardActionListener {
        fun onNumber(number: Int)
        fun onBack(longClick: Boolean)
        fun onFunction()
    }
}
