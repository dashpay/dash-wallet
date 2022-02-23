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

import android.content.DialogInterface
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AdaptiveDialog(@LayoutRes private val layout: Int): DialogFragment() {
    companion object {
        private const val ICON_RES_ARG = "icon_res"
        private const val TITLE_ARG = "title"
        private const val MESSAGE_ARG = "message"
        private const val POS_BUTTON_ARG = "positive_text"
        private const val NEG_BUTTON_ARG = "negative_text"

        @JvmStatic
        fun simple(
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null
        ): AdaptiveDialog {
            return custom(
                R.layout.dialog_simple,
                null,
                null,
                message,
                negativeButtonText,
                positiveButtonText
            )
        }

        fun create(
            @DrawableRes icon: Int,
            title: String,
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null
        ): AdaptiveDialog {
            return custom(
                R.layout.dialog_adaptive,
                icon,
                title,
                message,
                negativeButtonText,
                positiveButtonText
            )
        }

        @JvmStatic
        fun custom(
            @LayoutRes layout: Int,
            @DrawableRes icon: Int?,
            title: String?,
            message: String,
            negativeButtonText: String,
            positiveButtonText: String? = null
        ): AdaptiveDialog {
            val args = Bundle().apply {
                icon?.let { putInt(ICON_RES_ARG, icon) }
                putString(TITLE_ARG, title)
                putString(MESSAGE_ARG, message)
                putString(NEG_BUTTON_ARG, negativeButtonText)
                putString(POS_BUTTON_ARG, positiveButtonText)
            }
            return AdaptiveDialog(layout).apply {
                arguments = args
            }
        }
    }

    private var onResultListener: ((Boolean?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        dialog?.window?.apply {
            val dialogInset = resources.getDimensionPixelOffset(R.dimen.dialog_horizontal_inset)
            setBackgroundDrawable(
                InsetDrawable(
                    ResourcesCompat.getDrawable(resources, R.drawable.white_background_rounded, null),
                    dialogInset, 0, dialogInset, 0
                )
            )
            requestFeature(Window.FEATURE_NO_TITLE)
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val iconView: ImageView? = view.findViewById(R.id.dialog_icon)
        val titleView: TextView? = view.findViewById(R.id.dialog_title)
        val messageView: TextView? = view.findViewById(R.id.dialog_message)
        val positiveButton: TextView? = view.findViewById(R.id.dialog_positive_button)
        val negativeButton: TextView? = view.findViewById(R.id.dialog_negative_button)
        val negativeButtonSecondary: TextView? = view.findViewById(R.id.dialog_negative_button_secondary)

        showIfNotEmpty(iconView, ICON_RES_ARG)
        showIfNotEmpty(titleView, TITLE_ARG)
        val isMessageShown = showIfNotEmpty(messageView, MESSAGE_ARG)
        showIfNotEmpty(negativeButton, NEG_BUTTON_ARG)
        val isPositiveButtonShown = showIfNotEmpty(positiveButton, POS_BUTTON_ARG)

        if (isMessageShown) {
            messageView?.post {
                if (messageView.lineCount > 3) {
                    titleView?.gravity = Gravity.START
                    messageView.gravity = Gravity.START
                    iconView?.updateLayoutParams<LinearLayout.LayoutParams> {
                        gravity = Gravity.START
                    }
                }
            }
        }

        if (negativeButtonSecondary != null && !isPositiveButtonShown) {
            negativeButton?.isVisible = false
            negativeButtonSecondary.isVisible = true
            negativeButtonSecondary.text = negativeButton?.text
            negativeButtonSecondary.setOnClickListener {
                onNegativeAction()
            }
        }

        positiveButton?.setOnClickListener {
            onPositiveAction()
        }

        negativeButton?.setOnClickListener {
            onNegativeAction()
        }
    }

    fun show(activity: FragmentActivity, onResult: (Boolean?) -> Unit) {
        onResultListener = onResult
        show(activity.supportFragmentManager, "adaptive_dialog")
    }

    suspend fun showAsync(activity: FragmentActivity): Boolean? {
        return suspendCancellableCoroutine { coroutine ->
            val onSuccess: (Boolean?) -> Unit = { result ->
                if (coroutine.isActive) {
                    coroutine.resume(result)
                }
            }

            try {
                show(activity, onSuccess)
            } catch(ex: Exception) {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(ex)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.apply {
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                callback = UserInteractionAwareCallback(this.callback, requireActivity())
            }
        }
    }

    private fun onPositiveAction() {
        onResultListener?.invoke(true)
        onResultListener = null
        dismiss()
    }

    private fun onNegativeAction() {
        onResultListener?.invoke(false)
        onResultListener = null
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onResultListener?.invoke(null)
        onResultListener = null
    }

    private fun showIfNotEmpty(view: TextView?, argKey: String): Boolean {
        if (view == null) {
            return false
        }

        val args = requireArguments()
        val text = args.getString(argKey)

        if (text != null) {
            view.text = text
            view.isVisible = true
        } else {
            view.isVisible = false
        }

        return view.isVisible
    }

    private fun showIfNotEmpty(view: ImageView?, argKey: String): Boolean {
        if (view == null) {
            return false
        }

        val args = requireArguments()
        val resId = if (args.containsKey(argKey)) args.getInt(argKey) else 0

        if (resId != 0) {
            view.setImageResource(resId)
            view.isVisible = true
        } else {
            view.isVisible = false
        }

        return view.isVisible
    }
}