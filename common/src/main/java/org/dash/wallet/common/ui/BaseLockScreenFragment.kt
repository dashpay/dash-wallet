package org.dash.wallet.common.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.services.LockScreenBroadcaster
import javax.inject.Inject

@AndroidEntryPoint
@Deprecated("Use AdaptiveDialog to show dialogs that are auto-dismissible with lock screen")
open class BaseLockScreenFragment: Fragment() {
    @Inject lateinit var lockScreenBroadcaster: LockScreenBroadcaster
    protected lateinit var alertDialog: AlertDialog
    protected lateinit var dialogFragment: DialogFragment
    @Inject protected lateinit var baseAlertDialogBuilder: BaseAlertDialogBuilder

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lockScreenBroadcaster.activatingLockScreen.observe(this) {
            Log.e(this::class.java.simpleName, "Lock screen observed")
            if (this::alertDialog.isInitialized) {
                alertDialog.dismissDialog()
            }
            if (this::dialogFragment.isInitialized){
                dialogFragment.dismiss()
            }
        }
    }
}