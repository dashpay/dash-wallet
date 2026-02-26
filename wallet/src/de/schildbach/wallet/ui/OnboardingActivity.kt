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
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.backup.RestoreFromFileActivity
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.security.PinRetryController
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet.security.SecurityGuard
import de.schildbach.wallet.ui.onboarding.SelectSecurityLevelActivity
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.onboarding.WelcomePagerAdapter
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityOnboardingBinding
import de.schildbach.wallet_test.databinding.ActivityOnboardingPermLockBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.OnboardingState
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.ButtonLarge
import org.dash.wallet.common.ui.components.ButtonStyles
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.getMainTask
import org.slf4j.LoggerFactory
import javax.inject.Inject

private const val REGULAR_FLOW_TUTORIAL_REQUEST_CODE = 0
const val SET_PIN_REQUEST_CODE = 1
private const val RESTORE_PHRASE_REQUEST_CODE = 2
private const val RESTORE_FILE_REQUEST_CODE = 3
private const val UPGRADE_NONENCRYPTED_FLOW_TUTORIAL_REQUEST_CODE = 4

public enum class OnboardingPath {
    Create,
    RestoreSeed,
    RestoreFile
}

@AndroidEntryPoint
class OnboardingActivity : RestoreFromFileActivity() {

    companion object {
        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_UPGRADE = "upgrade"
        private const val EXTRA_ONBOARDING_PATH = "onboarding_path"
        private val log = LoggerFactory.getLogger(OnboardingActivity::class.java)

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

        fun createIntent(context: Context, invite: InvitationLinkData): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_INVITE, invite)
            }
        }
    }

    private val viewModel by viewModels<OnboardingViewModel>()
    private val inviteHandlerViewModel by viewModels<InviteHandlerViewModel>()
    private lateinit var binding: ActivityOnboardingBinding

    @Inject
    lateinit var walletApplication: WalletApplication
    @Inject
    lateinit var config: Configuration
    @Inject
    lateinit var pinRetryController: PinRetryController

    private val selectWordCountLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            viewModel.createNewWallet(
                result.data!!.extras!!.getInt(SelectSecurityLevelActivity.EXTRA_WORD_COUNT)
            )
        }
    }

    private val onPageChanged = object : ViewPager2.OnPageChangeCallback() {

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (pinRetryController.isLockedForever) {
            val binding = ActivityOnboardingPermLockBinding.inflate(layoutInflater)
            setContentView(binding.root)
            getStatusBarHeightPx()
            binding.closeApp.setOnClickListener {
                finish()
            }
            binding.wipeWallet.setOnClickListener {
                ResetWalletDialog.newInstance(viewModel.analytics).show(supportFragmentManager, "reset_wallet_dialog")
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

        OnboardingState.init(walletApplication.configuration)
        OnboardingState.clear()

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

        // Welcome Page View
        binding.viewpager.adapter = WelcomePagerAdapter(this)
        binding.pageIndicator.setViewPager2(binding.viewpager)


        binding.viewpager.registerOnPageChangeCallback(onPageChanged)

        // during an upgrade, for some reason the previous screen is still in the recent app list
        // this will find it and close it
        if (intent.extras?.getBoolean(EXTRA_UPGRADE) == true) {
            removePreviousTask(this)
        }
        val invite: InvitationLinkData? = intent?.getParcelableExtra(EXTRA_INVITE)
        lifecycleScope.launch {
            invite?.let {
                log.info("saving invite for later: ${invite?.link}")
                inviteHandlerViewModel.setInvitationLink(it, true)
            }
        }

        binding.composeContainer.setContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp, 10.dp, 20.dp, 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ButtonLarge(
                    onClick = { createNewWallet() },
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = ButtonStyles.whiteWithBlueText(),
                    R.string.onboarding_create_wallet
                )
                ButtonLarge(
                    onClick = { recoverWalletFromSeedPhrase() },
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = ButtonStyles.white10WithWhiteText(),
                    R.string.onboarding_recover_wallet
                )
                if (BuildConfig.DEBUG) {
                    ButtonLarge(
                        onClick = { restoreWallet() },
                        modifier = Modifier
                            .fillMaxWidth(),
                        colors = ButtonStyles.white10WithWhiteText(),
                        R.string.onboarding_restore_wallet
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.viewpager.unregisterOnPageChangeCallback(onPageChanged)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val invite: InvitationLinkData? = intent?.getParcelableExtra(EXTRA_INVITE)
        if (invite != null && inviteHandlerViewModel.invitation.value != null) {
            // TODO: wrong message
            log.info("handling invite: already processing invite")
            InviteHandler(this, viewModel.analytics).showInviteWhileOnboardingInProgressDialog()
        } else if (invite != null) {
            log.info("saving invite for later: ${invite.link}")
            lifecycleScope.launch {
                inviteHandlerViewModel.setInvitationLink(invite, true)
            }
        }
    }

    // This is due to a wallet being created in an invalid way
    // such that the wallet is not encrypted
    private fun unencryptedFlow() {
        log.info("the wallet is not encrypted -- the wallet will be upgraded")
        upgradeUnencryptedWallet()
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
        upgradeOrStartMainActivity()
    }

    private fun upgradeOrStartMainActivity() {
        if (SecurityGuard.isConfiguredQuickCheck()) {
            startMainActivity()
        } else {
            startActivity(AppUpgradeActivity.createIntent(this))
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtras(this@OnboardingActivity.intent.extras ?: Bundle())
        }
        startActivity(intent)
        finish()
    }

    private fun onboarding() {
        initViewModel()
    }

    private fun createNewWallet() {
        selectWordCountLauncher.launch(
            Intent(this, SelectSecurityLevelActivity::class.java)
        )
    }

    private fun recoverWalletFromSeedPhrase() {
        viewModel.logEvent(AnalyticsConstants.Onboarding.RECOVERY)
        walletApplication.initEnvironmentIfNeeded()
        startActivityForResult(Intent(this, RestoreWalletFromSeedActivity::class.java), REQUEST_CODE_RESTORE_WALLET)
    }

    private fun restoreWallet() {
        viewModel.logEvent(AnalyticsConstants.Onboarding.RESTORE_FROM_FILE)
        restoreWalletFromFile()
    }

    @SuppressLint("StringFormatInvalid")
    private fun initViewModel() {
        viewModel.showToastAction.observe(this) {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }

        viewModel.finishCreateNewWalletAction.observe(this) {
            startActivityForResult(
                SetPinActivity.createIntent(
                    application,
                    R.string.set_pin_create_new_wallet,
                    onboarding = true,
                    onboardingPath = OnboardingPath.Create
                ),
                SET_PIN_REQUEST_CODE
            )
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

    @Deprecated("Deprecated in Java")
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
