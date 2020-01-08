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
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : AppCompatActivity(), VerifySeedActions {

    companion object {

        private const val EXTRA_SEED = "extra_seed"
        private const val EXTRA_VIEW_ONLY = "extra_write_down"

        fun createIntent(context: Context, seed: Array<String>, viewOnly: Boolean = false): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_SEED, seed)
            intent.putExtra(EXTRA_VIEW_ONLY, viewOnly)
            return intent
        }
    }

    private var seed: Array<String> = arrayOf()
    private var viewOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify_seed)

        seed = if (intent.extras?.containsKey(EXTRA_SEED)!!) {
            intent.extras!!.getStringArray(EXTRA_SEED)!!
        } else {
            throw IllegalStateException("This activity needs to receive a String[] Intent Extra " +
                    "containing the recovery seed.")
        }
        viewOnly = if(intent.extras.containsKey(EXTRA_VIEW_ONLY)) {
            intent.extras!!.getBoolean(EXTRA_VIEW_ONLY)!!
        } else {
            false
        }

        if(viewOnly) {
            supportFragmentManager.beginTransaction().add(R.id.container,
                    VerifySeedWriteDownFragment.newInstance(seed)).commit()
        } else {
            supportFragmentManager.beginTransaction().add(R.id.container,
                    VerifySeedSecureNowFragment.newInstance()).commit()
        }
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction().setCustomAnimations(R.anim.slide_in_right,
                R.anim.slide_out_left).replace(R.id.container, fragment).commit()
    }

    override fun startSeedVerification() {
        replaceFragment(VerifySeedItIsImportantFragment.newInstance())
    }

    override fun skipSeedVerification() {
        WalletApplication.getInstance().configuration.armBackupSeedReminder()
        WalletApplication.getInstance().configuration.setBackupSeedLastDismissedReminder()
        goHome()
    }

    override fun showRecoveryPhrase() {
        val verifySeedWriteDownFragment = VerifySeedWriteDownFragment.newInstance(seed)
        replaceFragment(verifySeedWriteDownFragment)
    }

    override fun onVerifyWriteDown() {
        if(viewOnly) {
            goHome()
        } else {
            supportFragmentManager.beginTransaction().replace(R.id.container,
                    VerifySeedConfirmFragment.newInstance(seed)).commit()
        }
    }

    override fun onSeedVerified() {
        WalletApplication.getInstance().configuration.disarmBackupSeedReminder()
        goHome()
    }

    override fun onBackPressed() {
        skipSeedVerification()
    }

    private fun goHome() {
        startActivity(Intent(this, WalletActivity::class.java))
        finish()
    }

    override fun onUserInteraction() {
        (application as WalletApplication).resetAutoLogoutTimer()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}