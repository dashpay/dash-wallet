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
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R

@AndroidEntryPoint
class SetupPinDuringUpgradeDialog(
    private var onResult: ((Boolean?, String?) -> Unit)?
) : CheckPinDialog() {

    companion object {
        @JvmStatic
        fun show(activity: AppCompatActivity, onResult: (Boolean?, String?) -> Unit) {
            val checkPinDialogExt = SetupPinDuringUpgradeDialog(onResult)
            checkPinDialogExt.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }
    }

    private val negativeButton: Button by lazy { requireView().findViewById(R.id.negative_button) }
    private val positiveButton: Button by lazy { requireView().findViewById(R.id.positive_button) }

    private val setPinViewModel by viewModels<SetPinViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!setPinViewModel.isWalletEncrypted) {
            viewModel.pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
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

        if (!setPinViewModel.isWalletEncrypted) {
            title = getString(R.string.forgot_pin_instruction_2)
            message = getString(R.string.lock_enter_pin)
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
        setPinViewModel.encryptWalletLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    onResult?.invoke(true, viewModel.pin.toString())
                }
                Status.ERROR -> {
                    onResult?.invoke(false, null)
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            // make the background opaque (not dimmed)
            val windowParams = attributes
            windowParams.dimAmount = 0.00f
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            attributes = windowParams
        }
    }

    override fun checkPin(pin: String) {
        setPinViewModel.savePinAndEncrypt(pin, false)
    }

    override fun showLockedAlert(activity: FragmentActivity, lockedTimeMessage: String) {
        onResult?.invoke(null, null)
    }
}
