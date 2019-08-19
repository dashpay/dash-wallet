package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.ViewSwitcher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R

class SetPinActivity : AppCompatActivity() {

    private lateinit var numericKeyboardView: NumericKeyboardView
    private lateinit var confirmButtonView: View
    private lateinit var messageView: View
    private lateinit var viewModel: SetPinViewModel
    private lateinit var pinProgressSwitcherView: ViewSwitcher
    private lateinit var pinPreviewView: PinPreviewView
    private lateinit var pageTitleView: TextView
    private lateinit var pageMessageView: TextView

    val pin = arrayListOf<Int>()
    var seed = listOf<String>()

    private enum class State {
        DECRYPT,
        DECRYPTING,
        SET_PIN,
        CONFIRM_PIN,
        ENCRYPTING
    }

    private var state = State.SET_PIN

    companion object {

        private const val EXTRA_TITLE_RES_ID = "extra_title_res_id"
        private const val EXTRA_PASSWORD = "extra_password"

        fun createIntent(context: Context, titleResId: Int): Intent {
            val intent = Intent(context, SetPinActivity::class.java)
            intent.putExtra(EXTRA_TITLE_RES_ID, titleResId)
            return intent
        }

        fun createIntent(context: Context, titleResId: Int, password: String?): Intent {
            val intent = createIntent(context, titleResId)
            intent.putExtra(EXTRA_PASSWORD, password)
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

        val walletApplication = application as WalletApplication
        if (walletApplication.wallet.isEncrypted) {
            val password = intent.getStringExtra(EXTRA_PASSWORD)
            if (password != null) {
                viewModel.checkPin(password)
            } else {
                setState(State.DECRYPT)
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
        messageView = findViewById(R.id.message)
        confirmButtonView = findViewById(R.id.btn_confirm)
        numericKeyboardView = findViewById(R.id.numeric_keyboard)

        numericKeyboardView.setCancelEnabled(false)
        numericKeyboardView.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (pin.size < 4 || state == State.DECRYPT) {
                    pin.add(number)
                    pinPreviewView.next()
                }

                if (state == State.DECRYPT) {
                    if (pin.size == viewModel.pin.size || (state == State.CONFIRM_PIN && pin.size > viewModel.pin.size)) {
                        nextStep()
                    }
                } else {
                    if (pin.size == 4) {
                        nextStep()
                    }
                }
            }

            override fun onBack() {
                if (pin.size > 0) {
                    pin.removeAt(pin.lastIndex)
                    pinPreviewView.prev()
                }
            }

            override fun onCancel() {

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
                    viewModel.encryptKeys()
                }, 200)
            } else {
                pinPreviewView.shake()
                setState(State.CONFIRM_PIN)
            }
        } else {
            viewModel.setPin(pin)
            if (state == State.DECRYPT) {
                viewModel.checkPin()
            } else {
                setState(State.CONFIRM_PIN)
            }
        }
    }

    private fun setState(newState: State) {
        when (newState) {
            State.DECRYPT -> {
                pinPreviewView.mode = PinPreviewView.PinType.EXTENDED
                pageTitleView.setText(R.string.set_pin_enter_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                messageView.visibility = View.GONE
                confirmButtonView.visibility = View.VISIBLE
                viewModel.pin.clear()
                pin.clear()
            }
            State.SET_PIN -> {
                pinPreviewView.mode = PinPreviewView.PinType.STANDARD
                pageTitleView.setText(R.string.set_pin_set_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                messageView.visibility = View.VISIBLE
                confirmButtonView.visibility = View.GONE
                pinPreviewView.clear()
                viewModel.pin.clear()
                pin.clear()
            }
            State.CONFIRM_PIN -> {
                pageTitleView.setText(R.string.set_pin_confirm_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                messageView.visibility = View.VISIBLE
                confirmButtonView.visibility = View.GONE
                Handler().postDelayed({
                    pinPreviewView.clear()
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
                pageTitleView.setText(R.string.set_pin_decrypting)
                if (pinProgressSwitcherView.currentView.id == R.id.pin_preview) {
                    pinProgressSwitcherView.showNext()
                }
                pageMessageView.visibility = View.INVISIBLE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(false)
                confirmButtonView.visibility = View.GONE
            }
        }
        state = newState
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(SetPinViewModel::class.java)
        viewModel.encryptWalletLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    if (state == State.DECRYPTING) {
                        setState(State.DECRYPT)
                        pinPreviewView.shake()
                    } else {
                        android.widget.Toast.makeText(this, "Encrypting error", android.widget.Toast.LENGTH_LONG).show()
                        setState(State.CONFIRM_PIN)
                    }
                }
                Status.LOADING -> {
                    setState(if (state == State.CONFIRM_PIN) State.ENCRYPTING else State.DECRYPTING)
                }
                Status.SUCCESS -> {
                    if (state == State.DECRYPTING) {
                        setState(State.SET_PIN)
                    } else {
                        viewModel.initWallet()
                    }
                }
            }
        })
        viewModel.startVerifySeedActivity.observe(this, Observer {
            val intent = VerifySeedActivity.createIntent(this, seed.toTypedArray())
            intent.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        })
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

    override fun startActivity(intent: Intent?) {
        super.startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
