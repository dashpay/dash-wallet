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
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity

class SetPinActivity : InteractionAwareActivity() {

    private lateinit var walletApplication: WalletApplication

    private lateinit var numericKeyboardView: NumericKeyboardView
    private lateinit var confirmButtonView: View
    private lateinit var viewModel: SetPinViewModel
    private lateinit var enableFingerprintViewModel: EnableFingerprintDialog.SharedViewModel
    private lateinit var pinProgressSwitcherView: ViewSwitcher
    private lateinit var pinPreviewView: PinPreviewView
    private lateinit var pageTitleView: TextView
    private lateinit var pageMessageView: TextView

    private lateinit var pinRetryController: PinRetryController
    private var pinLength = WalletApplication.getInstance().configuration.pinLength

    val pin = arrayListOf<Int>()
    var seed = listOf<String>()

    private val initialPin by lazy {
        intent.getStringExtra(EXTRA_PASSWORD)
    }

    private val changePin by lazy {
        intent.getBooleanExtra(CHANGE_PIN, false)
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

        @JvmOverloads
        @JvmStatic
        fun createIntent(context: Context, titleResId: Int,
                         changePin: Boolean = false, pin: String? = null): Intent {
            val intent = Intent(context, SetPinActivity::class.java)
            intent.putExtra(EXTRA_TITLE_RES_ID, titleResId)
            intent.putExtra(CHANGE_PIN, changePin)
            intent.putExtra(EXTRA_PASSWORD, pin)
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

        pinRetryController = PinRetryController.getInstance()

        walletApplication = application as WalletApplication
        if (walletApplication.wallet.isEncrypted) {
            if (initialPin != null) {
                if (changePin) {
                    viewModel.oldPinCache = initialPin
                    setState(State.SET_PIN)
                } else {
                    viewModel.decryptKeys(initialPin)
                }
            } else {
                if (changePin) {
                    if (pinRetryController.isLocked) {
                        setState(State.LOCKED)
                    } else {
                        setState(State.CHANGE_PIN)
                    }
                } else {
                    setState(State.DECRYPT)
                }
            }
        } else {
            seed = walletApplication.wallet.keyChainSeed.mnemonicCode!!
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

        numericKeyboardView.setFunctionEnabled(false)
        numericKeyboardView.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (changePin && pinRetryController.isLocked) {
                    return
                }

                if (pin.size < pinLength || state == State.DECRYPT) {
                    pin.add(number)
                    pinPreviewView.next()
                }

                if (state == State.DECRYPT) {
                    if (pin.size == viewModel.pin.size || (state == State.CONFIRM_PIN && pin.size > viewModel.pin.size)) {
                        nextStep()
                    }
                } else {
                    if (pin.size == pinLength) {
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
            if (pin == viewModel.pin) {
                Handler().postDelayed({
                    if (changePin) {
                        viewModel.changePin()
                    } else {
                        viewModel.savePinAndEncrypt()
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
                if (pinLength != PinPreviewView.DEFAULT_PIN_LENGTH) {
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
                if (pinRetryController.failCount() > 0) {
                    pinPreviewView.badPin(pinRetryController.getRemainingAttemptsMessage(this))
                }
                if (newState == State.INVALID_PIN) {
                    pinPreviewView.shake()
                }
            }
            State.SET_PIN -> {
                pinPreviewView.mode = PinPreviewView.PinType.STANDARD
                pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
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
                pageMessageView.text = pinRetryController.getWalletTemporaryLockedMessage(this)
                pageMessageView.visibility = View.VISIBLE
                pinProgressSwitcherView.visibility = View.GONE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            }
        }
        state = newState
    }

    private fun initViewModel() {
        viewModel = ViewModelProvider(this)[SetPinViewModel::class.java]
        viewModel.encryptWalletLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    if (changePin) {
                        pinRetryController.failedAttempt(viewModel.getPinAsString())
                        if (pinRetryController.isLocked) {
                            setState(State.LOCKED)
                        }
                    } else {
                        if (state == State.DECRYPTING) {
                            setState(if (changePin) State.INVALID_PIN else State.DECRYPT)
                            if (!changePin) {
                                android.widget.Toast.makeText(this, "Incorrect PIN", android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else {
                            android.widget.Toast.makeText(this, "Encrypting error", android.widget.Toast.LENGTH_LONG).show()
                            setState(State.CONFIRM_PIN)
                        }
                    }
                }
                Status.LOADING -> {
                    setState(if (state == State.CONFIRM_PIN) State.ENCRYPTING else State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    if (state == State.DECRYPTING) {
                        seed = walletApplication.wallet.keyChainSeed.mnemonicCode!!
                        setState(State.SET_PIN)
                    } else {
                        if (changePin) {
                            WalletApplication.getInstance().configuration.pinLength = PinPreviewView.DEFAULT_PIN_LENGTH
                            val enableFingerprint = walletApplication.configuration.enableFingerprint
                            if (EnableFingerprintDialog.shouldBeShown(this@SetPinActivity) && enableFingerprint) {
                                EnableFingerprintDialog.show(viewModel.getPinAsString(), supportFragmentManager)
                            } else {
                                if (initialPin != null) {
                                    pinRetryController.clearPinFailPrefs()
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
        })
        viewModel.checkPinLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    pinRetryController.failedAttempt(viewModel.getPinAsString())
                    if (pinRetryController.isLocked) {
                        setState(State.LOCKED)
                    } else {
                        setState(if (changePin) State.INVALID_PIN else State.DECRYPT)
                    }
                }
                Status.LOADING -> {
                    setState(State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    viewModel.oldPinCache = viewModel.getPinAsString()
                    pinRetryController.clearPinFailPrefs()
                    setState(State.SET_PIN)
                }
            }
        })
        viewModel.startNextActivity.observe(this, Observer {
            setResult(Activity.RESULT_OK)
            if (it) {
                startVerifySeedActivity()
            } else {
                goHome()
            }
            walletApplication.autoLogout.apply {
                maybeStartAutoLogoutTimer()
                keepLockedUntilPinEntered = false
            }
        })
        enableFingerprintViewModel = ViewModelProvider(this)[EnableFingerprintDialog.SharedViewModel::class.java]
        enableFingerprintViewModel.onCorrectPinCallback.observe(this, Observer {
            if (initialPin != null) {
                goHome()
            } else {
                finish()
            }
        })
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
        startActivity(VerifySeedActivity.createIntent(this, seed.toTypedArray()))
        finish()
    }

    private fun goHome() {
        startActivity(WalletActivity.createIntent(this))
        finish()
    }
}
