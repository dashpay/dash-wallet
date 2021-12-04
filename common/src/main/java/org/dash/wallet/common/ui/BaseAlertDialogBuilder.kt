package org.dash.wallet.common.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

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

    /**
     * Wrapper function which returns a formatted string as calling the method getString(@StringResId, Object...) returns:
     * Format string XXX is not a valid format string so it should not be passed to String.format
     */
    fun formatString(messageResId: Int,
                     vararg messageArgs: Any): String {
        return context.getString(messageResId, messageArgs)
    }
}

fun AlertDialog.dismissDialog() {
    if (isShowing) {
        Log.e(this::class.java.simpleName, "Dialog is showing")
        dismiss()
    } else {
        Log.e(this::class.java.simpleName, "Dialog not showing")
    }
}

fun Context.getActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> this.baseContext.getActivity()
        else -> null
    }
}