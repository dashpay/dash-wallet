/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.payments_button_view.view.*


class PaymentsButtonView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {

    private val defaultSybTitleSize: Float

    init {
        inflate(context, R.layout.payments_button_view, this)
        defaultSybTitleSize = title_view.textSize
        // allow for a larger selection ripple when this row is selected
        //setBackgroundResource(R.drawable.selectable_background_dark)
        val paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.PaymentsButtonView)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.PaymentsButtonView_pb_icon, -1)
            if (drawableResId > -1) {
                val iconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (iconDrawable != null) {
                    icon_view.setImageDrawable(iconDrawable)
                }
            }
            val title = attrsArray.getString(R.styleable.PaymentsButtonView_pb_title)
            if (title != null) {
                title_view.text = title
            }
            val subTitle = attrsArray.getString(R.styleable.PaymentsButtonView_pb_sub_title)
            if (subTitle != null) {
                sub_title_view.text = subTitle
            }
            val active = attrsArray.getBoolean(R.styleable.PaymentsButtonView_pb_active, true)
            setActive(active)
        } finally {
            attrsArray.recycle()
        }
    }

    fun setActive(active: Boolean) {
        if (active) {
            val subTitleColor = ResourcesCompat.getColor(resources, R.color.dash_black, null)
            sub_title_view.setTextColor(subTitleColor)
            sub_title_view.textSize = convertPixelsToDp(defaultSybTitleSize)
        } else {
            val subTitleColor = ResourcesCompat.getColor(resources, R.color.dash_gray, null)
            sub_title_view.setTextColor(subTitleColor)
            sub_title_view.textSize = convertPixelsToDp(defaultSybTitleSize) * 0.8f
        }
        this.isEnabled = active
    }

    fun setTitle(textResId: Int) {
        title_view.setText(textResId)
    }

    fun setTitle(text: String) {
        title_view.text = text
    }

    fun setSubTitle(textResId: Int) {
        sub_title_view.setText(textResId)
    }

    fun setSubTitle(text: String) {
        sub_title_view.text = text
    }

    private fun convertPixelsToDp(px: Float): Float {
        return px / (context.resources.displayMetrics.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT)
    }
}
