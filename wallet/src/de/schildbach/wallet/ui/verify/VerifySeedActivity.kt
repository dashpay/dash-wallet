/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.verify

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import de.schildbach.wallet.ui.DecryptSeedViewModel
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.InteractionAwareActivity
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : InteractionAwareActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(VerifySeedActivity::class.java)
        private const val EXTRA_SEED = "extra_seed"
        private const val EXTRA_PIN = "extra_pin"
        private const val NAVIGATE_TO_HOME = "navigate_to_home"

        @JvmStatic
        fun createIntent(context: Context, seed: Array<String>, startMainActivityOnClose: Boolean = true): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_SEED, seed)
            intent.putExtra(NAVIGATE_TO_HOME, startMainActivityOnClose)
            return intent
        }

        @JvmStatic
        fun createIntent(context: Context, pin: String, startMainActivityOnClose: Boolean = true): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            intent.putExtra(NAVIGATE_TO_HOME, startMainActivityOnClose)
            return intent
        }
    }

    private val viewModel: DecryptSeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        super.setSecuredActivity(true)
        setContentView(R.layout.activity_verify_seed)

        if (intent.extras!!.containsKey(EXTRA_SEED)) {
            viewModel.init(intent.extras!!.getStringArray(EXTRA_SEED)!!)
        } else {
            val pin = intent.extras!!.getString(EXTRA_PIN)!!

            lifecycleScope.launch {
                try {
                    viewModel.init(pin)
                } catch (ex: Exception) {
                    log.error("Failed to decrypt seed", ex)
                    finish()
                }
            }
        }
    }

    override fun finish() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        val launchMainActivity = intent.getBooleanExtra(NAVIGATE_TO_HOME, true)

        if (launchMainActivity) {
            startActivity(Intent(this, WalletActivity::class.java))
        }

        super.finish()
    }
}
