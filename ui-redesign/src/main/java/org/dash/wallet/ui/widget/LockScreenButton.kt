package org.dash.wallet.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import kotlinx.android.synthetic.main.quick_action_button.view.*


class LockScreenButton(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs, R.style.DashButton_White) {

    init {
        inflate(context, R.layout.lock_screen_button, this)

        maxWidth = 300
        maxHeight = 300

        setBackgroundResource(R.drawable.selectable_background_light)

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.LockScreenButton)
        try {
            val actionIconDrawable = attrsArray.getDrawable(R.styleable.LockScreenButton_action_icon)
            if (actionIconDrawable != null) {
                action_icon.setImageDrawable(actionIconDrawable)
            }
            val actionIconSize = attrsArray.getFloat(R.styleable.LockScreenButton_action_icon_size_percent, 0.4f)
            if (actionIconSize > 0) {
                val constraintSet = ConstraintSet()
                constraintSet.clone(this)
                constraintSet.constrainPercentWidth(action_icon.id, actionIconSize)
                constraintSet.applyTo(this)
            }
            val actionText = attrsArray.getString(R.styleable.LockScreenButton_text)
            if (actionText != null) {
                findViewById<TextView>(R.id.action_text).text = actionText
            }
        } finally {
            attrsArray.recycle()
        }
    }
}
