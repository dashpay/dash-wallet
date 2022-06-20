package org.dash.wallet.common.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

@Deprecated("Use AdaptiveDialog")
open class BaseAlertDialogBuilder(private val context: Context) {
    var title: CharSequence? = null
    var message: CharSequence? = null
    var positiveText: CharSequence? = null
    var negativeText: CharSequence? = null
    var neutralText: CharSequence? = null
    var view: View? = null
    var positiveAction: (() -> Unit)? = null
    var negativeAction: (() -> Unit)? = null
    var neutralAction: (() -> Unit)? = null
    var dismissAction: (() -> Unit)? = null
    var cancelAction: (() -> Unit)? = null
    var drawableIcon: Int? = null
    var cancelable: Boolean = true
    var showIcon: Boolean = false
    var isCancelableOnTouchOutside: Boolean = true

    fun buildAlertDialog(): AlertDialog {
        val alertDialog = AlertDialog.Builder(context, R.style.My_Theme_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setView(view)
            .setPositiveButton(positiveText) {_, _ -> positiveAction?.invoke() }
            .setNegativeButton(negativeText) {_, _ -> negativeAction?.invoke() }
            .setNeutralButton(neutralText) {_, _ -> neutralAction?.invoke() }
            .setOnCancelListener { cancelAction?.invoke() }
            .setOnDismissListener { dismissAction?.invoke() }
            .setCancelable(cancelable)
            .create()

        alertDialog.apply {
            if (showIcon){
                setIcon(R.drawable.ic_warning_grey600_24dp)
            }
            setCanceledOnTouchOutside(isCancelableOnTouchOutside)
            window?.callback = UserInteractionAwareCallback(window?.callback, context.getActivity())
        }
        return alertDialog
    }
}

/**
 * Wrapper function which returns a formatted string as calling the method getString(@StringResId, Object...)
 * returns the error: Format string XXX is not a valid format string so it should not be passed to String.format
 */
fun Context.formatString(messageResId: Int,
                          vararg messageArgs: Any): String {
    return this.getString(messageResId, messageArgs)
}

fun AlertDialog.dismissDialog() {
    if (isShowing) {
        dismiss()
    }
}

fun Context.getActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> this.baseContext.getActivity()
        else -> null
    }
}