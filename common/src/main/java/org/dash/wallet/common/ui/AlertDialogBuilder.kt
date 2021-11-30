package org.dash.wallet.common.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import org.dash.wallet.common.R
import org.dash.wallet.common.UserInteractionAwareCallback

/**
 * Some string resources undergo some transformation before being added to the components of the alert dialog to be built.
 * So for simplicity we shall have all the text components be of type CharSequence
 */

 class AlertDialogBuilder(private val appContext: Context,
                         lifecycle: Lifecycle): LifecycleObserver {
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
    lateinit var alertDialog: AlertDialog

    init {
        lifecycle.addObserver(this)
    }


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
        alertDialog = initDialogBuilder().create()
        alertDialog.apply {
            if (showIcon){
                setIcon(R.drawable.ic_warning_grey600_24dp)
            }
            setCanceledOnTouchOutside(isCancelableOnTouchOutside)
            window?.callback = UserInteractionAwareCallback(window?.callback,
                appContext as Activity?
            )
        }
        return alertDialog
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun stop() {
        alertDialog.dismiss()
        Log.e("TAG", "================================>>>> lifecycle owner STOPED")
    }
}