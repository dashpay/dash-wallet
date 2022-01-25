package org.dash.wallet.common.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
@Deprecated("Use AdaptiveDialog")
abstract class BaseDialogFragment : DialogFragment() {
    protected lateinit var alertDialog: AlertDialog
    @Inject lateinit var baseAlertDialogBuilder: BaseAlertDialogBuilder

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Log.e(this::class.java.simpleName, "onCreateDialog")
        return alertDialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        alertDialog.dismissDialog()
    }
}