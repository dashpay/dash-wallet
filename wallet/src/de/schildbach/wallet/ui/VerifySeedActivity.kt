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
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import de.schildbach.wallet.ui.main.MainActivity

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
        private const val NAVIGATE_TO_HOME = "navigate_to_home"

        @JvmStatic
        fun createIntent(context: Context, seed: Array<String>, goHomeOnClose: Boolean = true): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_SEED, seed)
            intent.putExtra(NAVIGATE_TO_HOME, goHomeOnClose)
            return intent
        }

        @JvmStatic
        fun createIntent(context: Context, pin: String, goHomeOnClose: Boolean = true): Intent {
            val intent = Intent(context, VerifySeedActivity::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            intent.putExtra(NAVIGATE_TO_HOME, goHomeOnClose)
            return intent
        }
    }

    enum class VerificationStep {
        ViewImportantInfo,
        ShowRecoveryPhrase,
        VerifyRecoveryPhrase
    }

    var currentFragment = VerificationStep.ViewImportantInfo

    private val decryptSeedViewModel: DecryptSeedViewModel by viewModels()

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
        decryptSeedViewModel.decryptSeedLiveData.observe(this) {
            when (it.status) {
                Status.ERROR -> {
                    finish()
                }
                Status.SUCCESS -> {
                    val deterministicSeed = it.data!!.first
                    seed = deterministicSeed!!.mnemonicCode!!.toTypedArray()
                }
                else -> { }
            }
        }
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
        supportFragmentManager.executePendingTransactions()
        super.setSecuredActivity(false)
    }

    override fun skipSeedVerification() {
        goBack()
    }

    override fun showRecoveryPhrase() {
        super.setSecuredActivity(true)
        currentFragment = VerificationStep.ShowRecoveryPhrase
        val verifySeedWriteDownFragment = VerifySeedWriteDownFragment.newInstance(seed)
        replaceFragment(verifySeedWriteDownFragment)
    }

    override fun onVerifyWriteDown() {
        super.setSecuredActivity(true)
        currentFragment = VerificationStep.VerifyRecoveryPhrase
        supportFragmentManager.beginTransaction().replace(R.id.container,
                VerifySeedConfirmFragment.newInstance(seed)).commit()
    }

    override fun onSeedVerified() {
        WalletApplication.getInstance().configuration.apply {
            disarmBackupSeedReminder()
            setLastBackupSeedTime()
        }
        goBack()
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

    private fun goBack() {
        val navigateToHome = intent.getBooleanExtra(NAVIGATE_TO_HOME, true)

        if (navigateToHome) {
            startActivity(Intent(this, MainActivity::class.java))
        }

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