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
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.CancellationSignal
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_lock_screen.*

class LockScreenActivity : AppCompatActivity() {

    companion object {

        private const val EXTRA_TITLE_RES_ID = "extra_title_res_id"

        @JvmStatic
        @JvmOverloads
        fun createIntent(context: Context, titleResId: Int = 0): Intent {
            val intent = Intent(context, LockScreenActivity::class.java)
            intent.putExtra(EXTRA_TITLE_RES_ID, titleResId)
            return intent
        }
    }

    private lateinit var viewModel: LockScreenViewModel

    private enum class State {
        ENTER_PIN,
        USE_FINGERPRINT,
    }

    private lateinit var state: State

    private var fingerprintHelper: FingerprintHelper? = null
    private lateinit var fingerprintCancellationSignal: CancellationSignal

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)

        initView()
        initViewModel()
    }

    private fun initView() {
//        CheckPinDialog.show(supportFragmentManager, 1)
        initFingerprint()
        action_login_with_pin.setOnClickListener {
            setState(State.ENTER_PIN)
        }
        action_login_with_fingerprint.setOnClickListener {
            setState(State.USE_FINGERPRINT)
        }
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this).get(LockScreenViewModel::class.java)
    }

    private fun setState(state: State) {
        when (state) {
            State.ENTER_PIN -> {
                numeric_keyboard.visibility = View.VISIBLE
                action_login_with_pin.visibility = View.GONE
                action_login_with_fingerprint.visibility = View.VISIBLE
                pin_preview.visibility = View.VISIBLE
                fingerprint_view.visibility = View.GONE
                action_title.setText(R.string.lock_enter_pin)
            }
            State.USE_FINGERPRINT -> {
                numeric_keyboard.visibility = View.GONE
                action_login_with_pin.visibility = View.VISIBLE
                action_login_with_fingerprint.visibility = View.GONE
                pin_preview.visibility = View.GONE
                fingerprint_view.visibility = View.VISIBLE
                action_title.setText(R.string.lock_unlock_with_fingerprint)
            }
        }
    }

    private fun initFingerprint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = FingerprintHelper(this)
            fingerprintHelper?.run {
                if (init()) {
                    if (isFingerprintEnabled) {
                        setState(State.USE_FINGERPRINT)
                        startFingerprintListener()
                    }
                } else {
                    fingerprintHelper = null
                    setState(State.ENTER_PIN)
                }
            }
        } else {
            setState(State.ENTER_PIN)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun startFingerprintListener() {
        fingerprintCancellationSignal = CancellationSignal()
        fingerprintHelper!!.getPassword(fingerprintCancellationSignal, object : FingerprintHelper.Callback {
            override fun onSuccess(savedPass: String) {
                finish()
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

    override fun onBackPressed() {
        super.onBackPressed()
    }


    override fun onDestroy() {
        super.onDestroy()

        if (::fingerprintCancellationSignal.isInitialized) {
            fingerprintCancellationSignal.cancel()
        }
    }
}
