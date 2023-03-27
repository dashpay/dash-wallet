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
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.security.PinRetryController
import de.schildbach.wallet.ui.widget.PinPreviewView
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityAppUpdateBinding
import org.dash.wallet.common.Configuration
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class AppUpgradeActivity : AppCompatActivity() {

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, AppUpgradeActivity::class.java)
        }
    }

    @Inject lateinit var configuration: Configuration
    @Inject lateinit var pinRetryController: PinRetryController
    private lateinit var binding: ActivityAppUpdateBinding

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
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        configuration.pinLength = PinPreviewView.CUSTOM_PIN_LENGTH
        setContentView(binding.root)
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
        binding.titlePane.visibility = View.INVISIBLE
        binding.dashLogo.visibility = View.INVISIBLE
        SetupPinDuringUpgradeDialog.show(this) { success, pin ->
            if (success == null) {
                temporaryLockCheckRunnable.run()
            } else if (success == true && !pin.isNullOrEmpty()) {
                onCorrectPin(pin)
            } else {
                Toast.makeText(this, "Unable to encrypt wallet", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onCorrectPin(pin: String) {
        configuration.pinLength = pin.length
        startActivity(WalletActivity.createIntent(this))
    }

    private fun walletLocked() {
        binding.titlePane.visibility = View.VISIBLE
        binding.dashLogo.visibility = View.VISIBLE
        temporaryLockCheckHandler.postDelayed(temporaryLockCheckRunnable, temporaryLockCheckInterval)
        binding.actionTitle.setText(R.string.wallet_lock_wallet_disabled)
        binding.actionSubtitle.text = pinRetryController.getWalletTemporaryLockedMessage(resources)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
