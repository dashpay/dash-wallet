package org.dash.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import kotlinx.android.synthetic.main.quick_action_button.view.*


class QuickActionButton(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs, R.style.DashButton_White) {

    init {
        inflate(context, R.layout.quick_action_button, this)


        maxWidth = 300
        maxHeight = 300

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.QuickActionButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.QuickActionButton_action_icon, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    action_icon.setImageDrawable(actionIconDrawable)
                }
            }
            val actionIconSize = attrsArray.getFloat(R.styleable.QuickActionButton_action_icon_size_percent, 0.4f)
            if (actionIconSize > 0) {
                val constraintSet = ConstraintSet()
                constraintSet.clone(this)
                constraintSet.constrainPercentWidth(action_icon.id, actionIconSize)
                constraintSet.applyTo(this)
            }
            val actionText = attrsArray.getString(R.styleable.QuickActionButton_action_text)
            if (actionText != null) {
                findViewById<TextView>(R.id.action_text).text = actionText
            }
            val actionDarkMode = attrsArray.getBoolean(R.styleable.QuickActionButton_action_dark_mode, false)
            if (actionDarkMode) {
                setBackgroundResource(R.drawable.gray_button_background)
            } else {
                setBackgroundResource(R.drawable.white_button_background)
            }
            val actionActive = attrsArray.getBoolean(R.styleable.QuickActionButton_action_active, true)
            setActive(actionActive)
        } finally {
            attrsArray.recycle()
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
