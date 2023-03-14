/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.shortcut_button.view.*
import org.dash.wallet.common.ui.getRoundedBackground

class ShortcutButton : LinearLayout {
    private var marginsSet = false
    var shouldAppear: Boolean = true
    private val padding = resources.getDimensionPixelOffset(R.dimen.shortcut_button_padding)

    init {
        inflate(context, R.layout.shortcut_button, this)
        background = resources.getRoundedBackground(R.style.SecondaryBackground)
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(padding, padding, padding, padding)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.ShortcutButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.ShortcutButton_src, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    action_icon.setImageDrawable(actionIconDrawable)
                }
            } else {
                action_icon.visibility = View.GONE
            }
            val actionText = attrsArray.getString(R.styleable.ShortcutButton_text)
            if (actionText != null) {
                action_text.text = actionText
            } else {
                action_text.visibility = View.GONE
            }

            val customTextColor = attrsArray.getColorStateList(R.styleable.ShortcutButton_shortcut_text_color)
            if (customTextColor != null) {
                action_text.setTextColor(customTextColor)
            }
            val actionActive = attrsArray.getBoolean(R.styleable.ShortcutButton_shortcut_active, true)
            setActive(actionActive)
        } finally {
            attrsArray.recycle()
        }
    }

    constructor(context: Context, iconResId: Int = 0, textResIt: Int = 0, onClickListener: OnClickListener? = null,
                textColorResId: Int = 0) : super(context) {
        if (iconResId != 0) {
            action_icon.setImageResource(iconResId)
        } else {
            action_icon.visibility = View.GONE
        }
        if (textResIt != 0) {
            action_text.setText(textResIt)
        } else {
            action_text.visibility = View.GONE
        }
        setOnClickListener(onClickListener)

        if (textColorResId != 0) {
            action_text.setTextColor(ResourcesCompat.getColor(resources, textColorResId, null))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        if (!marginsSet) {
            layoutParams = (layoutParams as MarginLayoutParams).run {
                leftMargin = padding; topMargin = padding
                rightMargin = padding; bottomMargin = padding
                this
            }
            marginsSet = true
        }
    }

    private fun setActive(active: Boolean) {
        if (active) {
            action_icon.colorFilter = null
            action_icon.alpha = 1.0f
            alpha = 1.0f
        } else {
            val tintColor = ResourcesCompat.getColor(resources, R.color.dash_gray, null)
            action_icon.setColorFilter(tintColor)
            action_icon.alpha = 0.7f
            alpha = 0.5f
        }
    }
}
