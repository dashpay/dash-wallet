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
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet.ui.backup.RestoreFromFileHelper
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.invite.AcceptInviteActivity
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.invite.InviteSendContactRequestDialog
import de.schildbach.wallet.ui.main.WalletActivityExt.setupBottomNavigation
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.Nfc
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.CurrencyInfo
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

        const val REQUEST_CODE_SCAN = 0
        const val REQUEST_CODE_BACKUP_WALLET = 1
        const val REQUEST_CODE_RESTORE_WALLET = 2

        const val DIALOG_BACKUP_WALLET_PERMISSION = 0
        const val DIALOG_RESTORE_WALLET_PERMISSION = 1
        const val DIALOG_RESTORE_WALLET = 2
        const val DIALOG_TIMESKEW_ALERT = 3
        const val DIALOG_VERSION_ALERT = 4
        const val DIALOG_LOW_STORAGE_ALERT = 5

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
        showBackupWalletDialogIfNeeded()
        checkLowStorageAlert()
        checkWalletEncryptionDialog()
        detectUserCountry()
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
        //TODO: can we remove this?
    }

    override fun onCreateDialog(id: Int, args: Bundle): Dialog? {
        return if (id == DIALOG_BACKUP_WALLET_PERMISSION) {
            createBackupWalletPermissionDialog()
        } else if (id == DIALOG_RESTORE_WALLET_PERMISSION) {
            createRestoreWalletPermissionDialog()
        } else if (id == DIALOG_TIMESKEW_ALERT) {
            createTimeskewAlertDialog(args.getLong("diff_minutes"))
        } else if (id == DIALOG_VERSION_ALERT) {
            createVersionAlertDialog()
        } else throw java.lang.IllegalArgumentException()
    }

    private fun createBackupWalletPermissionDialog(): Dialog {
        baseAlertDialogBuilder.title = getString(R.string.backup_wallet_permission_dialog_title)
        baseAlertDialogBuilder.message = getString(R.string.backup_wallet_permission_dialog_message)
        baseAlertDialogBuilder.neutralText = getString(R.string.button_dismiss)
        return baseAlertDialogBuilder.buildAlertDialog()
    }

    private fun createRestoreWalletPermissionDialog(): Dialog? {
        return RestoreFromFileHelper.createRestoreWalletPermissionDialog(this)
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

    private fun createTimeskewAlertDialog(diffMinutes: Long): Dialog {
        val pm = packageManager
        val settingsIntent = Intent(Settings.ACTION_DATE_SETTINGS)

        baseAlertDialogBuilder.title = getString(R.string.wallet_timeskew_dialog_title)
        baseAlertDialogBuilder.message = getString(R.string.wallet_timeskew_dialog_msg, diffMinutes)
        if (pm.resolveActivity(settingsIntent, 0) != null) {
            baseAlertDialogBuilder.positiveText = getString(R.string.button_settings)
            baseAlertDialogBuilder.positiveAction = {
                startActivity(settingsIntent)
                finish()
            }
        }
        baseAlertDialogBuilder.negativeText = getString(R.string.button_dismiss)
        baseAlertDialogBuilder.showIcon = true
        return baseAlertDialogBuilder.buildAlertDialog()
    }

    private fun createVersionAlertDialog(): Dialog? {
        val pm = packageManager
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(
                String.format(
                    Constants.MARKET_APP_URL,
                    packageName
                )
            )
        )
        val binaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL))
        val message = java.lang.StringBuilder(getString(R.string.wallet_version_dialog_msg))
        if (Build.VERSION.SDK_INT < Constants.SDK_DEPRECATED_BELOW) message.append("\n\n")
            .append(getString(R.string.wallet_version_dialog_msg_deprecated))
        baseAlertDialogBuilder.title = getString(R.string.wallet_version_dialog_title)
        baseAlertDialogBuilder.message = message
        if (pm.resolveActivity(marketIntent, 0) != null) {
            baseAlertDialogBuilder.positiveText =
                getString(R.string.wallet_version_dialog_button_market)
            baseAlertDialogBuilder.positiveAction = {
                startActivity(marketIntent)
                finish()
            }
        }
        if (pm.resolveActivity(binaryIntent, 0) != null) {
            baseAlertDialogBuilder.neutralText =
                getString(R.string.wallet_version_dialog_button_binary)
            baseAlertDialogBuilder.neutralAction = {
                startActivity(binaryIntent)
                finish()
            }
        }
        baseAlertDialogBuilder.negativeText = getString(R.string.button_dismiss)
        baseAlertDialogBuilder.showIcon = true
        return baseAlertDialogBuilder.buildAlertDialog()
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

        }
    }

    private fun checkAlerts() {
        val packageInfo = packageInfoProvider.packageInfo
        val versionNameSplit = packageInfo.versionName.indexOf('-')
        val url = (Constants.VERSION_URL
            .toString() + if (versionNameSplit >= 0) packageInfo.versionName.substring(versionNameSplit) else "").toHttpUrlOrNull()
                ?.newBuilder()
        url?.addEncodedQueryParameter("package", packageInfo.packageName)
        url?.addQueryParameter("current", Integer.toString(packageInfo.versionCode))
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
                        walletData.wallet
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
                    return walletApplication.wallet!!.toString(false, true, true, null)
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
            val dialog = AdaptiveDialog.custom(
                R.layout.dialog_adaptive,
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
                            walletData.wallet
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
        val wallet = walletApplication.wallet!!
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

    /**
     * Show a Dialog and if user confirms it, set the default fiat currency exchange rate using
     * the country code to generate a Locale and get the currency code from it.
     *
     * @param newCurrencyCode currency code.
     */
    private fun showFiatCurrencyChangeDetectedDialog(currentCurrencyCode: String,
                                                     newCurrencyCode: String) {
        baseAlertDialogBuilder.message = getString(
            R.string.change_exchange_currency_code_message,
            newCurrencyCode, currentCurrencyCode
        )
        baseAlertDialogBuilder.positiveText = getString(R.string.change_to, newCurrencyCode)
        baseAlertDialogBuilder.positiveAction = {
            config.exchangeCurrencyCodeDetected = true
            config.exchangeCurrencyCode = newCurrencyCode
            WalletBalanceWidgetProvider.updateWidgets(this@MainActivity, walletApplication.wallet!!)
        }
        baseAlertDialogBuilder.negativeText = getString(R.string.leave_as, currentCurrencyCode)
        baseAlertDialogBuilder.negativeAction = {
            config.exchangeCurrencyCodeDetected = true
        }
        alertDialog = baseAlertDialogBuilder.buildAlertDialog()
        alertDialog.show()
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

    /**
     * Get ISO 3166-1 alpha-2 country code for this device (or null if not available)
     * If available, call [.showFiatCurrencyChangeDetectedDialog]
     * passing the country code.
     */
    private fun detectUserCountry() {
        if (config.exchangeCurrencyCodeDetected) {
            return
        }
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountry = tm.simCountryIso
            log.info("Detecting currency based on device, mobile network or locale:")
            if (simCountry != null && simCountry.length == 2) { // SIM country code is available
                log.info("Device Sim Country: $simCountry")
                updateCurrencyExchange(simCountry.toUpperCase())
            } else if (tm.phoneType != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                val networkCountry = tm.networkCountryIso
                log.info("Network Country: $simCountry")
                if (networkCountry != null && networkCountry.length == 2) { // network country code is available
                    updateCurrencyExchange(networkCountry.toUpperCase())
                } else {
                    //Couldn't obtain country code - Use Default
                    if (config.exchangeCurrencyCode == null) setDefaultCurrency()
                }
            } else {
                //No cellular network - Wifi Only
                if (config.exchangeCurrencyCode == null) setDefaultCurrency()
            }
        } catch (e: java.lang.Exception) {
            //fail safe
            log.info("NMA-243:  Exception thrown obtaining Locale information: ", e)
            if (config.exchangeCurrencyCode == null) setDefaultCurrency()
        }
    }

    private fun setDefaultCurrency() {
        val countryCode: String? = getCurrentCountry()
        log.info("Setting default currency:")
        if (countryCode != null) {
            try {
                log.info("Local Country: $countryCode")
                val l = Locale("", countryCode)
                val currency = Currency.getInstance(l)
                var newCurrencyCode = currency.currencyCode
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }

                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode);

                log.info("Setting Local Currency: $newCurrencyCode")
                config.exchangeCurrencyCode = newCurrencyCode

                //Fallback to default
                if (config.exchangeCurrencyCode == null) {
                    setDefaultExchangeCurrencyCode()
                }
            } catch (x: IllegalArgumentException) {
                log.info("Cannot obtain currency for $countryCode: ", x)
                setDefaultExchangeCurrencyCode()
            }
        } else {
            setDefaultExchangeCurrencyCode()
        }
    }

    private fun setDefaultExchangeCurrencyCode() {
        log.info("Using default Country: US")
        log.info("Using default currency: " + Constants.DEFAULT_EXCHANGE_CURRENCY)
        config.exchangeCurrencyCode = Constants.DEFAULT_EXCHANGE_CURRENCY
    }

    private fun getCurrentCountry(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault()[0].country
        } else {
            Locale.getDefault().country
        }
    }

    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     *
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private fun updateCurrencyExchange(countryCode: String) {
        log.info("Updating currency exchange rate based on country: $countryCode")
        val l = Locale("", countryCode)
        val currency = Currency.getInstance(l)
        var newCurrencyCode = currency.currencyCode
        var currentCurrencyCode = config.exchangeCurrencyCode
        if (currentCurrencyCode == null) {
            currentCurrencyCode = Constants.DEFAULT_EXCHANGE_CURRENCY
        }
        if (!currentCurrencyCode.equals(newCurrencyCode, ignoreCase = true)) {
            if (config.wasUpgraded()) {
                showFiatCurrencyChangeDetectedDialog(currentCurrencyCode, newCurrencyCode)
            } else {
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }

                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode);

                log.info("Setting Local Currency: $newCurrencyCode")
                config.exchangeCurrencyCodeDetected = true
                config.exchangeCurrencyCode = newCurrencyCode
            }
        }

        //Fallback to default
        if (config.exchangeCurrencyCode == null) {
            setDefaultExchangeCurrencyCode()
        }
    }

    fun handleRestoreWallet() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) showDialog(
            DIALOG_RESTORE_WALLET
        ) else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_RESTORE_WALLET
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_BACKUP_WALLET) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showBackupWalletDialog = true
            } else {
                showDialog(DIALOG_BACKUP_WALLET_PERMISSION)
            }
        } else if (requestCode == REQUEST_CODE_RESTORE_WALLET) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                handleRestoreWallet()
            } else showDialog(DIALOG_RESTORE_WALLET_PERMISSION)
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

    private fun explainPushNotifications() {
        AdaptiveDialog.create(
            null,
            getString(R.string.notification_explainer_title),
            getString(R.string.notification_explainer_message),
            "",
            getString(R.string.button_okay)
        ).show(this)
        config.showNotificationsExplainer = false
    }
}
