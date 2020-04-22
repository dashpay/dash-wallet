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
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_enter_pin.*

open class SetupPinDuringUpgradeDialog : CheckPinDialog() {

    companion object {
        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            val checkPinDialogExt = SetupPinDuringUpgradeDialog()
            val args = Bundle()
            args.putInt(ARG_REQUEST_CODE, requestCode)
            args.putBoolean(ARG_PIN_ONLY, true)
            checkPinDialogExt.arguments = args
            checkPinDialogExt.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }
    }

    private val negativeButton: Button by lazy { view!!.findViewById<Button>(R.id.negative_button) }
    private val positiveButton: Button by lazy { view!!.findViewById<Button>(R.id.positive_button) }

    private lateinit var setPinViewModel: SetPinViewModel

    private val walletNotEncrypted = !WalletApplication.getInstance().wallet.isEncrypted

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (walletNotEncrypted) {
            pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
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
        positiveButton.visibility = View.GONE

        if (walletNotEncrypted) {
            title.setText(R.string.forgot_pin_instruction_2)
            message.setText(R.string.lock_enter_pin)
            negativeButton.setText(R.string.button_cancel)
            negativeButton.isEnabled = false
        } else {
            negativeButton.setText(R.string.wallet_lock_unlock)
            negativeButton.setOnClickListener {
                if (viewModel.pin.isNotEmpty()) {
                    viewModel.checkPin(viewModel.pin)
                }
            }
        }
        setPinViewModel = ViewModelProviders.of(this).get(SetPinViewModel::class.java)
        setPinViewModel.encryptWalletLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.SUCCESS -> {
                    sharedModel.onWalletEncryptedCallback.value = viewModel.pin.toString()
                }
                Status.ERROR -> {
                    sharedModel.onWalletEncryptedCallback.value = null
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
            }
        })
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

    override fun checkPin(pin: String) {
        setPinViewModel.savePinAndEncrypt(pin, false)
    }

    override fun showLockedAlert(context: Context) {
        sharedModel.onCancelCallback.call()
    }
}
