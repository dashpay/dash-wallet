package org.dash.wallet.common.ui

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
abstract class BaseDialogFragment : DialogFragment() {
    protected val lockScreenViewModel: LockScreenViewModel by activityViewModels()
    protected lateinit var alertDialog: AlertDialog
    @Inject lateinit var baseAlertDialogBuilder: BaseAlertDialogBuilder

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Log.e(this::class.java.simpleName, "onCreateDialog")
        lockScreenViewModel.activatingLockScreen.observe(this){
            Log.e(this::class.java.simpleName, "Closing dialog")
            alertDialog.dismissDialog()
        }
        return alertDialog
    }
}