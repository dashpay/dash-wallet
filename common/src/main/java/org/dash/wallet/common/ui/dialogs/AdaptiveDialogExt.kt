package org.dash.wallet.common.ui.dialogs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.fragment.app.FragmentActivity
import org.dash.wallet.common.R

class AdaptiveDialogExt(@LayoutRes private val layout: Int) : AdaptiveDialog(layout) {
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
        ): AdaptiveDialogExt {
            return custom(
                R.layout.dialog_adaptive_ext,
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
        ): AdaptiveDialogExt {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putString(POS_BUTTON_ARG, positiveButtonText)
                putString(EXTRA_MESSAGE_BUTTON_ARG, extraMessage)
            }
            return AdaptiveDialogExt(layout).apply {
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
        show(activity.supportFragmentManager, "adaptive_dialog")
    }

    private fun onExtraMessageAction(){
        onExtraMessageListener?.invoke()
        onExtraMessageListener = null
        dismiss()
    }
}