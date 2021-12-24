package de.schildbach.wallet.ui

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
import android.os.Handler
import android.os.LocaleList
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.observeOnce
import de.schildbach.wallet.ui.InputParser.BinaryInputParser
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment
import de.schildbach.wallet.ui.backup.RestoreFromFileHelper
import de.schildbach.wallet.ui.PaymentsFragment.Companion.ACTIVE_TAB_RECENT
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.dashpay.ContactsFragment.Companion.MODE_SEARCH_CONTACTS
import de.schildbach.wallet.ui.dashpay.ContactsFragment.Companion.MODE_SELECT_CONTACT
import de.schildbach.wallet.ui.dashpay.ContactsFragment.Companion.MODE_VIEW_REQUESTS
import de.schildbach.wallet.ui.invite.AcceptInviteActivity
import de.schildbach.wallet.ui.explore.ExploreTestNetFragment
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.invite.InviteSendContactRequestDialog
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.FingerprintHelper
import de.schildbach.wallet.util.Nfc
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.CurrencyInfo
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.DialogBuilder
import org.dash.wallet.common.ui.FancyAlertDialog
import java.io.IOException
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AbstractBindServiceActivity(), ActivityCompat.OnRequestPermissionsResultCallback,
        UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener,
        PaymentsPayFragment.OnSelectContactToPayListener, WalletFragment.OnSelectPaymentTabListener,
        ContactSearchResultsAdapter.OnViewAllRequestsListener {

    companion object {
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

    private val viewModel: MainActivityViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    @Inject
    lateinit var analytics: AnalyticsService

    private var isRestoringBackup = false
    private var showBackupWalletDialog = false
    private val config: Configuration by lazy { walletApplication.configuration }
    private var fingerprintHelper: FingerprintHelper? = null
    private var retryCreationIfInProgress = true
    private var pendingInvite: InvitationLinkData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        initFingerprintHelper()
        setupBottomNavigation()
    }

    private fun handleCreateFromInvite() {
        if (!config.hasBeenUsed() && config.onboardingInviteProcessing) {
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
        viewModel.blockchainIdentityData.observe(this) {
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
                    InviteHandler(this, analytics).showUsernameAlreadyDialog()
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

        viewModel.goBackAndStartActivityEvent.observe(this) {
            goBack(true)
            //Delay added to prevent fragment being removed and activity being launched "at the same time"
            Handler().postDelayed({
                startActivity(Intent(this, it))
            }, 500)
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
        viewModel.blockchainStateData.observe(this) {
            it?.apply {
                if (isSynced() && config.onboardingInviteProcessing) {
                    binding.restoringWalletCover.isVisible = false
                    handleOnboardingInvite(false)
                }
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
        viewModel.platformRepo.loadProfileByUserId(initInvitationUserId).observe(this@MainActivity) {
            val dialog = InviteSendContactRequestDialog.newInstance(this@MainActivity, it!!)
            dialog.show(supportFragmentManager, null)
        }
    }

    override fun onResume() {
        super.onResume()
        showBackupWalletDialogIfNeeded()
        checkLowStorageAlert()
        detectUserCountry()
        walletApplication.startBlockchainService(true)
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

    private fun setupBottomNavigation() {
        binding.bottomNavigation.itemIconTintList = null
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                binding.bottomNavigation.selectedItemId = R.id.home
            }
        }
        binding.bottomNavigation.setOnNavigationItemSelectedListener { item ->
            when (item.itemId) {
                binding.bottomNavigation.selectedItemId -> {
                    if (item.itemId == R.id.payments) {
                        goBack()
                    }
                }
                R.id.bottom_home -> goBack(true)
                R.id.contacts -> showContacts()
                R.id.payments -> showPayments()
                R.id.discover -> showExplore()
                R.id.more -> showMore()
            }
            true
        }
    }

    override fun onBackPressed() {
        if (!goBack()) {
            super.onBackPressed()
        }
    }

    private fun startFragmentTransaction(enterAnim: Int, exitAnim: Int): FragmentTransaction {
        val transaction = supportFragmentManager.beginTransaction()
        transaction.setCustomAnimations(enterAnim, R.anim.fragment_out, enterAnim, exitAnim)
        return transaction
    }

    private fun addFragment(fragment: Fragment, enterAnim: Int = R.anim.fragment_in,
                            exitAnim: Int = R.anim.fragment_out) {
        val transaction = startFragmentTransaction(enterAnim, exitAnim)
        transaction.add(R.id.fragment_container, fragment)
        transaction.addToBackStack(null).commit()
    }

    private fun replaceFragment(fragment: Fragment, enterAnim: Int = R.anim.fragment_in,
                                exitAnim: Int = R.anim.fragment_out) {
        val transaction = startFragmentTransaction(enterAnim, exitAnim)
        transaction.replace(R.id.fragment_container, fragment)
        transaction.addToBackStack(null).commit()
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

    private fun showContacts(mode: Int = MODE_SEARCH_CONTACTS) {
        if (viewModel.hasIdentity) {
            viewModel.blockchainIdentityData.observeOnce(this) {
                if (it?.creationState == BlockchainIdentityData.CreationState.DONE) {
                    viewModel.dismissUsernameCreatedCard()
                }
            }
            val contactsFragment = ContactsFragment.newInstance(mode)
            if (mode == MODE_VIEW_REQUESTS) {
                addFragment(contactsFragment)
            } else {
                replaceFragment(contactsFragment)
            }
        } else {
            replaceFragment(UpgradeToEvolutionFragment.newInstance())
        }
    }

    private fun showExplore() {
        val exploreFragment = ExploreTestNetFragment.newInstance()
        replaceFragment(exploreFragment)
    }

    private fun showPayments(activeTab: Int = ACTIVE_TAB_RECENT) {
        val paymentsFragment = PaymentsFragment.newInstance(activeTab)
        replaceFragment(paymentsFragment, R.anim.fragment_slide_up,
                R.anim.fragment_slide_down)
        analytics.logEvent(AnalyticsConstants.Home.SEND_RECEIVE_BUTTON, bundleOf())
    }

    private fun showMore() {
        val moreFragment = MoreFragment()
        replaceFragment(moreFragment)
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

    private fun createBackupWalletPermissionDialog(): Dialog? {
        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.backup_wallet_permission_dialog_title)
        dialog.setMessage(getString(R.string.backup_wallet_permission_dialog_message))
        dialog.singleDismissButton(null)
        return dialog.create()
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
        DialogBuilder.warn(this, R.string.wallet_low_storage_dialog_title)
            .setMessage(R.string.wallet_low_storage_dialog_msg)
            .setPositiveButton(R.string.wallet_low_storage_dialog_button_apps) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                finish()
            }
            .setNegativeButton(R.string.button_dismiss, null)
            .create()
            .show()
    }

    private fun createTimeskewAlertDialog(diffMinutes: Long): Dialog? {
        val pm = packageManager
        val settingsIntent = Intent(Settings.ACTION_DATE_SETTINGS)
        val dialog = DialogBuilder.warn(this, R.string.wallet_timeskew_dialog_title)
        dialog.setMessage(getString(R.string.wallet_timeskew_dialog_msg, diffMinutes))
        if (pm.resolveActivity(settingsIntent, 0) != null) {
            dialog.setPositiveButton(R.string.button_settings) { dialog, id ->
                startActivity(settingsIntent)
                finish()
            }
        }
        dialog.setNegativeButton(R.string.button_dismiss, null)
        return dialog.create()
    }

    private fun createVersionAlertDialog(): Dialog? {
        val pm = packageManager
        val marketIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(Constants.MARKET_APP_URL, packageName)))
        val binaryIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL))
        val dialog = DialogBuilder.warn(this, R.string.wallet_version_dialog_title)
        val message = java.lang.StringBuilder(getString(R.string.wallet_version_dialog_msg))
        if (Build.VERSION.SDK_INT < Constants.SDK_DEPRECATED_BELOW) message.append("\n\n").append(getString(R.string.wallet_version_dialog_msg_deprecated))
        dialog.setMessage(message)
        if (pm.resolveActivity(marketIntent, 0) != null) {
            dialog.setPositiveButton(R.string.wallet_version_dialog_button_market
            ) { dialog, id ->
                startActivity(marketIntent)
                finish()
            }
        }
        if (pm.resolveActivity(binaryIntent, 0) != null) {
            dialog.setNeutralButton(R.string.wallet_version_dialog_button_binary
            ) { dialog, id ->
                startActivity(binaryIntent)
                finish()
            }
        }
        dialog.setNegativeButton(R.string.button_dismiss, null)
        return dialog.create()
    }

    fun restoreWallet(wallet: Wallet?) {
        walletApplication.replaceWallet(wallet)
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        config.disarmBackupReminder()
        upgradeWalletKeyChains(Constants.BIP44_PATH, true)
        if (fingerprintHelper != null) {
            fingerprintHelper!!.clear()
        }
    }

    private fun enableFingerprint() {
        config.remindEnableFingerprint = true
        UnlockWalletDialogFragment.show(supportFragmentManager)
    }

    private fun handleInvite(invite: InvitationLinkData) {
        val acceptInviteIntent = AcceptInviteActivity.createIntent(this, invite, false)
        startActivity(acceptInviteIntent)
    }

    override fun onUnlocked() {
        if (pendingInvite != null) {
            handleInvite(pendingInvite!!)
            pendingInvite = null // clear the invite
        }
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
            object : BinaryInputParser(inputType, input) {
                override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                    cannotClassify(inputType)
                }

                override fun error(x: Exception, messageResId: Int, vararg messageArgs: Any) {
                    InputParser.dialog(this@MainActivity, null, 0, messageResId, *messageArgs)
                }
            }.parse()

        }
    }

    private fun checkAlerts() {
        val packageInfo = walletApplication.packageInfo()
        val versionNameSplit = packageInfo.versionName.indexOf('-')
        val url = HttpUrl
                .parse(Constants.VERSION_URL
                        .toString() + if (versionNameSplit >= 0) packageInfo.versionName.substring(versionNameSplit) else "")
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
            val dialog: ReportIssueDialogBuilder = object : ReportIssueDialogBuilder(this,
                    R.string.report_issue_dialog_title_crash, R.string.report_issue_dialog_message_crash) {

                override fun subject(): CharSequence {
                    return Constants.REPORT_SUBJECT_BEGIN + packageInfo.versionName + " " + Constants.REPORT_SUBJECT_CRASH
                }

                @Throws(IOException::class)
                override fun collectApplicationInfo(): CharSequence {
                    val applicationInfo = StringBuilder()
                    CrashReporter.appendApplicationInfo(applicationInfo, walletApplication)
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
                    return wallet.toString(false, true, true, null)
                }
            }
            if (!isFinishing) {
                dialog.show()
            }
        }
    }

    private fun checkWalletEncryptionDialog() {
        if (!wallet.isEncrypted) {
            EncryptKeysDialogFragment.show(false, supportFragmentManager)
        }
    }

    fun handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(false, supportFragmentManager) { resetBlockchain() }
    }

    private fun resetBlockchain() {
        isRestoringBackup = false
        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.restore_wallet_dialog_success)
        dialog.setMessage(getString(R.string.restore_wallet_dialog_success_replay))
        dialog.setNeutralButton(R.string.button_ok) { dialog, id ->
            walletApplication.resetBlockchain()
            finish()
        }
        dialog.show()
    }

    private fun checkRestoredWalletEncryptionDialog() {
        if (!wallet.isEncrypted) {
            handleEncryptKeysRestoredWallet()
        } else {
            resetBlockchain()
        }
    }

    open fun upgradeWalletKeyChains(path: ImmutableList<ChildNumber?>?, restoreBackup: Boolean): Unit {
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
                UpgradeWalletDisclaimerDialog.show(getSupportFragmentManager())
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog()
            } else checkWalletEncryptionDialog()
        }
    }

    private fun initFingerprintHelper() {
        //Init fingerprint helper
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = FingerprintHelper(this)
            if (!fingerprintHelper!!.init()) {
                fingerprintHelper = null
            }
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
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setMessage(getString(R.string.change_exchange_currency_code_message,
                newCurrencyCode, currentCurrencyCode))
        dialogBuilder.setPositiveButton(getString(R.string.change_to, newCurrencyCode)) { dialog, which ->
            config.exchangeCurrencyCodeDetected = true
            config.exchangeCurrencyCode = newCurrencyCode
            WalletBalanceWidgetProvider.updateWidgets(this@MainActivity, wallet)
        }
        dialogBuilder.setNegativeButton(getString(R.string.leave_as, currentCurrencyCode)) { dialog, which -> config.exchangeCurrencyCodeDetected = true }
        dialogBuilder.show()
    }

    private fun showBackupWalletDialogIfNeeded() {
        if (showBackupWalletDialog) {
            BackupWalletDialogFragment.show(supportFragmentManager)
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
            AbstractWalletActivity.log.info("Detecting currency based on device, mobile network or locale:")
            if (simCountry != null && simCountry.length == 2) { // SIM country code is available
                AbstractWalletActivity.log.info("Device Sim Country: $simCountry")
                updateCurrencyExchange(simCountry.toUpperCase())
            } else if (tm.phoneType != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                val networkCountry = tm.networkCountryIso
                AbstractWalletActivity.log.info("Network Country: $simCountry")
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
            AbstractWalletActivity.log.info("NMA-243:  Exception thrown obtaining Locale information: ", e)
            if (config.exchangeCurrencyCode == null) setDefaultCurrency()
        }
    }

    private fun setDefaultCurrency() {
        val countryCode: String? = getCurrentCountry()
        AbstractWalletActivity.log.info("Setting default currency:")
        if (countryCode != null) {
            try {
                AbstractWalletActivity.log.info("Local Country: $countryCode")
                val l = Locale("", countryCode)
                val currency = Currency.getInstance(l)
                var newCurrencyCode = currency.currencyCode
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    AbstractWalletActivity.log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }
                AbstractWalletActivity.log.info("Setting Local Currency: $newCurrencyCode")
                config.exchangeCurrencyCode = newCurrencyCode

                //Fallback to default
                if (config.exchangeCurrencyCode == null) {
                    setDefaultExchangeCurrencyCode()
                }
            } catch (x: IllegalArgumentException) {
                AbstractWalletActivity.log.info("Cannot obtain currency for $countryCode: ", x)
                setDefaultExchangeCurrencyCode()
            }
        } else {
            setDefaultExchangeCurrencyCode()
        }
    }

    private fun setDefaultExchangeCurrencyCode() {
        AbstractWalletActivity.log.info("Using default Country: US")
        AbstractWalletActivity.log.info("Using default currency: " + Constants.DEFAULT_EXCHANGE_CURRENCY)
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
        AbstractWalletActivity.log.info("Updating currency exchange rate based on country: $countryCode")
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
                    AbstractWalletActivity.log.info("found obsolete currency: $newCurrencyCode")
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode)
                }
                AbstractWalletActivity.log.info("Setting Local Currency: $newCurrencyCode")
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
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) showDialog(DIALOG_RESTORE_WALLET) else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_RESTORE_WALLET)
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

    override fun selectContactToPay() {
        showContacts(MODE_SELECT_CONTACT)
    }

    override fun onSelectPaymentTab(mode: Int) {
        showPayments(mode)
    }

    override fun onViewAllRequests() {
        showContacts(MODE_VIEW_REQUESTS)
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
}