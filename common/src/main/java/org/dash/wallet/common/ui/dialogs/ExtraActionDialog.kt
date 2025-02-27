/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.ui.dialogs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.R

class ExtraActionDialog(
    @LayoutRes private val layout: Int = R.layout.dialog_extra_action
) : AdaptiveDialog(layout) {
    companion object {
        private const val ICON_RES_ARG = "icon_res"
        private const val TITLE_ARG = "title"
        private const val MESSAGE_ARG = "message"
        private const val POS_BUTTON_ARG = "positive_text"
        private const val NEG_BUTTON_ARG = "negative_text"
        private const val EXTRA_MESSAGE_BUTTON_ARG = "extra_message_text"

        @JvmStatic
        fun create(
            @DrawableRes icon: Int?,
            title: String,
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null,
            extraMessage: String? = null
        ): ExtraActionDialog {
            return custom(
                R.layout.dialog_extra_action,
                icon, title, message, negativeButtonText, positiveButtonText, extraMessage
            )
        }

        @JvmStatic
        fun custom(
            @LayoutRes layout: Int,
            @DrawableRes icon: Int?,
            title: String?,
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null,
            extraMessage: String? = null
        ): ExtraActionDialog {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putString(POS_BUTTON_ARG, positiveButtonText)
                putString(EXTRA_MESSAGE_BUTTON_ARG, extraMessage)
            }
            return ExtraActionDialog(layout).apply {
                arguments = args
            }
        }
    }

    private var onExtraMessageListener: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val extraMessageView: TextView = view.findViewById(R.id.dialog_extra_message)
        showIfNotEmpty(extraMessageView, EXTRA_MESSAGE_BUTTON_ARG)
        extraMessageView.setOnClickListener {
            onExtraMessageAction()
        }
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit, onExtraMessageAction: () -> Unit) {
        onResultListener = onResult
        onExtraMessageListener = onExtraMessageAction
        show(activity.supportFragmentManager, "extra_action_dialog")
    }

    private fun onExtraMessageAction() {
        onExtraMessageListener?.invoke()
        onExtraMessageListener = null
        dismiss()
    }
}