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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_onboarding.*
import kotlinx.android.synthetic.main.activity_onboarding_perm_lock.*
import org.dash.wallet.common.ui.DialogBuilder

private const val REGULAR_FLOW_TUTORIAL_REQUEST_CODE = 0

class OnboardingActivity : RestoreFromFileActivity() {

    companion object {
        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, OnboardingActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    private lateinit var viewModel: OnboardingViewModel

    private lateinit var walletApplication: WalletApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PinRetryController.getInstance().isLockedForever) {
            setContentView(R.layout.activity_onboarding_perm_lock)
            getStatusBarHeightPx()
            hideSlogan()
            close_app.setOnClickListener {
                finish()
            }
            wipe_wallet.setOnClickListener {
                ResetWalletDialog.newInstance().show(supportFragmentManager, "reset_wallet_dialog")
            }
            return
        }

        setContentView(R.layout.activity_onboarding)
        slogan.setPadding(slogan.paddingLeft, slogan.paddingTop, slogan.paddingRight, getStatusBarHeightPx())

        viewModel = ViewModelProviders.of(this).get(OnboardingViewModel::class.java)

        walletApplication = (application as WalletApplication)
        if (walletApplication.walletFileExists()) {
            regularFlow()
        } else {
            if (walletApplication.wallet == null) {
                onboarding()
            } else {
                if (walletApplication.wallet.isEncrypted) {
                    walletApplication.fullInitialization()
                    regularFlow()
                } else {
                    onboarding()
                }
            }
        }
    }

    private fun regularFlow() {
        if (walletApplication.configuration.v7TutorialCompleted) {
            upgradeOrStartMainActivity()
        } else {
            startActivityForResult(Intent(this, WelcomeActivity::class.java),
                    REGULAR_FLOW_TUTORIAL_REQUEST_CODE)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun upgradeOrStartMainActivity() {
        if (SecurityGuard.isConfiguredQuickCheck()) {
            startMainActivity()
        } else {
            startActivity(AppUpgradeActivity.createIntent(this))
        }
    }

    private fun startMainActivity() {
        val intent = if (walletApplication.configuration.autoLogoutEnabled) {
            LockScreenActivity.createIntent(this)
        } else {
            WalletActivity.createIntent(this)
        }
        startActivity(intent)
        finish()
    }

    private fun onboarding() {
        initView()
        initViewModel()
        showButtonsDelayed()
        if (!walletApplication.configuration.v7TutorialCompleted) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun initView() {
        create_new_wallet.setOnClickListener {
            viewModel.createNewWallet()
        }
        recovery_wallet.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            startActivity(Intent(this, RestoreWalletFromSeedActivity::class.java))
        }
        restore_wallet.setOnClickListener {
            restoreWalletFromFile()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showToastAction.observe(this, Observer {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        })
        viewModel.showRestoreWalletFailureAction.observe(this, Observer {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }
            val dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title)
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, message))
            dialog.setPositiveButton(R.string.button_dismiss, null)
            dialog.setNegativeButton(R.string.button_retry) { _, _ ->
                RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
            }
            dialog.show()
        })
        viewModel.startActivityAction.observe(this, Observer {
            startActivity(it)
        })
    }

    private fun showButtonsDelayed() {
        Handler().postDelayed({
            hideSlogan()
            findViewById<LinearLayout>(R.id.buttons).visibility = View.VISIBLE
        }, 1000)
    }

    private fun hideSlogan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val sloganDrawable = (window.decorView.background as LayerDrawable).getDrawable(1)
            sloganDrawable.mutate().alpha = 0
        }
    }

    private fun getStatusBarHeightPx(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REGULAR_FLOW_TUTORIAL_REQUEST_CODE) {
            upgradeOrStartMainActivity()
        }
    }
}
