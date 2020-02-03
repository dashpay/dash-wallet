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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.view.animation.AnimationUtils
import androidx.annotation.RequiresApi
import androidx.core.os.CancellationSignal
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_lock_screen.*
import org.bitcoinj.wallet.DeterministicSeed
import org.dash.wallet.common.ui.DialogBuilder
import java.util.concurrent.TimeUnit

class LockScreenActivity : SendCoinsQrActivity() {

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, LockScreenActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private val walletApplication = WalletApplication.getInstance()
    private lateinit var viewModel: LockScreenViewModel
    private lateinit var decryptSeedViewModel: DecryptSeedViewModel
    private lateinit var enableFingerprintViewModel: CheckPinSharedModel

    private val temporaryLockCheckHandler = Handler()
    private val temporaryLockCheckInterval = TimeUnit.SECONDS.toMillis(10)
    private val temporaryLockCheckRunnable = Runnable {
        if (pinRetryController.isLocked) {
            setState(State.LOCKED)
        } else {
            setState(State.ENTER_PIN)
        }
    }

    private enum class State {
        ENTER_PIN,
        DECRYPTING,
        INVALID_PIN,
        LOCKED,
        USE_FINGERPRINT,
    }

    private var fingerprintHelper: FingerprintHelper? = null
    private lateinit var fingerprintCancellationSignal: CancellationSignal
    private lateinit var pinRetryController: PinRetryController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        pinRetryController = PinRetryController.getInstance()
        resetSessionPin()
        initView()
        initViewModel()
    }

    override fun onStart() {
        super.onStart()
        setupInitState()
        walletApplication.startBlockchainService(true)
    }

    private fun initView() {
        action_login_with_pin.setOnClickListener {
            setState(State.ENTER_PIN)
        }
        action_login_with_fingerprint.setOnClickListener {
            setState(State.USE_FINGERPRINT)
        }
        action_receive.setOnClickListener {
            startActivity(QuickReceiveActivity.createIntent(this))
        }
        action_scan_to_pay.setOnClickListener {
            performScanning(it);
        }
        numeric_keyboard.setFunctionEnabled(false)
        numeric_keyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (pinRetryController.isLocked) {
                    return
                }
                if (decryptSeedViewModel.pin.length < 4) {
                    decryptSeedViewModel.pin.append(number)
                    pin_preview.next()
                }
                if (decryptSeedViewModel.pin.length == 4) {
                    Handler().postDelayed({
                        decryptSeedViewModel.checkPin(decryptSeedViewModel.pin)
                    }, 200)
                }
            }

            override fun onBack(longClick: Boolean) {
                if (decryptSeedViewModel.pin.isNotEmpty()) {
                    decryptSeedViewModel.pin.deleteCharAt(decryptSeedViewModel.pin.length - 1)
                    pin_preview.prev()
                }
            }

            override fun onFunction() {

            }
        }
        view_flipper.inAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(LockScreenViewModel::class.java)
        decryptSeedViewModel = ViewModelProviders.of(this).get(DecryptSeedViewModel::class.java)
        decryptSeedViewModel.decryptSeedLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    pinRetryController.failedAttempt(it.data!!.second)
                    if (pinRetryController.isLocked) {
                        setState(State.LOCKED)
                    } else {
                        setState(State.INVALID_PIN)
                    }
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    onCorrectPin(it.data!!.first, it.data.second)
                }
            }
        })
        enableFingerprintViewModel = ViewModelProviders.of(this)[CheckPinSharedModel::class.java]
        enableFingerprintViewModel.onCorrectPinCallback.observe(this, Observer {
            onFingerprintSuccess(it.second)
        })
    }

    private fun onCorrectPin(seed: DeterministicSeed?, pin: String?) {
        pinRetryController.clearPinFailPrefs()
        walletApplication.maybeStartAutoLogoutTimer()
        saveSessionPin(pin)
        if (shouldShowBackupReminder && seed != null) {
            startActivity(VerifySeedActivity.createIntent(this, seed.mnemonicCode!!.toTypedArray(), true))
        } else {
            startActivity(WalletActivity.createIntent(this))
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun setState(state: State) {

        action_scan_to_pay.isEnabled = true

        when (state) {
            State.ENTER_PIN, State.INVALID_PIN -> {
                view_flipper.displayedChild = 0

                action_title.setText(R.string.lock_enter_pin)

                action_login_with_pin.visibility = View.GONE
                action_login_with_fingerprint.visibility = View.VISIBLE

                numeric_keyboard.visibility = View.VISIBLE
                numeric_keyboard.isEnabled = true

                if (state == State.INVALID_PIN) {
                    decryptSeedViewModel.pin.clear()
                    pin_preview.shake()
                    Handler().postDelayed({
                        pin_preview.clear()
                    }, 200)
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
                decryptSeedViewModel.pin.clear()
                pin_preview.clear()
                temporaryLockCheckHandler.postDelayed(temporaryLockCheckRunnable, temporaryLockCheckInterval)

                action_title.setText(R.string.wallet_lock_wallet_disabled)
                action_subtitle.text = pinRetryController.getWalletTemporaryLockedMessage(this)

                action_login_with_pin.visibility = View.GONE
                action_login_with_fingerprint.visibility = View.GONE

                action_scan_to_pay.isEnabled = false
                numeric_keyboard.visibility = View.GONE
            }
        }
    }

    private fun setupInitState() {
        if (pinRetryController.isLocked) {
            setState(State.LOCKED)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = FingerprintHelper(this)
            fingerprintHelper?.run {
                if (init() && isFingerprintEnabled) {
                    setState(State.USE_FINGERPRINT)
                    startFingerprintListener()
                    return
                } else {
                    fingerprintHelper = null
                    action_login_with_fingerprint.isEnabled = false
                }
            }
        }
        setState(State.ENTER_PIN)
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
                    if (fingerprintHelper!!.hasFingerprintKeyChanged()) {
                        showFingerprintKeyChangedDialog()
                        action_login_with_fingerprint.isEnabled = false
                    } else {
                        fingerprint_view.showError(exceededMaxAttempts)
                    }
                }
            }

            override fun onHelp(helpCode: Int, helpString: String) {
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
            setState(State.ENTER_PIN)
        }
        dialogBuilder.show()
    }

    private fun onFingerprintSuccess(pass: String) {
        // In order to display the VerifySeedActivity (when needed) the seed is required
        // but when using fingerprint to unlock the App we only know the pin, that is why
        // decryptSeedViewModel.checkPin(savedPass) has to be called
        if (shouldShowBackupReminder) {
            decryptSeedViewModel.checkPin(pass)
        } else {
            onCorrectPin(null, pass)
        }
    }

    private val shouldShowBackupReminder =
            walletApplication.configuration.remindBackupSeed() && walletApplication.configuration.lastDismissedReminderMoreThan24hAgo()
}
