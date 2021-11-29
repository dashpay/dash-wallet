package org.dash.wallet.common.util

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

/**
 * Some string resources undergo some transformation before being added to the components of the alert dialog built
 * So for simplicity we shall have all the text components be of type CharSequence
 */

open class AlertDialogBuilder(private val appContext: Context) {
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

    private fun initDialogBuilder(): AlertDialog.Builder
         = AlertDialog.Builder(appContext, R.style.My_Theme_Dialog)
             .setTitle(title)
             .setMessage(message)
             .setView(view)
             .setPositiveButton(positiveText) {_, _ -> positiveAction?.invoke() }
             .setNegativeButton(negativeText) {_, _ -> negativeAction?.invoke() }
             .setNeutralButton(neutralText) {_, _ -> neutralAction?.invoke() }
             .setOnCancelListener { cancelAction?.invoke() }
             .setOnDismissListener { dismissAction?.invoke() }
             .setCancelable(cancelable)


    /**
     * Wrapper function which returns a formatted string as calling the method getString(@StringResId, Object...) returns:
     * Format string XXX is not a valid format string so it should not be passed to String.format
     */
    fun formatString(messageResId: Int,
                         vararg messageArgs: Any): String {
        return appContext.getString(messageResId, messageArgs)
    }

    fun createAlertDialog(): AlertDialog {
        return initDialogBuilder().create().apply {
            if (showIcon){
                setIcon(R.drawable.ic_warning_grey600_24dp)
            }
            setCanceledOnTouchOutside(isCancelableOnTouchOutside)
            window?.callback = UserInteractionAwareCallback(window?.callback,
                appContext as Activity?
            )
        }
    }
}