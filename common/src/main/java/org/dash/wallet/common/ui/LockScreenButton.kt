package org.dash.wallet.common.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import kotlinx.android.synthetic.main.lock_screen_button.view.*
import org.dash.wallet.common.R


class LockScreenButton(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs, R.style.DashButton_White) {

    init {
        inflate(context, R.layout.lock_screen_button, this)

        setBackgroundResource(R.drawable.transparent_button_background)
        orientation = VERTICAL

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.LockScreenButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.LockScreenButton_action_icon, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    action_icon.setImageDrawable(actionIconDrawable)
                }
            }
            val actionText = attrsArray.getString(R.styleable.LockScreenButton_text)
            if (actionText != null) {
                findViewById<TextView>(R.id.action_text).text = actionText
            }
            val actionPadding = attrsArray.getDimensionPixelSize(R.styleable.LockScreenButton_action_icon_padding, 0)
            action_icon.setPadding(actionPadding, actionPadding, actionPadding, actionPadding)

        } finally {
            attrsArray.recycle()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        val alpha = if (enabled) 1.0f else 0.5f
        action_icon.alpha = alpha
        action_text.alpha = alpha
    }
}
