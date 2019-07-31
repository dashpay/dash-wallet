package org.dash.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

class QuickActionButton(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs, R.style.DashButton_White) {

    private val actionIconView: ImageView

    init {
        inflate(context, R.layout.quick_action_button, this)
        orientation = VERTICAL
        gravity = Gravity.BOTTOM

        actionIconView = findViewById(R.id.action_icon)
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.QuickActionButton)
        try {
            val actionIconDrawable = attrsArray.getDrawable(R.styleable.QuickActionButton_action_icon)
            if (actionIconDrawable != null) {
                actionIconView.setImageDrawable(actionIconDrawable)
            }
            val actionIconSize = attrsArray.getDimensionPixelSize(R.styleable.QuickActionButton_action_icon_size, 0)
            if (actionIconSize > 0) {
                actionIconView.layoutParams.height = actionIconSize
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
            actionIconView.colorFilter = null
            actionIconView.alpha = 1.0f
            alpha = 1.0f
        } else {
            val tintColor = ResourcesCompat.getColor(resources, R.color.dash_gray, null)
            actionIconView.setColorFilter(tintColor)
            actionIconView.alpha = 0.7f
            alpha = 0.5f
        }
    }
}
