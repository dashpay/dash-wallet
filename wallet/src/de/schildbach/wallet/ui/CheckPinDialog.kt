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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.*
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentEnterPinBinding
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
open class CheckPinDialog(
    private var onSuccessOrDismiss: ((String?) -> Unit)?
) : DialogFragment() {

    companion object {

        internal val FRAGMENT_TAG = CheckPinDialog::class.java.simpleName
        private val log = LoggerFactory.getLogger(CheckPinDialog::class.java)

        @JvmStatic
        fun show(activity: FragmentActivity, onSuccessOrDismiss: (String?) -> Unit) {
            val checkPinDialog = CheckPinDialog(onSuccessOrDismiss)
            checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
        }

        suspend fun showAsync(activity: FragmentActivity): String? {
            return suspendCancellableCoroutine { coroutine ->
                val checkPinDialog = CheckPinDialog { pin ->
                    if (coroutine.isActive) {
                        coroutine.resume(pin)
                    }
                }

                try {
                    checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
                } catch (ex: Exception) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(ex)
                    }
                }
            }
        }
    }

    protected enum class State {
        ENTER_PIN,
        INVALID_PIN,
        DECRYPTING
    }

    private val binding by viewBinding(FragmentEnterPinBinding::bind)
    protected open val viewModel by viewModels<CheckPinViewModel>()
    private lateinit var state: State

    @Inject
    lateinit var restartService: RestartService

    protected var title: String
        get() = binding.title.text.toString()
        set(value) { binding.title.text = value }

    protected var message: String
        get() = binding.message.text.toString()
        set(value) { binding.message.text = value }

    constructor(): this(null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return if (viewModel.isWalletLocked) {
            val message = viewModel.getLockedMessage(resources)
            showLockedAlert(requireActivity(), message)
            dismiss()
            null
        } else {
            inflater.inflate(R.layout.fragment_enter_pin, container, false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViewModel()
        binding.buttonBar.negativeButton.setText(R.string.button_cancel)
        binding.buttonBar.negativeButton.setOnClickListener {
            dismiss()
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
                    if (viewModel.isLockedAfterAttempt(it.data!!)) {
                        restartService.performRestart(requireActivity(), true)
                        return@observe
                    }

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
                else -> {
                    // ignore
                }
            }
        }
    }

    private fun dismiss(pin: String) {
        if (viewModel.isWalletLocked) {
            return
        }
        onSuccessOrDismiss?.invoke(pin)
        onSuccessOrDismiss = null
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
        if (view == null) {
            log.warn("Attempted to set state when the view is not available.")
            return
        }
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
                if (viewModel.getFailCount() > 0) {
                    binding.pinPreview.badPin(viewModel.getRemainingAttemptsMessage(resources))
                }
                warnLastAttempt()
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

                warnLastAttempt()
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

    private fun warnLastAttempt() {
        if (viewModel.getRemainingAttempts() == 1) {
            val dialog = AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.wallet_last_attempt),
                getString(R.string.wallet_last_attempt_message),
                "",
                getString(R.string.button_understand)
            )
            dialog.isCancelable = false
            dialog.show(requireActivity())
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        onSuccessOrDismiss?.invoke(null)
        onSuccessOrDismiss = null
        super.onDismiss(dialog)
    }

    protected open fun showLockedAlert(activity: FragmentActivity, lockedTimeMessage: String) {
        AdaptiveDialog.create(
            R.drawable.ic_warning,
            activity.getString(R.string.wallet_lock_wallet_disabled),
            lockedTimeMessage,
            activity.getString(android.R.string.ok)
        ).show(activity)
    }
}
