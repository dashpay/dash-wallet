package de.schildbach.wallet.ui

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.widget.NumericKeyboardView
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R

class EncryptKeysActivity : AppCompatActivity() {

    private lateinit var numericKeyboardView: NumericKeyboardView

    private lateinit var pinRetryController: PinRetryController

    private val handler = Handler()
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var fingerprintHelper: FingerprintHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_encrypt_keys)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setTitle(R.string.onboarding_create_new_wallet)
        }

        initView()

        backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
        fingerprintHelper = FingerprintHelper(this)

        this.pinRetryController = PinRetryController(this)
    }

    private fun initView() {
        numericKeyboardView = findViewById(R.id.numeric_keyboard)
        numericKeyboardView.onKeyboardActionListener = object : NumericKeyboardView.OnKeyboardActionListener {

            override fun onNumber(number: Int) {
                val pinPreviewView = findViewById<PinPreviewView>(R.id.pin_preview)
                pinPreviewView.next()
            }

            override fun onBack() {
                val pinPreviewView = findViewById<PinPreviewView>(R.id.pin_preview)
//                pinPreviewView.removePinView()
                pinPreviewView.prev()
//                pinPreviewView.mode = PinPreviewView.PinType.EXTENDED
            }

            override fun onCancel() {
                val pinPreviewView = findViewById<PinPreviewView>(R.id.pin_preview)
//                pinPreviewView.addPinView()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }
}
