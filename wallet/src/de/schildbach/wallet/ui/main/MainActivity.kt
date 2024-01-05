/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.invite.AcceptInviteActivity
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.invite.InviteSendContactRequestDialog
import de.schildbach.wallet.ui.main.WalletActivityExt.checkTimeSkew
import de.schildbach.wallet.ui.main.WalletActivityExt.handleFirebaseAction
import de.schildbach.wallet.ui.main.WalletActivityExt.requestDisableBatteryOptimisation
import de.schildbach.wallet.ui.main.WalletActivityExt.setupBottomNavigation
import de.schildbach.wallet.ui.main.WalletActivityExt.showFiatCurrencyChangeDetectedDialog
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.Nfc
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AbstractBindServiceActivity(), ActivityCompat.OnRequestPermissionsResultCallback,
    UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
    EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener {

    companion object {
        private val log = LoggerFactory.getLogger(MainActivity::class.java)

        const val EXTRA_RESET_BLOCKCHAIN = "reset_blockchain"
        private const val EXTRA_INVITE = "extra_invite"

        fun createIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java)
        }

        fun createIntent(context: Context, invite: InvitationLinkData): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_INVITE, invite)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val baseAlertDialogBuilder = BaseAlertDialogBuilder(this)
    private val viewModel: MainViewModel by viewModels()
    @Inject
    lateinit var config: Configuration
    private lateinit var binding: ActivityMainBinding
    private var isRestoringBackup = false
    private var showBackupWalletDialog = false
    private var retryCreationIfInProgress = true
    private var pendingInvite: InvitationLinkData? = null

    val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestDisableBatteryOptimisation()
    };

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        this.setupBottomNavigation(viewModel)

        initViewModel()
        handleCreateFromInvite()

        if (savedInstanceState == null) {
            checkAlerts()
        }
        config.touchLastUsed()

        handleIntent(intent)

        //Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            //Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false)
        }

        viewModel.currencyChangeDetected.observe(
            this
        ) { currencies: Pair<String?, String?> ->
            showFiatCurrencyChangeDetectedDialog(
                viewModel,
                currencies.component1()!!, currencies.component2()!!
            )
        }
    }

    override fun onStart() {
        super.onStart()

        if (!lockScreenDisplayed && config.showNotificationsExplainer) {
            explainPushNotifications()
        }
    }

    private fun handleCreateFromInvite() {
//        if (!config.hasBeenUsed() && config.onboardingInviteProcessing) { // TODO
        if (config.onboardingInviteProcessing) {
            if (config.isRestoringBackup) {
                binding.restoringWalletCover.isVisible = true
            } else {
                handleOnboardingInvite(true)
            }
        }
    }

    private fun handleOnboardingInvite(silentMode: Boolean) {
        val invite = InvitationLinkData(config.onboardingInvite, false)
        startActivity(InviteHandlerActivity.createIntent(this@MainActivity, invite, silentMode))
        config.setOnboardingInviteProcessingDone()
    }

    fun initViewModel() {
        viewModel.isAbleToCreateIdentityLiveData.observe(this) {
            // empty observer just to trigger data loading
            // viewModel is shared with some fragments keeping the observer active
            // inside the parent Activity will avoid recreation of relatively complex
            // isAbleToCreateIdentityData LiveData
        }
        viewModel.blockchainIdentity.observe(this) {
            if (it != null) {
                if (retryCreationIfInProgress && it.creationInProgress) {
                    retryCreationIfInProgress = false
                    if (it.usingInvite) {
                        startService(CreateIdentityService.createIntentForRetryFromInvite(this, false))
                    } else {
                        startService(CreateIdentityService.createIntentForRetry(this, false))
                    }
                }
                if (config.isRestoringBackup && config.onboardingInviteProcessing) {
                    config.setOnboardingInviteProcessingDone()
                    InviteHandler(this, viewModel.analytics).showUsernameAlreadyDialog()
                    binding.restoringWalletCover.isVisible = false
                }
            }
        }

        viewModel.platformRepo.onIdentityResolved = { identity ->
            if (identity == null && config.isRestoringBackup && config.onboardingInviteProcessing) {
                lifecycleScope.launch {
                    handleOnboardingInvite(false)
                    binding.restoringWalletCover.isVisible = false
                }
            }
        }
        viewModel.showCreateUsernameEvent.observe(this) {
            startActivity(Intent(this, CreateUsernameActivity::class.java))
        }
        viewModel.sendContactRequestState.observe(this) {
            config.inviter?.also { initInvitationUserId ->
                if (!config.inviterContactRequestSentInfoShown) {
                    it?.get(initInvitationUserId)?.apply {
                        if (status == Status.SUCCESS) {
                            log.info("showing successfully sent contact request dialog")
                            showInviteSendContactRequestDialog(initInvitationUserId)
                            config.inviterContactRequestSentInfoShown = true
                        }
                    }
                }
            }
        }

        viewModel.isBlockchainSynced.observe(this) { isSynced ->
            if (isSynced && config.onboardingInviteProcessing) {
                binding.restoringWalletCover.isVisible = false
                handleOnboardingInvite(false)
            }
        }
        viewModel.seriousErrorLiveData.observe(this) {
            if (it != null) {
                if (it.data != null && !viewModel.processingSeriousError) {
                    val messageId = when (it.data) {
                        SeriousError.MissingEncryptionIV -> {
                            R.string.serious_error_security_missing_iv
                        }
                        else -> {
                            R.string.serious_error_unknown
                        }
                    }
                    val dialog = FancyAlertDialog.newInstance(
                        R.string.serious_error_title,
                        messageId, R.drawable.ic_error,
                        R.string.button_ok,
                        R.string.button_cancel
                    )
                    dialog.show(supportFragmentManager, "serious_error_dialog")
                    viewModel.processingSeriousError = true
                }
            }
        }
    }

    private fun showInviteSendContactRequestDialog(initInvitationUserId: String) {
        lifecycleScope.launch {
            viewModel.getProfile(initInvitationUserId)?.let { profile ->
                val dialog = InviteSendContactRequestDialog.newInstance(this@MainActivity, profile)
                dialog.show(supportFragmentManager, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        turnOnAutoLogout()
        checkTimeSkew(viewModel)
        checkLowStorageAlert()
        checkWalletEncryptionDialog()
        viewModel.detectUserCountry()
        viewModel.startBlockchainService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent!!)
    }

    //BIP44 Wallet Upgrade Dialog Dismissed (Ok button pressed)
    override fun onUpgradeConfirmed() {
        if (isRestoringBackup) {
            checkRestoredWalletEncryptionDialog()
        } else {
            checkWalletEncryptionDialog()
        }
    }

    override fun onBackPressed() {
        if (!goBack()) {
            super.onBackPressed()
        }
    }

    private fun goBack(goHome: Boolean = false): Boolean {
        if (!goHome && supportFragmentManager.backStackEntryCount > 1) {
            supportFragmentManager.popBackStack()
            return true
        } else if (goHome || supportFragmentManager.backStackEntryCount == 1) {
            supportFragmentManager.popBackStack(null,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        return false
    }

    override fun onNewKeyChainEncrypted() {
        // TODO: can we remove this?
    }

    private fun showRestoreWalletFromSeedDialog() {
        RestoreWalletFromSeedDialogFragment.show(supportFragmentManager)
    }

    fun handleRestoreWalletFromSeed() {
        showRestoreWalletFromSeedDialog();
    }

    private fun showLowStorageAlertDialog() {
        baseAlertDialogBuilder.title = getString(R.string.wallet_low_storage_dialog_title)
        baseAlertDialogBuilder.message = getString(R.string.wallet_low_storage_dialog_msg)
        baseAlertDialogBuilder.positiveText =
            getString(R.string.wallet_low_storage_dialog_button_apps)
        baseAlertDialogBuilder.positiveAction = {
            startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            finish()
        }
        baseAlertDialogBuilder.negativeText = getString(R.string.button_dismiss)
        baseAlertDialogBuilder.showIcon = true
        alertDialog = baseAlertDialogBuilder.buildAlertDialog()
        alertDialog.show()
    }

    fun restoreWallet(wallet: Wallet?) {
        walletApplication.replaceWallet(wallet)
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        config.disarmBackupReminder()
        upgradeWalletKeyChains(Constants.BIP44_PATH, true)
    }

    private fun handleInvite(invite: InvitationLinkData) {
        val acceptInviteIntent = AcceptInviteActivity.createIntent(this, invite, false)
        startActivity(acceptInviteIntent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.hasExtra(EXTRA_RESET_BLOCKCHAIN)) {
            goBack(true)
            recreate()
            return
        }
        if (intent.hasExtra(EXTRA_INVITE)) {
            val invite = intent.extras!!.getParcelable<InvitationLinkData>(EXTRA_INVITE)!!
            if (!isLocked) {
                handleInvite(invite)
            } else {
                pendingInvite = invite
            }
        }
        val action = intent.action
        val extras = intent.extras
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val inputType = intent.type

            @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
            val ndefMessage = intent
                .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)!![0] as NdefMessage
            val input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage)
            object : InputParser.BinaryInputParser(inputType, input) {
                override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                    cannotClassify(inputType)
                }

                override fun error(x: Exception, messageResId: Int, vararg messageArgs: Any) {
                    baseAlertDialogBuilder.message = getString(messageResId, *messageArgs)
                    baseAlertDialogBuilder.neutralText = getString(R.string.button_dismiss)
                    alertDialog = baseAlertDialogBuilder.buildAlertDialog()
                    alertDialog.show()
                }
            }.parse()
        } else if (extras != null && extras.containsKey(WalletActivityExt.NOTIFICATION_ACTION_KEY)) {
            handleFirebaseAction(extras)
        }
    }

    private fun checkAlerts() {
        val packageInfo = packageInfoProvider.packageInfo

        if (CrashReporter.hasSavedCrashTrace()) {
            val stackTrace = StringBuilder()
            try {
                CrashReporter.appendSavedCrashTrace(stackTrace)
            } catch (x: IOException) {
                log.info("problem appending crash info", x)
            }
            alertDialog = object : ReportIssueDialogBuilder(this,
                    R.string.report_issue_dialog_title_crash, R.string.report_issue_dialog_message_crash) {

                override fun subject(): CharSequence {
                    return Constants.REPORT_SUBJECT_BEGIN + packageInfo.versionName + " " + Constants.REPORT_SUBJECT_CRASH
                }

                @Throws(IOException::class)
                override fun collectApplicationInfo(): CharSequence {
                    val applicationInfo = StringBuilder()
                    CrashReporter.appendApplicationInfo(
                        applicationInfo,
                        packageInfoProvider,
                        configuration,
                        walletData.wallet,
                        walletApplication.getSystemService(PowerManager::class.java)
                    )
                    return applicationInfo
                }

                @Throws(IOException::class)
                override fun collectStackTrace(): CharSequence? {
                    return if (stackTrace.isNotEmpty()) stackTrace else null
                }

                @Throws(IOException::class)
                override fun collectDeviceInfo(): CharSequence? {
                    val deviceInfo = StringBuilder()
                    CrashReporter.appendDeviceInfo(deviceInfo, this@MainActivity)
                    return deviceInfo
                }

                override fun collectWalletDump(): CharSequence? {
                    return walletData.wallet!!.toString(false, true, true, null)
                }
            }.buildAlertDialog()
            if (!isFinishing) {
                alertDialog.show()
            }
        }
    }

    // Normally OnboardingActivity will catch the non-encrypted wallets
    // However, if OnboardingActivity does not catch it, such as after a new wallet is created,
    // then we will catch it here.  This scenario was found during QA tests, but in a build that does
    // not encrypt the wallet.

    private fun checkWalletEncryptionDialog() {
        if (!walletApplication.wallet!!.isEncrypted) {
            log.info("the wallet is not encrypted")
            viewModel.logError(
                Exception("the wallet is not encrypted / OnboardingActivity"),
                "no other details are available without the user submitting a report"
            )
            val dialog = AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.wallet_encryption_error_title),
                getString(R.string.wallet_not_encrypted_error_message),
                getString(R.string.button_cancel),
                getString(R.string.button_ok)
            )
            dialog.isCancelable = false
            dialog.show(this) { reportIssue ->
                if (reportIssue != null) {
                    if (reportIssue) {
                        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                            this@MainActivity,
                            packageInfoProvider,
                            configuration,
                            walletData.wallet,
                            walletApplication
                        ).buildAlertDialog()
                        alertDialog.show()
                    } else {
                        // is there way to try to fix it?
                        // can we encrypt the wallet with the SecurityGuard.Password
                        // for now, lets close the app
                        this@MainActivity.finishAffinity()
                    }
                }
                Unit
            }
        }
    }

    fun handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(false, supportFragmentManager) { resetBlockchain() }
    }

    private fun resetBlockchain() {
        isRestoringBackup = false
        baseAlertDialogBuilder.title = getString(R.string.restore_wallet_dialog_success)
        baseAlertDialogBuilder.message = getString(R.string.restore_wallet_dialog_success_replay)
        baseAlertDialogBuilder.neutralText = getString(R.string.button_ok)
        baseAlertDialogBuilder.neutralAction = {
            walletApplication.resetBlockchain()
            finish()
        }
        alertDialog = baseAlertDialogBuilder.buildAlertDialog()
        alertDialog.show()
    }

    private fun checkRestoredWalletEncryptionDialog() {
        if (!walletApplication.wallet!!.isEncrypted) {
            handleEncryptKeysRestoredWallet()
        } else {
            resetBlockchain()
        }
    }

    open fun upgradeWalletKeyChains(path: ImmutableList<ChildNumber?>?, restoreBackup: Boolean) {
        val wallet = walletData.wallet!!
        isRestoringBackup = restoreBackup
        if (!wallet.hasKeyChain(path)) {
            if (wallet.isEncrypted()) {
                EncryptNewKeyChainDialogFragment.show(getSupportFragmentManager(), path)
            } else {
                //
                // Upgrade the wallet now
                //
                wallet.addKeyChain(path)
                walletApplication.saveWallet()
                //
                // Tell the user that the wallet is being upgraded (BIP44)
                // and they will have to enter a PIN.
                //
                UpgradeWalletDisclaimerDialog.show(getSupportFragmentManager(), false)
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog()
            } else checkWalletEncryptionDialog()
        }
    }

    private fun showBackupWalletDialogIfNeeded() {
        if (showBackupWalletDialog) {
            BackupWalletDialogFragment.show(this)
            showBackupWalletDialog = false
        }
    }

    private fun checkLowStorageAlert() {
        val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW))
        if (stickyIntent != null) {
            showLowStorageAlertDialog()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                goBack()
                return true
            }
            R.id.option_close -> {
                goBack()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        supportFragmentManager.fragments.forEach {
            it.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.platformRepo.onIdentityResolved = null
    }

    override fun onLockScreenDeactivated() {
        if (pendingInvite != null) {
            handleInvite(pendingInvite!!)
            pendingInvite = null // clear the invite
        } else if (config.showNotificationsExplainer) {
            explainPushNotifications()
        }
    }

    /**
     * Android 13 - Show system dialog to get notification permission from user, if not granted
     *              ask again with each app upgrade if not granted.  This logic is handled by
     *              {@link #onLockScreenDeactivated} and {@link #onStart}.
     * Android 12 and below - show a explainer dialog once only.
     */
    private fun explainPushNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (configuration.showNotificationsExplainer) {
            AdaptiveDialog.create(
                null,
                getString(R.string.notification_explainer_title),
                getString(R.string.notification_explainer_message),
                "",
                getString(R.string.button_okay)
            ).show(this)
        }
        config.showNotificationsExplainer = false
    }
}
