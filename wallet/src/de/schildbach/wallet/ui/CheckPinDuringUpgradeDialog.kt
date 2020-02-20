/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_enter_pin.*

open class CheckPinDuringUpgradeDialog : CheckPinDialog() {

    companion object {

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            val checkPinDialogExt = CheckPinDuringUpgradeDialog()
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
        pin_or_fingerprint_button.visibility = View.GONE

        cancel_button.setText(R.string.wallet_lock_unlock)
        cancel_button.setOnClickListener {
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
