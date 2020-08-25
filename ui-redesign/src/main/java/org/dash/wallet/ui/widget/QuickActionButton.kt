package org.dash.wallet.ui.widget

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import kotlinx.android.synthetic.main.quick_action_button.view.*
import kotlin.math.roundToInt


class QuickActionButton(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    companion object {
        val DEFAULT_MARGIN_PX = dpToPx(4)

        private fun dpToPx(dp: Int): Int {
            val density = Resources.getSystem().displayMetrics.density
            return (dp.toFloat() * density).roundToInt()
        }
    }

    var marginsSet = false

    init {
        inflate(context, R.layout.quick_action_button_2, this)
        setBackgroundResource(R.drawable.white_background_rounded)
        orientation = VERTICAL
        setPadding(DEFAULT_MARGIN_PX, DEFAULT_MARGIN_PX, DEFAULT_MARGIN_PX, DEFAULT_MARGIN_PX)

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.QuickActionButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.QuickActionButton_action_icon, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    action_icon.setImageDrawable(actionIconDrawable)
                }
            }
            val actionText = attrsArray.getString(R.styleable.QuickActionButton_action_text)
            if (actionText != null) {
                action_text.text = actionText
            }
            val actionDarkMode = attrsArray.getBoolean(R.styleable.QuickActionButton_action_dark_mode, false)
            if (actionDarkMode) {
                setBackgroundResource(R.drawable.gray_button_background_2)
            } else {
                setBackgroundResource(R.drawable.white_button_background_2)
            }
            val actionActive = attrsArray.getBoolean(R.styleable.QuickActionButton_action_active, true)
            setActive(actionActive)
        } finally {
            attrsArray.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        if (!marginsSet) {
            layoutParams = (layoutParams as MarginLayoutParams).run {
                leftMargin = DEFAULT_MARGIN_PX; topMargin = DEFAULT_MARGIN_PX
                rightMargin = DEFAULT_MARGIN_PX; bottomMargin = DEFAULT_MARGIN_PX
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
