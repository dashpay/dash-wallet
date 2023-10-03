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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.enter_amount.NumericKeyboardView
import javax.inject.Inject

@AndroidEntryPoint
class SetPinActivity : InteractionAwareActivity() {

    private lateinit var numericKeyboardView: NumericKeyboardView
    private lateinit var confirmButtonView: View
    private lateinit var pinProgressSwitcherView: ViewSwitcher
    private lateinit var pinPreviewView: PinPreviewView
    private lateinit var pageTitleView: TextView
    private lateinit var pageMessageView: TextView
    private var alertDialog: AlertDialog? = null
    private val viewModel by viewModels<SetPinViewModel>()

    @Inject lateinit var restartService: RestartService
    @Inject lateinit var authManager: SecurityFunctions
    @Inject lateinit var packageInfoProvider: PackageInfoProvider

    val pin = arrayListOf<Int>()
    var seed = listOf<String>()

    private val initialPin by lazy {
        intent.getStringExtra(EXTRA_PASSWORD)
    }

    private val changePin by lazy {
        intent.getBooleanExtra(CHANGE_PIN, false)
    }

    private val upgradingWallet by lazy {
        intent.getBooleanExtra(UPGRADING_WALLET, false)
    }

    private enum class State {
        DECRYPT,
        DECRYPTING,
        SET_PIN,
        CHANGE_PIN,
        CONFIRM_PIN,
        INVALID_PIN,
        ENCRYPTING,
        LOCKED
    }

    private var state = State.SET_PIN

    companion object {

        private const val EXTRA_TITLE_RES_ID = "extra_title_res_id"
        private const val EXTRA_PASSWORD = "extra_password"
        private const val CHANGE_PIN = "change_pin"
        private const val UPGRADING_WALLET = "upgrading_wallet"

        @JvmOverloads
        @JvmStatic
        fun createIntent(
            context: Context, titleResId: Int,
            changePin: Boolean = false, pin: String? = null,
            upgradingWallet: Boolean = false
        ): Intent {
            val intent = Intent(context, SetPinActivity::class.java)
            intent.putExtra(EXTRA_TITLE_RES_ID, titleResId)
            intent.putExtra(CHANGE_PIN, changePin)
            intent.putExtra(EXTRA_PASSWORD, pin)
            intent.putExtra(UPGRADING_WALLET, upgradingWallet)
            return intent
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_pin)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(intent.getIntExtra(EXTRA_TITLE_RES_ID, R.string.set_pin_create_new_wallet))

        initView()
        initViewModel()

        if (viewModel.walletData.wallet == null) {
            showErrorDialog(false, NullPointerException("wallet is null in SetPinActivity"))
        } else {
            if (viewModel.walletData.wallet!!.isEncrypted) {
                if (initialPin != null) {
                    if (changePin) {
                        viewModel.oldPinCache = initialPin
                        setState(State.SET_PIN)
                    } else {
                        viewModel.decryptKeys(initialPin)
                    }
                } else {
                    if (changePin) {
                        if (viewModel.isWalletLocked) {
                            setState(State.LOCKED)
                        } else {
                            setState(State.CHANGE_PIN)
                        }
                    } else {
                        setState(State.DECRYPT)
                    }
                }
            } else {
                seed = viewModel.walletData.wallet!!.keyChainSeed.mnemonicCode!!
            }
        }
    }

    private fun initView() {
        pinProgressSwitcherView = findViewById(R.id.pin_progress_switcher)
        pinPreviewView = findViewById(R.id.pin_preview)
        pageTitleView = findViewById(R.id.page_title)
        pageMessageView = findViewById(R.id.message)
        confirmButtonView = findViewById(R.id.btn_confirm)
        numericKeyboardView = findViewById(R.id.numeric_keyboard)

        pinPreviewView.setTextColor(R.color.dash_light_gray)
        pinPreviewView.hideForgotPinAction()

        numericKeyboardView.isFunctionEnabled = false
        numericKeyboardView.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (changePin && viewModel.isWalletLocked) {
                    return
                }

                if (pin.size < viewModel.pinLength || state == State.DECRYPT) {
                    pin.add(number)
                    pinPreviewView.next()
                }

                if (state == State.DECRYPT) {
                    if (pin.size == viewModel.pinArray.size || (state == State.CONFIRM_PIN && pin.size > viewModel.pinArray.size)) {
                        nextStep()
                    }
                } else {
                    if (pin.size == viewModel.pinLength) {
                        nextStep()
                    }
                }
            }

            override fun onBack(longClick: Boolean) {
                if (pin.size > 0) {
                    pin.removeAt(pin.lastIndex)
                    pinPreviewView.prev()
                }
            }

            override fun onFunction() {

            }
        }
        confirmButtonView.setOnClickListener {
            if (pin.size == 0) {
                pinPreviewView.shake()
            } else {
                nextStep()
            }
        }
    }

    private fun nextStep() {
        if (state == State.CONFIRM_PIN) {
            if (pin == viewModel.pinArray) {
                Handler().postDelayed({
                    if (changePin) {
                        viewModel.changePin()
                    } else {
                        viewModel.savePinAndEncrypt(!upgradingWallet)
                    }
                }, 200)
            } else {
                pinPreviewView.shake()
                setState(State.CONFIRM_PIN)
            }
        } else {
            viewModel.setPin(pin)
            if (state == State.DECRYPT || state == State.CHANGE_PIN || state == State.INVALID_PIN) {
                if (changePin) {
                    viewModel.checkPin()
                } else {
                    viewModel.decryptKeys()
                }
            } else {
                setState(State.CONFIRM_PIN)
            }
        }
    }

    private fun setState(newState: State) {
        when (newState) {
            State.DECRYPT -> {
                pinPreviewView.mode = PinPreviewView.PinType.CUSTOM
                pageTitleView.setText(R.string.set_pin_enter_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.GONE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                confirmButtonView.visibility = View.VISIBLE
                viewModel.pin.clear()
                pin.clear()
            }
            State.CHANGE_PIN, State.INVALID_PIN -> {
                if (viewModel.pinLength != PinPreviewView.DEFAULT_PIN_LENGTH) {
                    pinPreviewView.mode = PinPreviewView.PinType.CUSTOM
                } else {
                    pinPreviewView.mode = PinPreviewView.PinType.STANDARD
                }
                pageTitleView.setText(R.string.set_pin_enter_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.GONE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                confirmButtonView.visibility = View.GONE
                viewModel.pin.clear()
                pin.clear()
                if (viewModel.getFailCount() > 0) {
                    pinPreviewView.badPin(viewModel.getRemainingAttemptsMessage(resources))
                }
                if (newState == State.INVALID_PIN) {
                    pinPreviewView.shake()
                }
                warnLastAttempt()
            }
            State.SET_PIN -> {
                pinPreviewView.mode = PinPreviewView.PinType.STANDARD
                viewModel.pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
                pinPreviewView.clearBadPin()
                pageTitleView.setText(R.string.set_pin_set_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                confirmButtonView.visibility = View.GONE
                pinPreviewView.clear()
                viewModel.pin.clear()
                pin.clear()
            }
            State.CONFIRM_PIN -> {
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                confirmButtonView.visibility = View.GONE
                Handler().postDelayed({
                    pinPreviewView.clear()
                    pageTitleView.setText(R.string.set_pin_confirm_pin)
                }, 200)
                pin.clear()
            }
            State.ENCRYPTING -> {
                pageTitleView.setText(R.string.set_pin_encrypting)
                if (pinProgressSwitcherView.currentView.id == R.id.pin_preview) {
                    pinProgressSwitcherView.showNext()
                }
                pageMessageView.visibility = View.INVISIBLE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(false)
                confirmButtonView.visibility = View.GONE
            }
            State.DECRYPTING -> {
                pageTitleView.setText(if (changePin) R.string.set_pin_verifying_pin else R.string.set_pin_decrypting)
                if (pinProgressSwitcherView.currentView.id == R.id.pin_preview) {
                    pinProgressSwitcherView.showNext()
                }
                pageMessageView.visibility = View.INVISIBLE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(false)
                confirmButtonView.visibility = View.GONE
            }
            State.LOCKED -> {
                viewModel.pin.clear()
                pin.clear()
                pinPreviewView.clear()
                pageTitleView.setText(R.string.wallet_lock_wallet_disabled)
                pageMessageView.text = viewModel.getLockedMessage(resources)
                pageMessageView.visibility = View.VISIBLE
                pinProgressSwitcherView.visibility = View.GONE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
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
            dialog.show(this) { }
        }
    }

    private fun initViewModel() {
        viewModel.encryptWalletLiveData.observe(this) {
            when (it.status) {
                Status.ERROR -> {
                    if (changePin) {
                        if(viewModel.isLockedAfterAttempt(viewModel.getPinAsString())) {

                        } else {
                            if (viewModel.isWalletLocked) {
                                setState(State.LOCKED)
                            }
                        }
                    } else {
                        if (state == State.DECRYPTING) {
                            setState(if (changePin) State.INVALID_PIN else State.DECRYPT)
                            if (!changePin) {
                                android.widget.Toast.makeText(this, R.string.set_pin_confirm_pin_incorrect,
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            showErrorDialog(true, it.exception)
                            setState(State.CONFIRM_PIN)
                        }
                    }
                }
                Status.LOADING -> {
                    setState(if (state == State.CONFIRM_PIN) State.ENCRYPTING else State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    if (state == State.DECRYPTING) {
                        seed = viewModel.walletData.wallet!!.keyChainSeed.mnemonicCode!!
                        setState(State.SET_PIN)
                    } else {
                        if (changePin) {
                            viewModel.configuration.pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
                            if (viewModel.biometricHelper.requiresEnabling
                                && viewModel.configuration.enableFingerprint
                            ) {
                                lifecycleScope.launch {
                                    viewModel.biometricHelper.enableBiometricReminder(
                                        this@SetPinActivity,
                                        viewModel.getPinAsString()
                                    )

                                    if (initialPin != null) {
                                        goHome()
                                    } else {
                                        finish()
                                    }
                                }
                            } else {
                                if (initialPin != null) {
                                    viewModel.resetFailedPinAttempts()
                                    goHome()
                                } else {
                                    finish()
                                }
                            }
                        } else {
                            viewModel.initWallet()
                        }
                    }
                }
            }
        }
        viewModel.checkPinLiveData.observe(this) {
            when (it.status) {
                Status.ERROR -> {
                    if(viewModel.isLockedAfterAttempt(viewModel.getPinAsString())) {
                        restartService.performRestart(this, true)
                    } else {
                        if (viewModel.isWalletLocked) {
                            setState(State.LOCKED)
                        } else {
                            setState(if (changePin) State.INVALID_PIN else State.DECRYPT)
                        }
                    }
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    viewModel.oldPinCache = viewModel.getPinAsString()
                    viewModel.resetFailedPinAttempts()
                    setState(State.SET_PIN)
                }
            }
        }
        viewModel.startNextActivity.observe(this) {
            setResult(Activity.RESULT_OK)
            if (it) {
                startVerifySeedActivity()
            } else {
                goHome()
            }
            viewModel.startAutoLogout()
        }
    }

    private fun showErrorDialog(isEncryptingError: Boolean, exception: Throwable?) {
        if (exception != null) {
            viewModel.logError(exception, "SetPinActivity Error")
        } else {
            viewModel.logError(Exception("SetPinActivity Error: unknown"))
        }
        var title = 0;
        var message = 0;
        if (isEncryptingError) {
            title = R.string.wallet_encryption_error_title
            message = R.string.wallet_encryption_error_message
        } else {
            title = R.string.set_pin_error_missing_wallet_title
            message = R.string.set_pin_error_missing_wallet_message
        }
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(title),
            getString(message),
            getString(R.string.button_cancel),
            getString(R.string.button_ok)
        )
        dialog.isCancelable = false
        dialog.show(this) {
            if (it == true) {
                alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                    this,
                    packageInfoProvider,
                    viewModel.configuration,
                    viewModel.walletData.wallet,
                    application as WalletApplication
                ).buildAlertDialog()
                alertDialog?.show()
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when {
            pin.size > 0 -> setState(state)
            state == State.CONFIRM_PIN -> setState(State.SET_PIN)
            else -> if (state != State.ENCRYPTING && state != State.DECRYPTING) {
                finish()
            }
        }
    }

    private fun startVerifySeedActivity() {
        startActivity(VerifySeedActivity.createIntent(this, seed.toTypedArray(), true))
        finish()
    }

    private fun goHome() {
        startActivity(WalletActivity.createIntent(this))
        finish()
    }

    override fun onPause() {
        val alertDialog = this.alertDialog

        if (alertDialog != null && alertDialog.isShowing) {
            alertDialog.dismiss()
        }

        super.onPause()
    }
}
