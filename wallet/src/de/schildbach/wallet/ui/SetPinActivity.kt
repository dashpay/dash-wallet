package de.schildbach.wallet.ui

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
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R

class SetPinActivity : AppCompatActivity() {

    private lateinit var numericKeyboardView: NumericKeyboardView

    private lateinit var viewModel: SetPinViewModel

    private lateinit var pinProgressSwitcherView: ViewSwitcher

    private lateinit var pinPreviewView: PinPreviewView

    private lateinit var pageTitleView: TextView

    private lateinit var pageMessageView: TextView

    val pin = arrayListOf<Int>()

    var legacyPinMode = false

    private enum class State {
        SET_PIN,
        CONFIRM_PIN,
        ENCRYPTING
    }

    private var state = State.SET_PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_pin)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setTitle(R.string.create_pin_create_new_wallet)
        }

        initView()
        initViewModel()
    }

    private fun initView() {
        pinProgressSwitcherView = findViewById(R.id.pin_progress_switcher)
        pinPreviewView = findViewById(R.id.pin_preview)
        pageTitleView = findViewById(R.id.page_title)
        pageMessageView = findViewById(R.id.message)
        numericKeyboardView = findViewById(R.id.numeric_keyboard)

        pinPreviewView.mode = if (legacyPinMode) PinPreviewView.PinType.EXTENDED else PinPreviewView.PinType.STANDARD
        numericKeyboardView.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                if (pin.size < 4 || legacyPinMode) {
                    pin.add(number)
                    pinPreviewView.next()
                }

                if (legacyPinMode) {
                    if (pin.size == viewModel.pin.size || (state == State.CONFIRM_PIN && pin.size > viewModel.pin.size)) {
                        nextStep()
                    }
                } else {
                    if (pin.size == 4) {
                        nextStep()
                    }
                }
                println("PIN: $pin")
            }

            override fun onBack() {
                if (pin.size > 0) {
                    pin.removeAt(pin.lastIndex)
                    pinPreviewView.prev()
                }
                println("PIN: $pin")
            }

            override fun onCancel() {
                nextStep()
            }
        }
    }

    private fun nextStep() {
        if (state == State.CONFIRM_PIN) {
            if (pin == viewModel.pin) {
                viewModel.encryptKeys()
            } else {
                pinPreviewView.shake()
            }
        } else {
            setState(State.CONFIRM_PIN)
        }
    }

    private fun setState(newState: State) {
        when (newState) {
            State.SET_PIN -> {
                pageTitleView.setText(R.string.create_pin_set_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                viewModel.pin.clear()
            }
            State.CONFIRM_PIN -> {
                pageTitleView.setText(R.string.create_pin_confirm_pin)
                if (pinProgressSwitcherView.currentView.id == R.id.progress) {
                    pinProgressSwitcherView.showPrevious()
                }
                pageMessageView.visibility = View.VISIBLE
                numericKeyboardView.visibility = View.VISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                if (legacyPinMode) {
                    pinPreviewView.clear()
                } else {
                    Handler().postDelayed({
                        pinPreviewView.clear()
                    }, 200)
                }
                viewModel.pin.clear()
                viewModel.pin.addAll(pin)
                pin.clear()
            }
            State.ENCRYPTING -> {
                pageTitleView.setText(R.string.create_pin_encrypting)
                if (pinProgressSwitcherView.currentView.id == R.id.pin_preview) {
                    pinProgressSwitcherView.showNext()
                }
                pageMessageView.visibility = View.INVISIBLE
                numericKeyboardView.visibility = View.INVISIBLE
                supportActionBar!!.setDisplayHomeAsUpEnabled(false)
            }
        }
        state = newState
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(SetPinViewModel::class.java)
        viewModel.encryptWalletLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    android.widget.Toast.makeText(this, "Encrypting error", android.widget.Toast.LENGTH_LONG).show()
                    setState(State.CONFIRM_PIN)
                }
                Status.LOADING -> {
                    setState(State.ENCRYPTING)
                }
                Status.SUCCESS -> {
                    viewModel.initWallet()
                }
            }
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivity(Intent(this, it.first))
            if (it.second) {
                finish()
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                when(state) {
                    State.SET_PIN -> finish()
                    else -> setState(State.SET_PIN)
                }
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun startActivity(intent: Intent?) {
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        super.startActivity(intent)
    }
}
