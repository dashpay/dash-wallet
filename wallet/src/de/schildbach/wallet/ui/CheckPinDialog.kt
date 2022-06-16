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
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.os.CancellationSignal
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentEnterPinBinding
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
open class CheckPinDialog(
    private val onSuccessOrDismiss: ((String?) -> Unit)?
) : DialogFragment() {

    companion object {

        internal val FRAGMENT_TAG = CheckPinDialog::class.java.simpleName
        private val log = LoggerFactory.getLogger(CheckPinDialog::class.java)

        internal const val ARG_REQUEST_CODE = "arg_request_code"
        internal const val ARG_PIN_ONLY = "arg_pin_only"

        private fun showDialog(checkPinDialog: CheckPinDialog, activity: FragmentActivity, requestCode: Int = 0, pinOnly: Boolean = false) {
            val controller = PinRetryController.getInstance()

            if (controller.isLocked) {
                val message = controller.getWalletTemporaryLockedMessage(activity.resources)
                checkPinDialog.showLockedAlert(activity, message)
            } else {
                val args = Bundle()
                args.putInt(ARG_REQUEST_CODE, requestCode)
                args.putBoolean(ARG_PIN_ONLY, pinOnly)
                checkPinDialog.arguments = args
                checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
            }
        }

        @JvmStatic
        fun show(activity: FragmentActivity, requestCode: Int = 0, pinOnly: Boolean = false) {
            val checkPinDialog = CheckPinDialog()
            showDialog(checkPinDialog, activity, requestCode, pinOnly)
        }

        @JvmStatic
        fun show(activity: FragmentActivity, requestCode: Int = 0) {
            show(activity, requestCode, false)
        }

        suspend fun showAsync(activity: FragmentActivity, pinOnly: Boolean = false): String? {
            return suspendCancellableCoroutine { coroutine ->
                val checkPinDialog = CheckPinDialog { pin ->
                    if (coroutine.isActive) {
                        coroutine.resume(pin)
                    }
                }

                try {
                    showDialog(checkPinDialog, activity, 0, pinOnly)
                } catch (ex: Exception) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(ex)
                    }
                }
            }
        }
    }

    private val binding by viewBinding(FragmentEnterPinBinding::bind)
    protected open val sharedModel by activityViewModels<CheckPinSharedModel>()
    protected open val viewModel by viewModels<CheckPinViewModel>()
    private lateinit var state: State

    protected var fingerprintHelper: FingerprintHelper? = null
    private lateinit var fingerprintCancellationSignal: CancellationSignal

    protected enum class State {
        ENTER_PIN,
        INVALID_PIN,
        DECRYPTING
    }

    constructor(): this(null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_enter_pin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViewModel()
        binding.buttonBar.negativeButton.setText(R.string.button_cancel)
        binding.buttonBar.negativeButton.setOnClickListener {
            sharedModel.onCancelCallback.call()
            dismiss()
        }
        binding.buttonBar.positiveButton.setOnClickListener {
            if (binding.pinPreview.visibility == View.VISIBLE) {
                fingerprintFlow(true)
            } else {
                fingerprintFlow(false)
            }
        }

        binding.numericKeyboard.isFunctionEnabled = false
        binding.numericKeyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {
            override fun onNumber(number: Int) {
                if (viewModel.pin.length < viewModel.pinLength) {
                    viewModel.pin.append(number)
                    binding.pinPreview.next()
                }
                if (viewModel.pin.length == viewModel.pinLength) {
                    binding.pinPreview.postDelayed({
                        checkPin(viewModel.pin.toString())
                    }, 200)
                }
            }

            override fun onBack(longClick: Boolean) {
                if (viewModel.pin.isNotEmpty()) {
                    viewModel.pin.deleteCharAt(viewModel.pin.length - 1)
                    binding.pinPreview.prev()
                }
            }

            override fun onFunction() {

            }
        }
        binding.pinPreview.setTextColor(R.color.dash_light_gray)
        binding.pinPreview.hideForgotPinAction()
        setState(State.ENTER_PIN)

        arguments?.getBoolean(ARG_PIN_ONLY, false).let {
            if (true == it) {
                fingerprintFlow(!it)
                binding.buttonBar.positiveButton.isEnabled = false
            } else initFingerprint()
        }
    }

    open fun checkPin(pin: String) {
        viewModel.checkPin(pin)
    }

    /*
        initViewModel can be overridden by subclasses to specify their own view model
        and actions
     */
    protected open fun initViewModel() {
        viewModel.checkPinLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.ERROR -> {
                    viewModel.registerFailedAttempt(it.data!!)
                    if (viewModel.isWalletLocked) {
                        val message = viewModel.getLockedMessage(requireContext().resources)
                        showLockedAlert(requireActivity(), message)
                        dismiss()
                        return@observe
                    }
                    setState(State.INVALID_PIN)
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    dismiss(it.data!!)
                }
            }
        }
    }

    private fun dismiss(pin: String) {
        if (viewModel.isWalletLocked) {
            return
        }
        val requestCode = requireArguments().getInt(ARG_REQUEST_CODE)
        sharedModel.onCorrectPinCallback.value = Pair(requestCode, pin)
        onSuccessOrDismiss?.invoke(pin)
        viewModel.resetFailedPinAttempts()
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    protected fun setState(newState: State) {
        when (newState) {
            State.ENTER_PIN -> {
                if (viewModel.pinLength != PinPreviewView.DEFAULT_PIN_LENGTH) {
                    binding.pinPreview.mode = PinPreviewView.PinType.CUSTOM
                }
                if (binding.pinProgressSwitcher.currentView.id == R.id.progress) {
                    binding.pinProgressSwitcher.showPrevious()
                }
                viewModel.pin.clear()
                binding.pinPreview.clear()
                binding.pinPreview.clearBadPin()
                binding.numericKeyboard.isEnabled = true
            }
            State.INVALID_PIN -> {
                if (binding.pinProgressSwitcher.currentView.id == R.id.progress) {
                    binding.pinProgressSwitcher.showPrevious()
                }
                viewModel.pin.clear()
                val pinPreview = binding.pinPreview
                pinPreview.shake()
                pinPreview.postDelayed({
                    pinPreview.clear()
                }, 200)
                pinPreview.badPin(viewModel.getRemainingAttemptsMessage(resources))
                binding.numericKeyboard.isEnabled = true
            }
            State.DECRYPTING -> {
                if (binding.pinProgressSwitcher.currentView.id != R.id.progress) {
                    binding.pinProgressSwitcher.showNext()
                }
                binding.numericKeyboard.isEnabled = false
            }
        }
        state = newState
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::fingerprintCancellationSignal.isInitialized) {
            fingerprintCancellationSignal.cancel()
        }
        onSuccessOrDismiss?.invoke(null)
        sharedModel.onCancelCallback.call()
        super.onDismiss(dialog)
    }

    private fun initFingerprint() {
        log.info("fingerprint setup for Android M and above")
        fingerprintHelper = FingerprintHelper(activity)
        fingerprintHelper?.run {
            if (init()) {
                if (isFingerprintEnabled) {
                    fingerprintFlow(true)
                    startFingerprintListener()
                } else {
                    binding.buttonBar.positiveButton.visibility = View.GONE
                }
            } else {
                fingerprintHelper = null
                fingerprintFlow(false)
            }
        }
    }

    private fun fingerprintFlow(active: Boolean) {
        binding.fingerprintView.isVisible = active
        binding.pinPreview.isVisible = !active
        binding.numericKeyboard.isVisible = !active
        binding.message.setText(if (active) R.string.authenticate_fingerprint_message else R.string.authenticate_pin_message)
        binding.buttonBar.positiveButton.setText(if (active) R.string.authenticate_switch_to_pin else R.string.authenticate_switch_to_fingerprint)
        binding.buttonBar.positiveButton.isVisible = active
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startFingerprintListener() {
        log.info("start fingerprint listener")
        fingerprintCancellationSignal = CancellationSignal()
        fingerprintCancellationSignal.setOnCancelListener {
            log.info("fingerprint cancellation signal listener triggered")
        }
        fingerprintHelper!!.getPassword(fingerprintCancellationSignal, object : FingerprintHelper.Callback {
            override fun onSuccess(savedPass: String) {
                log.info("fingerprint scan successful")
                onFingerprintSuccess(savedPass)
            }

            override fun onFailure(message: String, canceled: Boolean, exceededMaxAttempts: Boolean) {
                log.info("fingerprint scan failure (canceled: $canceled, max attempts: $exceededMaxAttempts): $message")
                if (!canceled) {
                    binding.fingerprintView.showError(exceededMaxAttempts)
                }
            }

            override fun onHelp(helpCode: Int, helpString: String) {
                log.info("fingerprint help (helpCode: $helpCode, helpString: $helpString")
                binding.fingerprintView.showError(false)
            }
        })
    }

    protected open fun onFingerprintSuccess(savedPass: String) {
        dismiss(savedPass)
    }

    protected open fun showLockedAlert(activity: FragmentActivity, lockedTimeMessage: String) {
        AdaptiveDialog.create(
            null,
            activity.getString(R.string.wallet_lock_wallet_disabled),
            lockedTimeMessage,
            activity.getString(android.R.string.ok)
        ).show(activity)
    }
}
