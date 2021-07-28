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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.telephony.TelephonyManager
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.os.CancellationSignal
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.AutoLogout
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_lock_screen.*
import kotlinx.android.synthetic.main.activity_lock_screen_root.*
import org.bitcoinj.wallet.Wallet.BalanceType
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit


open class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val INTENT_EXTRA_KEEP_UNLOCKED = "LockScreenActivity.keep_unlocked"
        private val log = LoggerFactory.getLogger(LockScreenActivity::class.java)
    }

    val walletApplication: WalletApplication = WalletApplication.getInstance()
    private val configuration = walletApplication.configuration
    private val autoLogout: AutoLogout = walletApplication.autoLogout

    private lateinit var viewModel: LockScreenViewModel
    private lateinit var checkPinViewModel: CheckPinViewModel
    private lateinit var enableFingerprintViewModel: EnableFingerprintDialog.SharedViewModel
    private var pinLength = configuration.pinLength

    private val lockScreenDisplayed: Boolean
        get() = root_view_switcher.displayedChild == 0

    private val temporaryLockCheckHandler = Handler()
    private val temporaryLockCheckInterval = TimeUnit.SECONDS.toMillis(10)
    private val temporaryLockCheckRunnable = Runnable {
        if (pinRetryController.isLocked) {
            setLockState(State.LOCKED)
        } else {
            setLockState(State.ENTER_PIN)
        }
    }

    private enum class State {
        ENTER_PIN,
        DECRYPTING,
        INVALID_PIN,
        LOCKED,
        USE_FINGERPRINT,
        USE_DEFAULT // defaults to fingerprint if available and enabled
    }

    private var fingerprintHelper: FingerprintHelper? = null
    private lateinit var fingerprintCancellationSignal: CancellationSignal
    private lateinit var pinRetryController: PinRetryController

    private val keepUnlocked by lazy {
        intent.getBooleanExtra(INTENT_EXTRA_KEEP_UNLOCKED, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (walletApplication.wallet == null) {
            finish()
            return
        }

        super.setContentView(R.layout.activity_lock_screen_root)
        setupKeyboardBottomMargin()

        pinRetryController = PinRetryController.getInstance()
        initView()
        initViewModel()

        setupBackupSeedReminder()
    }

    override fun setContentView(contentViewResId: Int) {
        setContentView(layoutInflater.inflate(contentViewResId, null))
    }

    override fun setContentView(contentView: View?) {
        regular_content.removeAllViews()
        regular_content.addView(contentView)
    }

    private val onLogoutListener = AutoLogout.OnLogoutListener {
        viewModel.activatingLockScreen.call()
        setLockState(State.USE_DEFAULT)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()

        if (!lockScreenDisplayed) {
            resetAutoLogoutTimer()
        }
    }

    private fun resetAutoLogoutTimer() {
        autoLogout.resetTimerIfActive()
    }

    private fun setupBackupSeedReminder() {
        val hasBalance = walletApplication.wallet.getBalance(BalanceType.ESTIMATED).isPositive
        if (hasBalance && configuration.lastBackupSeedTime == 0L) {
            configuration.setLastBackupSeedTime()
        }
    }

    private fun setupKeyboardBottomMargin() {
        if (!hasNavBar()) {
            val set = ConstraintSet()
            val layout = numeric_keyboard.parent as ConstraintLayout
            set.clone(layout)
            set.clear(R.id.numeric_keyboard, ConstraintSet.BOTTOM)
            set.connect(R.id.numeric_keyboard, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            set.applyTo(layout)
        }
    }

    private fun hasNavBar(): Boolean {
        val tm: TelephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        // emulator
        if ("Android" == tm.networkOperatorName || Build.FINGERPRINT.startsWith("generic")) {
            return true
        }
        val id: Int = resources.getIdentifier("config_showNavigationBar", "bool", "android")

        // Krip devices seem to incorrectly report config_showNavigationBar
        val isKripDeviceWithoutNavBar = Build.BRAND == "KRIP" && when (Build.MODEL) {
            "K5", "K5c", "K5b", "K4m", "KRIP_K4" -> true
            else -> false
        }
        return if (id > 0 && !isKripDeviceWithoutNavBar) {
            id > 0 && resources.getBoolean(id)
        } else {
            // Check for keys
            val hasMenuKey = ViewConfiguration.get(this).hasPermanentMenuKey();
            val hasBackKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK);
            !hasMenuKey && !hasBackKey;
        }
    }

    override fun onStart() {
        super.onStart()
        setupInitState()
        autoLogout.setOnLogoutListener(onLogoutListener)

        if (!keepUnlocked && configuration.autoLogoutEnabled && (autoLogout.keepLockedUntilPinEntered || autoLogout.shouldLogout())) {
            viewModel.activatingLockScreen.call()
            setLockState(State.USE_DEFAULT)
            autoLogout.setAppWentBackground(false)
            if (autoLogout.isTimerActive) {
                autoLogout.stopTimer()
            }
        } else {
            root_view_switcher.displayedChild = 1
            if (!keepUnlocked)
                autoLogout.maybeStartAutoLogoutTimer()
        }

        startBlockchainService()
    }

    private fun startBlockchainService() {
        walletApplication.startBlockchainService(false)
    }

    private fun initView() {
        action_login_with_pin.setOnClickListener {
            setLockState(State.ENTER_PIN)
        }
        action_login_with_fingerprint.setOnClickListener {
            setLockState(State.USE_FINGERPRINT)
        }
        action_receive.setOnClickListener {
            startActivity(QuickReceiveActivity.createIntent(this))
            autoLogout.keepLockedUntilPinEntered = true
        }
        action_scan_to_pay.setOnClickListener {
            startActivity(SendCoinsQrActivity.createIntent(this, true))
            autoLogout.keepLockedUntilPinEntered = true
        }
        numeric_keyboard.setFunctionEnabled(false)
        numeric_keyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (pinRetryController.isLocked) {
                    return
                }
                if (checkPinViewModel.pin.length < pinLength) {
                    checkPinViewModel.pin.append(number)
                    pin_preview.next()
                }
                if (checkPinViewModel.pin.length == pinLength) {
                    Handler().postDelayed({
                        checkPinViewModel.checkPin(checkPinViewModel.pin)
                    }, 200)
                }
            }

            override fun onBack(longClick: Boolean) {
                if (checkPinViewModel.pin.isNotEmpty()) {
                    checkPinViewModel.pin.deleteCharAt(checkPinViewModel.pin.length - 1)
                    pin_preview.prev()
                }
            }

            override fun onFunction() {

            }
        }
        view_flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this)[LockScreenViewModel::class.java]
        checkPinViewModel = ViewModelProvider(this)[CheckPinViewModel::class.java]
        checkPinViewModel.checkPinLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    pinRetryController.failedAttempt(it.data!!)
                    if (pinRetryController.isLocked) {
                        setLockState(State.LOCKED)
                    } else {
                        setLockState(State.INVALID_PIN)
                    }
                }
                Status.LOADING -> {
                    setLockState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    if (EnableFingerprintDialog.shouldBeShown(this)) {
                        EnableFingerprintDialog.show(it.data!!, supportFragmentManager)
                    } else {
                        onCorrectPin(it.data!!)
                    }
                }
            }
        })
        enableFingerprintViewModel = ViewModelProvider(this)[EnableFingerprintDialog.SharedViewModel::class.java]
        enableFingerprintViewModel.onCorrectPinCallback.observe(this, Observer {
            val pin = it.second
            onCorrectPin(pin)
        })
    }

    private fun onCorrectPin(pin: String) {
        pinRetryController.clearPinFailPrefs()
        autoLogout.keepLockedUntilPinEntered = false
        autoLogout.deviceWasLocked = false
        autoLogout.maybeStartAutoLogoutTimer()
        if (shouldShowBackupReminder) {
            val intent = VerifySeedActivity.createIntent(this, pin)
            configuration.resetBackupSeedReminderTimer()
            startActivity(intent)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            root_view_switcher.displayedChild = 1
        }
    }

    private fun setLockState(state: State) {
        log.info("LockState = $state")
        action_scan_to_pay.isEnabled = true

        val fingerPrintEnabled = initFingerprint(false)
        if (state == State.USE_DEFAULT) {
            return if (fingerPrintEnabled) {
                setLockState(State.USE_FINGERPRINT)
            } else {
                setLockState(State.ENTER_PIN)
            }
        }

        when (state) {
            State.ENTER_PIN, State.INVALID_PIN -> {
                if (pinLength != PinPreviewView.DEFAULT_PIN_LENGTH) {
                    pin_preview.mode = PinPreviewView.PinType.CUSTOM
                }
                view_flipper.displayedChild = 0

                action_title.setText(R.string.lock_enter_pin)

                action_login_with_pin.visibility = View.GONE
                action_login_with_fingerprint.visibility = View.VISIBLE

                numeric_keyboard.visibility = View.VISIBLE

                if (state == State.INVALID_PIN) {
                    checkPinViewModel.pin.clear()
                    pin_preview.shake()
                    Handler().postDelayed({
                        pin_preview.clear()
                    }, 200)
                } else {
                    numeric_keyboard.isEnabled = true
                    pin_preview.clear()
                    checkPinViewModel.pin.clear()
                    pin_preview.clearBadPin()
                }

                if (pinRetryController.failCount() > 0) {
                    pin_preview.badPin(pinRetryController.getRemainingAttemptsMessage(this))
                }
            }
            State.USE_FINGERPRINT -> {
                view_flipper.displayedChild = 1

                action_title.setText(R.string.lock_unlock_with_fingerprint)

                action_login_with_pin.visibility = View.VISIBLE
                action_login_with_fingerprint.visibility = View.GONE

                numeric_keyboard.visibility = View.GONE
            }
            State.DECRYPTING -> {
                view_flipper.displayedChild = 2

                numeric_keyboard.isEnabled = false
            }
            State.LOCKED -> {
                view_flipper.displayedChild = 3
                checkPinViewModel.pin.clear()
                pin_preview.clear()
                temporaryLockCheckHandler.postDelayed(temporaryLockCheckRunnable, temporaryLockCheckInterval)

                action_title.setText(R.string.wallet_lock_wallet_disabled)
                action_subtitle.text = pinRetryController.getWalletTemporaryLockedMessage(this)

                action_login_with_pin.visibility = View.GONE
                action_login_with_fingerprint.visibility = View.GONE

                action_scan_to_pay.isEnabled = false
                numeric_keyboard.visibility = View.GONE
            }
            State.USE_DEFAULT -> {
                // we should never reach this since default means we use
                // ENTER_PIN or USE_FINGERPRINT
            }
        }

        if (!lockScreenDisplayed) {
            root_view_switcher.displayedChild = 0
        }
    }

    private fun setupInitState() {
        if (pinRetryController.isLocked) {
            setLockState(State.LOCKED)
            return
        }
    }

    /**
     * @param forceInit force initialize the fingerprint listener
     * @return true if fingerprints are initialized, false if not
     */
    private fun initFingerprint(forceInit: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            log.info("initializing finger print on Android M and above(force: $forceInit)")
            if (fingerprintHelper == null) {
                fingerprintHelper = FingerprintHelper(this)
            }
            var result = false
            fingerprintHelper?.run {
                if (::fingerprintCancellationSignal.isInitialized && !fingerprintCancellationSignal.isCanceled) {
                    // we already initialized the fingerprint listener
                    log.info("fingerprint already initialized: $fingerprintCancellationSignal")
                    fingerprintCancellationSignal.cancel()
                }

                if (init() && isFingerprintEnabled) {
                    startFingerprintListener()
                    result = true
                } else {
                    log.info("fingerprint was disabled")
                    fingerprintHelper = null
                    action_login_with_fingerprint.isEnabled = false
                    action_login_with_fingerprint.alpha = 0f
                }
            }
            return result
        }
        return false
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startFingerprintListener() {

        fingerprintCancellationSignal = CancellationSignal()
        fingerprintCancellationSignal.setOnCancelListener {
            log.info("fingerprint cancellation signal listener triggered: $fingerprintCancellationSignal")
        }

        log.info("start fingerprint listener: $fingerprintCancellationSignal")
        //fingerprintListening = true
        fingerprintHelper!!.getPassword(fingerprintCancellationSignal, object : FingerprintHelper.Callback {
            override fun onSuccess(savedPass: String) {
                log.info("fingerprint scan successful")
                fingerprint_view.hideError()
                onCorrectPin(savedPass)
            }

            override fun onFailure(message: String, canceled: Boolean, exceededMaxAttempts: Boolean) {
                log.info("fingerprint scan failure (canceled: $canceled, max attempts: $exceededMaxAttempts): $message")
                if (!canceled) {
                    if (fingerprintHelper!!.hasFingerprintKeyChanged()) {
                        showFingerprintKeyChangedDialog()
                        action_login_with_fingerprint.isEnabled = false
                    } else {
                        fingerprint_view.showError(exceededMaxAttempts)
                        initFingerprint(false)
                    }
                }
            }

            override fun onHelp(helpCode: Int, helpString: String) {
                log.info("fingerprint help (helpCode: $helpCode, helpString: $helpString")
                fingerprint_view.showError(false)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()

        if (::fingerprintCancellationSignal.isInitialized) {
            fingerprintCancellationSignal.cancel()
        }
        temporaryLockCheckHandler.removeCallbacks(temporaryLockCheckRunnable)
    }

    private fun showFingerprintKeyChangedDialog() {
        val dialogBuilder = DialogBuilder(this)
        dialogBuilder.setTitle(R.string.fingerprint_changed_title)
        dialogBuilder.setMessage(R.string.fingerprint_changed_message)
        dialogBuilder.setPositiveButton(android.R.string.ok) { _, _ ->
            fingerprintHelper!!.resetFingerprintKeyChanged()
            setLockState(State.ENTER_PIN)
        }
        dialogBuilder.show()
    }

    private val shouldShowBackupReminder = configuration.remindBackupSeed
            && configuration.lastBackupSeedReminderMoreThan24hAgo()

    override fun onPause() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onResume() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onResume()
    }

    override fun onBackPressed() {
        if (!lockScreenDisplayed) {
            super.onBackPressed()
        }
    }
}
