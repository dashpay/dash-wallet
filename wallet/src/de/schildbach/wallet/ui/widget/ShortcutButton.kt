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
import android.content.res.Resources
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.tbuonomo.viewpagerdotsindicator.setPaddingVertical
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ShortcutButtonBinding
import kotlin.math.roundToInt

class ShortcutButton : LinearLayout {

    companion object {
        val DEFAULT_MARGIN_PX = dpToPx(4)

        private fun dpToPx(dp: Int): Int {
            val density = Resources.getSystem().displayMetrics.density
            return (dp.toFloat() * density).roundToInt()
        }
    }

    private var marginsSet = false
    var shouldAppear: Boolean = true
    private val binding = ShortcutButtonBinding.inflate(LayoutInflater.from(context), this)

    init {
        setBackgroundResource(R.drawable.white_button_background_no_shadow)
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPaddingVertical(DEFAULT_MARGIN_PX)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.ShortcutButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.ShortcutButton_src, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    binding.actionIcon.setImageDrawable(actionIconDrawable)
                }
            } else {
                binding.actionIcon.visibility = View.GONE
            }
            val actionText = attrsArray.getString(R.styleable.ShortcutButton_text)
            if (actionText != null) {
                binding.actionText.text = actionText
            } else {
                binding.actionText.visibility = View.GONE
            }
            val backgroundResId = attrsArray.getResourceId(
                R.styleable.ShortcutButton_background,
                R.drawable.white_button_background_no_shadow
            )
            setBackgroundResource(backgroundResId)
            val customTextColor = attrsArray.getColorStateList(R.styleable.ShortcutButton_shortcut_text_color)
            if (customTextColor != null) {
                binding.actionText.setTextColor(customTextColor)
            }
            val actionActive = attrsArray.getBoolean(R.styleable.ShortcutButton_shortcut_active, true)
            setActive(actionActive)
        } finally {
            attrsArray.recycle()
        }
    }

    constructor(
        context: Context,
        iconResId: Int = 0,
        textResIt: Int = 0,
        onClickListener: OnClickListener? = null,
        backgroundResId: Int = 0,
        textColorResId: Int = 0
    ) : super(context) {
        if (iconResId != 0) {
            binding.actionIcon.setImageResource(iconResId)
        } else {
            binding.actionIcon.visibility = View.GONE
        }
        if (textResIt != 0) {
            binding.actionText.setText(textResIt)
        } else {
            binding.actionText.visibility = View.GONE
        }
        setOnClickListener(onClickListener)
        if (backgroundResId != 0) {
            setBackgroundResource(backgroundResId)
        }
        if (textColorResId != 0) {
            binding.actionText.setTextColor(ResourcesCompat.getColor(resources, textColorResId, null))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        if (!marginsSet) {
            layoutParams = (layoutParams as MarginLayoutParams).run {
                topMargin = DEFAULT_MARGIN_PX
                bottomMargin = DEFAULT_MARGIN_PX
                this
            }
            marginsSet = true
        }
    }

    private fun setActive(active: Boolean) {
        if (active) {
            binding.actionIcon.colorFilter = null
            binding.actionIcon.alpha = 1.0f
            alpha = 1.0f
        } else {
            val tintColor = ResourcesCompat.getColor(resources, R.color.dash_gray, null)
            binding.actionIcon.setColorFilter(tintColor)
            binding.actionIcon.alpha = 0.7f
            alpha = 0.5f
        }
    }
}
