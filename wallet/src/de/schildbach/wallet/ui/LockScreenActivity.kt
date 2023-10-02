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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.telephony.TelephonyManager
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.AutoLogout
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.security.BiometricLockoutException
import de.schildbach.wallet.security.PinRetryController
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.ui.payments.QuickReceiveActivity
import de.schildbach.wallet.ui.send.SendCoinsQrActivity
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityLockScreenRootBinding
import kotlinx.coroutines.launch
import org.bitcoinj.wallet.Wallet.BalanceType
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dismissDialog
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
open class LockScreenActivity : SecureActivity() {

    companion object {
        const val INTENT_EXTRA_KEEP_UNLOCKED = "LockScreenActivity.keep_unlocked"
        private val log = LoggerFactory.getLogger(LockScreenActivity::class.java)
    }

    lateinit var alertDialog: AlertDialog
    @Inject lateinit var walletApplication: WalletApplication
    @Inject lateinit var walletData: WalletDataProvider
    @Inject lateinit var lockScreenBroadcaster: LockScreenBroadcaster
    @Inject lateinit var configuration: Configuration
    @Inject lateinit var restartService: RestartService
    @Inject lateinit var pinRetryController: PinRetryController
    @Inject lateinit var biometricHelper: BiometricHelper
    @Inject lateinit var packageInfoProvider: PackageInfoProvider

    private val autoLogout: AutoLogout by lazy { walletApplication.autoLogout }
    private val checkPinViewModel by viewModels<CheckPinViewModel>()
    private lateinit var binding: ActivityLockScreenRootBinding
    private val pinLength by lazy { configuration.pinLength }

    val lockScreenDisplayed: Boolean
        get() = binding.rootViewSwitcher.displayedChild == 0

    private val temporaryLockCheckHandler = Handler()
    private val temporaryLockCheckInterval = TimeUnit.SECONDS.toMillis(10)
    private val temporaryLockCheckRunnable = Runnable {
        if (pinRetryController.isLocked) {
            setLockState(State.LOCKED)
        } else {
            setLockState(State.USE_DEFAULT)
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

    val keepUnlocked by lazy {
        intent.getBooleanExtra(INTENT_EXTRA_KEEP_UNLOCKED, false)
    }

    protected var isLocked: Boolean = false
    private val shouldShowBackupReminder
        get() = configuration.remindBackupSeed && configuration.lastBackupSeedReminderMoreThan24hAgo()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (walletData.wallet == null) {
            finish()
            return
        }

        binding = ActivityLockScreenRootBinding.inflate(layoutInflater)
        super.setContentView(binding.root)
        setupKeyboardBottomMargin()
        isLocked = autoLogout.shouldLogout()

        initView()
        initViewModel()

        setupBackupSeedReminder()
    }

    override fun setContentView(contentViewResId: Int) {
        setContentView(layoutInflater.inflate(contentViewResId, null))
    }

    override fun setContentView(contentView: View?) {
        binding.regularContent.removeAllViews()
        binding.regularContent.addView(contentView)
    }

    private val onLogoutListener = AutoLogout.OnLogoutListener {
        isLocked = true
        dismissKeyboard()
        biometricHelper.cancelPending()
        setLockState(State.USE_DEFAULT)
        handleLockScreenActivated()
    }

    open fun imitateUserInteraction() {
        onUserInteraction()
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
        val hasBalance = walletData.wallet?.getBalance(BalanceType.ESTIMATED)?.isPositive ?: false
        if (hasBalance && configuration.lastBackupSeedTime == 0L) {
            configuration.setLastBackupSeedTime()
        }
    }

    private fun setupKeyboardBottomMargin() {
        if (!hasNavBar()) {
            val set = ConstraintSet()
            val layout = binding.lockScreen.numericKeyboard.parent as ConstraintLayout
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
            resources.getBoolean(id)
        } else {
            // Check for keys
            val hasMenuKey = ViewConfiguration.get(this).hasPermanentMenuKey()
            val hasBackKey = KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
            !hasMenuKey && !hasBackKey
        }
    }

    override fun onStart() {
        super.onStart()
        autoLogout.setOnLogoutListener(onLogoutListener)

        if (!keepUnlocked && configuration.autoLogoutEnabled &&
            (autoLogout.keepLockedUntilPinEntered || autoLogout.shouldLogout())
        ) {
            setLockState(
                if (pinRetryController.isLocked) {
                    State.LOCKED
                } else {
                    State.USE_DEFAULT
                }
            )
            autoLogout.setAppWentBackground(false)
            if (autoLogout.isTimerActive) {
                autoLogout.stopTimer()
            }
            handleLockScreenActivated()
        } else {
            binding.rootViewSwitcher.displayedChild = 1
            if (!keepUnlocked) {
                autoLogout.maybeStartAutoLogoutTimer()
            }
        }

        startBlockchainService()
    }

    private fun startBlockchainService() {
        walletApplication.startBlockchainService(false)
    }

    private fun initView() {
        binding.lockScreen.apply {
            actionLoginWithPin.setOnClickListener {
                setLockState(State.ENTER_PIN)
            }
            actionLoginWithFingerprint.setOnClickListener {
                setLockState(State.USE_FINGERPRINT)
            }
            actionReceive.setOnClickListener {
                startActivity(QuickReceiveActivity.createIntent(this@LockScreenActivity))
                autoLogout.keepLockedUntilPinEntered = true
            }
            actionScanToPay.setOnClickListener {
                startActivity(SendCoinsQrActivity.createIntent(this@LockScreenActivity, true))
                autoLogout.keepLockedUntilPinEntered = true
            }
            numericKeyboard.isFunctionEnabled = false
            numericKeyboard.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

                override fun onNumber(number: Int) {
                    if (pinRetryController.isLocked) {
                        return
                    }
                    if (checkPinViewModel.pin.length < pinLength) {
                        checkPinViewModel.pin.append(number)
                        pinPreview.next()
                    }
                    if (checkPinViewModel.pin.length == pinLength) {
                        pinPreview.postDelayed({
                            checkPinViewModel.checkPin(checkPinViewModel.pin)
                        }, 200)
                    }
                }

                override fun onBack(longClick: Boolean) {
                    if (checkPinViewModel.pin.isNotEmpty()) {
                        checkPinViewModel.pin.deleteCharAt(checkPinViewModel.pin.length - 1)
                        pinPreview.prev()
                    }
                }

                override fun onFunction() {
                }
            }
            viewFlipper.inAnimation = AnimationUtils.loadAnimation(this@LockScreenActivity, android.R.anim.fade_in)
        }
    }

    private fun initViewModel() {
        checkPinViewModel.checkPinLiveData.observe(this) {
            when (it.status) {
                Status.ERROR -> {
                    if (pinRetryController.failedAttempt(it.data!!)) {
                        restartService.performRestart(this, true)
                    } else {
                        if (pinRetryController.isLocked) {
                            setLockState(State.LOCKED)
                        } else {
                            setLockState(State.INVALID_PIN)
                        }
                    }
                }
                Status.LOADING -> {
                    setLockState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    if (biometricHelper.requiresEnabling) {
                        lifecycleScope.launch {
                            biometricHelper.enableBiometricReminder(this@LockScreenActivity, it.data!!)
                            onCorrectPin(it.data)
                        }
                    } else {
                        onCorrectPin(it.data!!)
                    }
                }
                else -> {
                    // ignore
                }
            }
        }
    }

    private fun onCorrectPin(pin: String) {
        pinRetryController.clearPinFailPrefs()
        autoLogout.keepLockedUntilPinEntered = false
        autoLogout.deviceWasLocked = false
        autoLogout.maybeStartAutoLogoutTimer()
        isLocked = false
        onLockScreenDeactivated()
        if (shouldShowBackupReminder) {
            val intent = VerifySeedActivity.createIntent(this, pin, false)
            configuration.resetBackupSeedReminderTimer()
            startActivity(intent)
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            binding.rootViewSwitcher.displayedChild = 1
        }
    }

    private fun setLockState(suggestedState: State) {
        binding.lockScreen.apply {
            actionScanToPay.isEnabled = true
            var lockState = suggestedState

            if (lockState == State.USE_DEFAULT) {
                lockState = if (biometricHelper.isEnabled) {
                    State.USE_FINGERPRINT
                } else {
                    log.info("fingerprint was disabled")
                    actionLoginWithFingerprint.isEnabled = false
                    actionLoginWithFingerprint.alpha = 0f
                    State.ENTER_PIN
                }
            }

            log.info("LockState = $lockState")

            when (lockState) {
                State.ENTER_PIN, State.INVALID_PIN -> {
                    if (pinLength != PinPreviewView.DEFAULT_PIN_LENGTH) {
                        pinPreview.mode = PinPreviewView.PinType.CUSTOM
                    }

                    viewFlipper.displayedChild = 0
                    actionTitle.setText(R.string.lock_enter_pin)
                    actionLoginWithPin.isVisible = false
                    actionLoginWithFingerprint.isVisible = true
                    numericKeyboard.isVisible = true

                    if (lockState == State.INVALID_PIN) {
                        checkPinViewModel.pin.clear()
                        pinPreview.shake()
                        pinPreview.postDelayed({
                            pinPreview.clear()
                        }, 200)
                    } else {
                        numericKeyboard.isEnabled = true
                        pinPreview.clear()
                        checkPinViewModel.pin.clear()
                        pinPreview.clearBadPin()
                    }

                    if (pinRetryController.failCount() > 0) {
                        pinPreview.badPin(pinRetryController.getRemainingAttemptsMessage(resources))
                    }

                    if (pinRetryController.remainingAttempts == 1) {
                        val dialog = AdaptiveDialog.create(
                            R.drawable.ic_warning,
                            getString(R.string.wallet_last_attempt),
                            getString(R.string.wallet_last_attempt_message),
                            "",
                            getString(R.string.button_understand)
                        )
                        dialog.isCancelable = false
                        dialog.show(this@LockScreenActivity)
                    }
                }
                State.USE_FINGERPRINT -> {
                    viewFlipper.displayedChild = 1
                    actionTitle.setText(R.string.lock_unlock_with_fingerprint)
                    actionLoginWithPin.visibility = View.VISIBLE
                    actionLoginWithFingerprint.visibility = View.GONE
                    numericKeyboard.visibility = View.GONE

                    showBiometricPrompt()
                }
                State.DECRYPTING -> {
                    viewFlipper.displayedChild = 2
                    numericKeyboard.isEnabled = false
                }
                State.LOCKED -> {
                    viewFlipper.displayedChild = 3
                    checkPinViewModel.pin.clear()
                    pinPreview.clear()
                    temporaryLockCheckHandler.postDelayed(temporaryLockCheckRunnable, temporaryLockCheckInterval)

                    actionTitle.setText(R.string.wallet_lock_wallet_disabled)
                    actionSubtitle.text = pinRetryController.getWalletTemporaryLockedMessage(resources)

                    actionLoginWithPin.visibility = View.GONE
                    actionLoginWithFingerprint.visibility = View.GONE

                    actionScanToPay.isEnabled = false
                    numericKeyboard.visibility = View.GONE
                }
                else -> {
                    // we should never reach this since default means we use
                    // ENTER_PIN or USE_FINGERPRINT
                }
            }
        }

        if (!lockScreenDisplayed) {
            binding.rootViewSwitcher.displayedChild = 0
        }
    }

    private fun showBiometricPrompt() {
        log.info("showing fingerprint prompt")
        biometricHelper.getPassword(this@LockScreenActivity, true) { savedPass, error ->
            if (error != null) {
                val exceededMaxAttempts = error is BiometricLockoutException
                log.info("fingerprint scan failure (max attempts: $exceededMaxAttempts): ${error.message}")

                if (error is KeyPermanentlyInvalidatedException) {
                    showFingerprintKeyChangedDialog()
                    binding.lockScreen.actionLoginWithFingerprint.isEnabled = false
                } else {
                    binding.lockScreen.fingerprintView.showError(exceededMaxAttempts)
                }
            } else if (savedPass != null) {
                log.info("fingerprint scan successful")
                binding.lockScreen.fingerprintView.hideError()
                onCorrectPin(savedPass)
            } else {
                log.info("user canceled fingerprint scan")
                setLockState(State.ENTER_PIN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        temporaryLockCheckHandler.removeCallbacks(temporaryLockCheckRunnable)
    }

    private fun showFingerprintKeyChangedDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_warning,
            title = getString(R.string.fingerprint_changed_title),
            message = getString(R.string.fingerprint_changed_message),
            positiveButtonText = getString(android.R.string.ok),
            negativeButtonText = ""
        ).show(this) {
            setLockState(State.ENTER_PIN)
        }
    }

    override fun onBackPressed() {
        if (!lockScreenDisplayed) {
            super.onBackPressed()
        }
    }

    private fun handleLockScreenActivated() {
        if (this::alertDialog.isInitialized) {
            alertDialog.dismissDialog()
        }
        lockScreenBroadcaster.activatingLockScreen.call()
        dismissDialogFragments(supportFragmentManager)
        onLockScreenActivated()
    }

    open fun onLockScreenActivated() { }

    open fun onLockScreenDeactivated() { }

    private fun dismissDialogFragments(fragmentManager: FragmentManager) {
        fragmentManager.fragments
            .takeIf { it.isNotEmpty() }
            ?.forEach { fragment ->
                // check to see if the activity is valid and fragment is added to its activity
                if (fragment.activity != null && fragment.isAdded) {
                    if (fragment is DialogFragment) {
                        fragment.dismissAllowingStateLoss()
                    } else if (fragment is NavHostFragment) {
                        dismissDialogFragments(fragment.childFragmentManager)
                    }
                }
            }
    }

    private fun dismissKeyboard() {
        val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.windowToken?.let { token ->
            inputManager?.hideSoftInputFromWindow(token, 0)
        }
    }
}
