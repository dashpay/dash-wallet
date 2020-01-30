package de.schildbach.wallet.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_enter_pin.*

open class CheckPinDialogExt : CheckPinDialog() {

    companion object {

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            val checkPinDialogExt = CheckPinDialogExt()
            val args = Bundle()
            args.putInt(ARG_REQUEST_CODE, requestCode)
            args.putBoolean(ARG_PIN_ONLY, true)
            checkPinDialogExt.arguments = args
            checkPinDialogExt.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isCancelable = false
        title.setText(R.string.update_app_unlock_wallet_title)
        message.setText(R.string.update_app_unlock_wallet_message)
        cancel_button.visibility = View.GONE
        pin_or_fingerprint_button.visibility = View.GONE

        unlock_button.visibility = View.VISIBLE
        unlock_button.setOnClickListener {
            if (viewModel.pin.isNotEmpty()) {
                viewModel.checkPin(viewModel.pin)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            //make the background opaque (not dimmed)
            val windowParams = attributes
            windowParams.dimAmount = 0.00f
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            attributes = windowParams
        }
    }

    override fun showLockedAlert(context: Context) {
        sharedModel.onCancelCallback.call()
    }
}
