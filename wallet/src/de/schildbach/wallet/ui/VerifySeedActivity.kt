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
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity

/**
 * @author Samuel Barbosa
 */
class VerifySeedActivity : InteractionAwareActivity(), VerifySeedActions {

    companion object {

        private const val EXTRA_SEED = "extra_seed"
        private const val EXTRA_PIN = "extra_pin"

        @JvmStatic
        fun createIntent(context: Context, seed: Array<String>): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_SEED, seed)
            return intent
        }

        @JvmStatic
        fun createIntent(context: Context, pin: String): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            return intent
        }
    }

    enum class VerificationStep {
        ViewImportantInfo,
        ShowRecoveryPhrase,
        VerifyRecoveryPhrase
    }

    var currentFragment = VerificationStep.ViewImportantInfo

    private lateinit var decryptSeedViewModel: DecryptSeedViewModel

    private var seed: Array<String> = arrayOf()

    private var goingBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify_seed)

        if (intent.extras!!.containsKey(EXTRA_SEED)) {
            seed = intent.extras!!.getStringArray(EXTRA_SEED)!!
        } else {
            initViewModel()
            val pin = intent.extras!!.getString(EXTRA_PIN)!!
            decryptSeedViewModel.checkPin(pin)
        }

        supportFragmentManager.beginTransaction().add(R.id.container,
                VerifySeedSecureNowFragment.newInstance()).commit()
    }

    private fun initViewModel() {
        decryptSeedViewModel = ViewModelProviders.of(this).get(DecryptSeedViewModel::class.java)
        decryptSeedViewModel.decryptSeedLiveData.observe(this, Observer {
            when (it.status) {
                Status.ERROR -> {
                    finish()
                }
                Status.SUCCESS -> {
                    val deterministicSeed = it.data!!.first
                    seed = deterministicSeed!!.mnemonicCode!!.toTypedArray()
                }
            }
        })
    }

    private fun replaceFragment(fragment: Fragment) {
        var enter = R.anim.slide_in_right
        var exit =  R.anim.slide_out_left
        if (goingBack) {
            enter =  R.anim.slide_in_left
            exit =  R.anim.slide_out_right
        }
        supportFragmentManager.beginTransaction().setCustomAnimations(enter,
                exit).replace(R.id.container, fragment).commit()
    }

    override fun startSeedVerification() {
        currentFragment = VerificationStep.ViewImportantInfo
        replaceFragment(VerifySeedItIsImportantFragment.newInstance())
    }

    override fun skipSeedVerification() {
        goHome()
    }

    override fun showRecoveryPhrase() {
        currentFragment = VerificationStep.ShowRecoveryPhrase
        val verifySeedWriteDownFragment = VerifySeedWriteDownFragment.newInstance(seed)
        replaceFragment(verifySeedWriteDownFragment)
    }

    override fun onVerifyWriteDown() {
        currentFragment = VerificationStep.VerifyRecoveryPhrase
        supportFragmentManager.beginTransaction().replace(R.id.container,
                VerifySeedConfirmFragment.newInstance(seed)).commit()
    }

    override fun onSeedVerified() {
        WalletApplication.getInstance().configuration.apply {
            disarmBackupSeedReminder()
            setLastBackupSeedTime()
        }
        goHome()
    }

    override fun onBackPressed() {
        goingBack = true
        when (currentFragment) {
            VerificationStep.ViewImportantInfo -> skipSeedVerification()
            VerificationStep.VerifyRecoveryPhrase -> showRecoveryPhrase()
            VerificationStep.ShowRecoveryPhrase -> startSeedVerification()
        }
        goingBack = false
    }

    private fun goHome() {
        startActivity(Intent(this, WalletActivity::class.java))
        finish()
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
}