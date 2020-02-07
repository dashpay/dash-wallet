package de.schildbach.wallet.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.CancellationSignal
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_enter_pin.*


open class CheckPinDialog : DialogFragment() {

    companion object {

        private val FRAGMENT_TAG = CheckPinDialog::class.java.simpleName

        private const val ARG_REQUEST_CODE = "arg_request_code"
        private const val ARG_PIN_ONLY = "arg_pin_only"

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0, pinOnly: Boolean = false) {
            val checkPinDialog = CheckPinDialog()
            if (PinRetryController.getInstance().isLocked) {
                checkPinDialog.showLockedAlert(activity)
            } else {
                val args = Bundle()
                args.putInt(ARG_REQUEST_CODE, requestCode)
                args.putBoolean(ARG_PIN_ONLY, pinOnly)
                checkPinDialog.arguments = args
                checkPinDialog.show(activity.supportFragmentManager, FRAGMENT_TAG)
            }
        }

        @JvmStatic
        fun show(activity: AppCompatActivity, requestCode: Int = 0) {
            show(activity, requestCode, false)
        }

    }

    private lateinit var state: State

    protected lateinit var viewModel: CheckPinViewModel
    protected lateinit var sharedModel: CheckPinSharedModel

    protected val pinRetryController = PinRetryController.getInstance()
    protected var fingerprintHelper: FingerprintHelper? = null
    protected lateinit var fingerprintCancellationSignal: CancellationSignal

    protected enum class State {
        ENTER_PIN,
        INVALID_PIN,
        DECRYPTING
    }

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
        cancel_button.setOnClickListener {
            sharedModel.onCancelCallback.call()
            dismiss()
        }
        pin_or_fingerprint_button.setOnClickListener {
            if (pin_preview.visibility == View.VISIBLE) {
                fingerprintFlow(true)
            } else {
                fingerprintFlow(false)
            }
        }
        numeric_keyboard.setFunctionEnabled(false)
        numeric_keyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (viewModel.pin.length < 4) {
                    viewModel.pin.append(number)
                    pin_preview.next()
                }
                if (viewModel.pin.length == 4) {
                    Handler().postDelayed({
                        viewModel.checkPin(viewModel.pin)
                    }, 200)
                }
            }

            override fun onBack(longClick: Boolean) {
                if (viewModel.pin.isNotEmpty()) {
                    viewModel.pin.deleteCharAt(viewModel.pin.length - 1)
                    pin_preview.prev()
                }
            }

            override fun onFunction() {

            }
        }
        pin_preview.setTextColor(R.color.dash_light_gray)
        pin_preview.hideForgotPinAction()
        setState(State.ENTER_PIN)

        arguments?.getBoolean(ARG_PIN_ONLY, false).let {
            if (true == it) {
                fingerprintFlow(!it)
                pin_or_fingerprint_button.isEnabled = false
            } else initFingerprint()
        }
    }

    /*
        initViewModel can be overridden by subclasses to specify their own view model
        and actions
     */
    protected open fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(CheckPinViewModel::class.java)
        viewModel.checkPinLiveData.observe(viewLifecycleOwner, Observer {
            when (it.status) {
                Status.ERROR -> {
                    pinRetryController.failedAttempt(it.data!!)
                    if (pinRetryController.isLocked) {
                        showLockedAlert(context!!)
                        dismiss()
                        return@Observer
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
        })
    }

    private fun dismiss(pin: String) {
        if (pinRetryController.isLocked) {
            return
        }
        val requestCode = arguments!!.getInt(ARG_REQUEST_CODE)
        sharedModel.onCorrectPinCallback.value = Pair(requestCode, pin)
        pinRetryController.clearPinFailPrefs()
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.run {
            initSharedModel(this)
        } ?: throw IllegalStateException("Invalid Activity")
    }

    protected open fun FragmentActivity.initSharedModel(activity: FragmentActivity) {
        sharedModel = ViewModelProviders.of(activity)[CheckPinSharedModel::class.java]
    }

    protected fun setState(newState: State) {
        when (newState) {
            State.ENTER_PIN -> {
                if (pin_progress_switcher.currentView.id == R.id.progress) {
                    pin_progress_switcher.showPrevious()
                }
                viewModel.pin.clear()
                pin_preview.clear()
                pin_preview.clearBadPin()
                numeric_keyboard.isEnabled = true
            }
            State.INVALID_PIN -> {
                if (pin_progress_switcher.currentView.id == R.id.progress) {
                    pin_progress_switcher.showPrevious()
                }
                viewModel.pin.clear()
                pin_preview.shake()
                Handler().postDelayed({
                    pin_preview.clear()
                }, 200)
                pin_preview.badPin(pinRetryController.getRemainingAttemptsMessage(context))
                numeric_keyboard.isEnabled = true
            }
            State.DECRYPTING -> {
                if (pin_progress_switcher.currentView.id != R.id.progress) {
                    pin_progress_switcher.showNext()
                }
                numeric_keyboard.isEnabled = false
            }
        }
        state = newState
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::fingerprintCancellationSignal.isInitialized) {
            fingerprintCancellationSignal.cancel()
        }
        super.onDismiss(dialog)
    }

    private fun initFingerprint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = FingerprintHelper(activity)
            fingerprintHelper?.run {
                if (init()) {
                    if (isFingerprintEnabled) {
                        fingerprintFlow(true)
                        startFingerprintListener()
                    } else {
                        pin_or_fingerprint_button.visibility = View.GONE
                    }
                } else {
                    fingerprintHelper = null
                    fingerprintFlow(false)
                }
            }
        }
    }

    private fun fingerprintFlow(active: Boolean) {
        fingerprint_view.visibility = if (active) View.VISIBLE else View.GONE
        pin_preview.visibility = if (active) View.GONE else View.VISIBLE
        numeric_keyboard.visibility = if (active) View.GONE else View.VISIBLE
        message.setText(if (active) R.string.authenticate_fingerprint_message else R.string.authenticate_pin_message)
        pin_or_fingerprint_button.setText(if (active) R.string.authenticate_switch_to_pin else R.string.authenticate_switch_to_fingerprint)
        pin_or_fingerprint_button.visibility = if (active) View.VISIBLE else View.GONE
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startFingerprintListener() {
        fingerprintCancellationSignal = CancellationSignal()
        fingerprintHelper!!.getPassword(fingerprintCancellationSignal, object : FingerprintHelper.Callback {
            override fun onSuccess(savedPass: String) {
                onFingerprintSuccess(savedPass)
            }

            override fun onFailure(message: String, canceled: Boolean, exceededMaxAttempts: Boolean) {
                if (!canceled) {
                    fingerprint_view.showError(exceededMaxAttempts)
                }
            }

            override fun onHelp(helpCode: Int, helpString: String) {
                fingerprint_view.showError(false)
            }
        })
    }

    protected open fun onFingerprintSuccess(savedPass: String) {
        dismiss(savedPass)
    }

    protected fun showLockedAlert(context: Context) {
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle(R.string.wallet_lock_wallet_disabled)
        dialogBuilder.setMessage(pinRetryController.getWalletTemporaryLockedMessage(context))
        dialogBuilder.setPositiveButton(android.R.string.ok, null)
        dialogBuilder.show()
    }
}
