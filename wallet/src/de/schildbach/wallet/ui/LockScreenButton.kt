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

package de.schildbach.wallet.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.LockScreenButtonBinding
import org.dash.wallet.common.ui.setRoundedRippleBackground

class LockScreenButton(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val binding = LockScreenButtonBinding.inflate(LayoutInflater.from(context), this)

    init {
        setRoundedRippleBackground(R.style.TertiaryButtonTheme_Large_White)
        orientation = VERTICAL

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.LockScreenButton)
        try {
            val drawableResId = attrsArray.getResourceId(R.styleable.LockScreenButton_action_icon, -1)
            if (drawableResId > -1) {
                val actionIconDrawable = AppCompatResources.getDrawable(context, drawableResId)
                if (actionIconDrawable != null) {
                    binding.actionIcon.setImageDrawable(actionIconDrawable)
                }
            }
            val actionText = attrsArray.getString(R.styleable.LockScreenButton_text)
            if (actionText != null) {
                findViewById<TextView>(R.id.action_text).text = actionText
            }
            val actionPadding = attrsArray.getDimensionPixelSize(R.styleable.LockScreenButton_action_icon_padding, 0)
            binding.actionIcon.setPadding(actionPadding, actionPadding, actionPadding, actionPadding)
        } finally {
            attrsArray.recycle()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        val alpha = if (enabled) 1.0f else 0.5f
        binding.actionIcon.alpha = alpha
        binding.actionText.alpha = alpha
    }
}
