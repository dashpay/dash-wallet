/*
 * Copyright 2019 Dash Core Group.
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

package de.schildbach.wallet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.backup.RestoreFromFileActivity
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.preference.PinRetryController
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityOnboardingBinding
import de.schildbach.wallet_test.databinding.ActivityOnboardingPermLockBinding
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.getMainTask
import org.slf4j.LoggerFactory
import javax.inject.Inject

private const val REGULAR_FLOW_TUTORIAL_REQUEST_CODE = 0
const val SET_PIN_REQUEST_CODE = 1
private const val RESTORE_PHRASE_REQUEST_CODE = 2
private const val RESTORE_FILE_REQUEST_CODE = 3
private const val UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE = 4

@AndroidEntryPoint
class OnboardingActivity : RestoreFromFileActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(OnboardingActivity::class.java)
        private const val EXTRA_UPGRADE = "upgrade"
        @JvmStatic
        @JvmOverloads
        fun createIntent(context: Context, upgrade: Boolean = false): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_UPGRADE, upgrade)
            }
        }

        // this function removes the previous task which may still be running after an
        // app upgrade
        private fun removePreviousTask(activity: AppCompatActivity) {
            log.info("do we need to remove the previous task")
            val mainTask = activity.getMainTask()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (mainTask.taskInfo.taskId != activity.taskId) {
                    log.info("removing previous task")
                    mainTask.finishAndRemoveTask()
                }
            } else {
                // when installing over 6.6.6 or lower, topActivity is null
                if (mainTask.taskInfo.topActivity?.className != this::class.java.name) {
                    log.info("removing previous task < Android Q")
                    mainTask.finishAndRemoveTask()
                }
            }
        }
    }

    private val viewModel by viewModels<OnboardingViewModel>()
    private lateinit var binding: ActivityOnboardingBinding

    @Inject
    lateinit var walletApplication: WalletApplication
    @Inject
    lateinit var analytics: AnalyticsService
    @Inject
    lateinit var config: Configuration
    @Inject
    lateinit var pinRetryController: PinRetryController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (pinRetryController.isLockedForever) {
            val binding = ActivityOnboardingPermLockBinding.inflate(layoutInflater)
            setContentView(binding.root)
            getStatusBarHeightPx()
            hideSlogan()
            binding.closeApp.setOnClickListener {
                finish()
            }
            binding.wipeWallet.setOnClickListener {
                ResetWalletDialog.newInstance().show(supportFragmentManager, "reset_wallet_dialog")
            }
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.slogan.setPadding(
            binding.slogan.paddingLeft,
            binding.slogan.paddingTop,
            binding.slogan.paddingRight,
            getStatusBarHeightPx()
        )

        // TODO: we should decouple the logic from view interactions
        // and move some of this to the viewModel, wrapping it in tests.
        // The viewModel already has some related events
        if (walletApplication.walletFileExists()) {
            if (!walletApplication.wallet!!.isEncrypted) {
                unencryptedFlow()
            } else {
                if (walletApplication.isWalletUpgradedToBIP44) {
                    regularFlow()
                } else {
                    upgradeToBIP44Flow()
                }
            }
        } else {
            if (walletApplication.wallet == null) {
                onboarding()
            } else {
                if (walletApplication.wallet!!.isEncrypted) {
                    walletApplication.fullInitialization()
                    regularFlow()
                } else {
                    onboarding()
                }
            }
        }
        // during an upgrade, for some reason the previous screen is still in the recent app list
        // this will find it and close it
        if (intent.extras?.getBoolean(EXTRA_UPGRADE) == true) {
            removePreviousTask(this)
        }
    }

    // This is due to a wallet being created in an invalid way
    // such that the wallet is not encrypted
    private fun unencryptedFlow() {
        log.info("the wallet is not encrypted -- the wallet will be upgraded")
        if (config.v7TutorialCompleted) {
            upgradeUnencryptedWallet()
        } else {
            startActivityForResult(
                Intent(this, WelcomeActivity::class.java),
                UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE
            )
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    private fun upgradeUnencryptedWallet() {
        viewModel.finishUnecryptedWalletUpgradeAction.observe(this) {
            startActivityForResult(
                SetPinActivity.createIntent(application, R.string.set_pin_upgrade_wallet, upgradingWallet = true),
                SET_PIN_REQUEST_CODE
            )
        }
        viewModel.upgradeUnencryptedWallet()
    }

    private fun upgradeToBIP44Flow() {
        // for now do nothing extra, it will be handled in WalletActivity
        regularFlow()
    }

    private fun regularFlow() {
        if (config.v7TutorialCompleted) {
            upgradeOrStartMainActivity()
        } else {
            startActivityForResult(
                Intent(this, WelcomeActivity::class.java),
                REGULAR_FLOW_TUTORIAL_REQUEST_CODE
            )
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
        val intent = Intent(this, WalletActivity::class.java).apply {
            putExtras(this@OnboardingActivity.intent.extras ?: Bundle())
        }
        startActivity(intent)
        finish()
    }

    private fun onboarding() {
        initView()
        initViewModel()
        showButtonsDelayed()
        if (!config.v7TutorialCompleted) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun initView() {
        binding.createNewWallet.setOnClickListener {
            viewModel.createNewWallet()
        }
        binding.recoveryWallet.setOnClickListener {
            walletApplication.initEnvironmentIfNeeded()
            startActivityForResult(Intent(this, RestoreWalletFromSeedActivity::class.java), REQUEST_CODE_RESTORE_WALLET)
        }
        binding.restoreWallet.setOnClickListener {
            restoreWalletFromFile()
        }
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showToastAction.observe(this) {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        viewModel.showRestoreWalletFailureAction.observe(this) {
            val message = when {
                TextUtils.isEmpty(it.message) -> it.javaClass.simpleName
                else -> it.message!!
            }

            AdaptiveDialog.create(
                R.drawable.ic_error,
                title = getString(R.string.import_export_keys_dialog_failure_title),
                message = getString(R.string.import_keys_dialog_failure, message),
                positiveButtonText = getString(R.string.button_dismiss),
                negativeButtonText = getString(R.string.retry)
            ).show(this) { dismiss ->
                if (dismiss == false) {
                    RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
                }
            }
        }
        viewModel.finishCreateNewWalletAction.observe(this) {
            startActivityForResult(
                SetPinActivity.createIntent(application, R.string.set_pin_create_new_wallet),
                SET_PIN_REQUEST_CODE
            )
        }
    }

    private fun showButtonsDelayed() {
        binding.buttons.postDelayed({
            hideSlogan()
            binding.buttons.isVisible = true
        }, 1000)
    }

    private fun hideSlogan() {
        val sloganDrawable = (window.decorView.background as LayerDrawable).getDrawable(1)
        sloganDrawable.mutate().alpha = 0
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
        } else if (requestCode == UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE) {
            upgradeUnencryptedWallet()
        } else if (
            (requestCode == SET_PIN_REQUEST_CODE || requestCode == RESTORE_PHRASE_REQUEST_CODE) &&
            resultCode == Activity.RESULT_OK
        ) {
            finish()
        }
    }
}
