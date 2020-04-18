/*
 * Copyright 2020 Dash Core Group
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
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_app_update.*
import org.dash.wallet.common.Configuration
import java.util.concurrent.TimeUnit

class AppUpgradeActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, AppUpgradeActivity::class.java)
        }
    }

    lateinit var configuration: Configuration
    private lateinit var pinRetryController: PinRetryController

    private val temporaryLockCheckHandler = Handler()
    private val temporaryLockCheckInterval = TimeUnit.SECONDS.toMillis(10)
    private val temporaryLockCheckRunnable = Runnable {
        if (pinRetryController.isLocked) {
            walletLocked()
        } else {
            askForPin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update)

        configuration = WalletApplication.getInstance().configuration
        configuration.pinLength = PinPreviewView.CUSTOM_PIN_LENGTH

        pinRetryController = PinRetryController.getInstance()
    }

    override fun onStart() {
        super.onStart()
        temporaryLockCheckRunnable.run()
    }

    override fun onStop() {
        super.onStop()
        temporaryLockCheckHandler.removeCallbacks(temporaryLockCheckRunnable)
    }

    private fun askForPin() {
        title_pane.visibility = View.INVISIBLE
        dash_logo.visibility = View.INVISIBLE
        val checkPinSharedModel = ViewModelProviders.of(this)[CheckPinSharedModel::class.java]
        checkPinSharedModel.onCorrectPinCallback.observe(this, Observer<Pair<Int?, String?>> { (_, pin) ->
            onCorrectPin(pin!!)
        })
        checkPinSharedModel.onWalletEncryptedCallback.observe(this, Observer<String?> { pin ->
            if (pin == null) {
                Toast.makeText(this, "Unable to encrypt wallet", Toast.LENGTH_LONG).show()
            } else {
                onCorrectPin(pin)
            }
        })
        checkPinSharedModel.onCancelCallback.observe(this, Observer<Void> {
            temporaryLockCheckRunnable.run()
        })
        SetupPinDuringUpgradeDialog.show(this, 0)
    }

    private fun onCorrectPin(pin: String) {
        configuration.pinLength = pin.length
        startActivity(WalletActivity.createIntent(this))
    }

    private fun walletLocked() {
        title_pane.visibility = View.VISIBLE
        dash_logo.visibility = View.VISIBLE
        temporaryLockCheckHandler.postDelayed(temporaryLockCheckRunnable, temporaryLockCheckInterval)
        action_title.setText(R.string.wallet_lock_wallet_disabled)
        action_subtitle.text = pinRetryController.getWalletTemporaryLockedMessage(this)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
