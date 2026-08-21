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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.NavigationRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.livedata.SeriousError
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation
import de.schildbach.wallet.service.platform.sdk.ShieldedInviteOverageTopUp
import de.schildbach.wallet.service.platform.work.ShieldedInviteOverageWorker
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.dashpay.*
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.invite.InviteSendContactRequestDialog
import de.schildbach.wallet.ui.staking.StakingActivity
import de.schildbach.wallet.ui.staking.createCrowdNodeWithdrawalReminderDialog
import de.schildbach.wallet.ui.main.MainActivityExt.checkLowStorageAlert
import de.schildbach.wallet.ui.cutover.CutoverSyncNoticeDialogFragment
import de.schildbach.wallet.ui.migration.MixedFundsMigrationDialogFragment
import de.schildbach.wallet.ui.main.MainActivityExt.checkTimeSkew
import de.schildbach.wallet.ui.main.MainActivityExt.handleFirebaseAction
import de.schildbach.wallet.ui.main.MainActivityExt.requestDisableBatteryOptimisation
import de.schildbach.wallet.ui.main.MainActivityExt.setupBottomNavigation
import de.schildbach.wallet.ui.main.MainActivityExt.showFiatCurrencyChangeDetectedDialog
import de.schildbach.wallet.ui.main.MainActivityExt.showStaleRatesToast
import de.schildbach.wallet.ui.more.ContactSupportDialogFragment
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog
import de.schildbach.wallet.util.CrashReporter
import de.schildbach.wallet.util.Nfc
import de.schildbach.wallet.util.StartupBreadcrumbs
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.wallet.DerivationPathFactory
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.components.ComposeHostFrameLayout
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.IllegalStateException
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AbstractBindServiceActivity(), ActivityCompat.OnRequestPermissionsResultCallback,
    UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
    EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener {
    companion object {
        private val log = LoggerFactory.getLogger(MainActivity::class.java)

        const val EXTRA_RESET_BLOCKCHAIN = "reset_blockchain"
        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_NAVIGATION_DESTINATION = "extra_destination"
        private const val EXTRA_NAVIGATION_ARGS = "extra_destination_args"

        fun createIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_NAVIGATION_DESTINATION, R.id.walletFragment)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        fun createIntent(context: Context, @NavigationRes destination: Int): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_NAVIGATION_DESTINATION, destination)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }

        /** [createIntent] variant passing [args] to the destination as its fragment arguments. */
        fun createIntent(context: Context, @NavigationRes destination: Int, args: Bundle): Intent {
            return createIntent(context, destination).putExtra(EXTRA_NAVIGATION_ARGS, args)
        }

        fun createIntent(context: Context, invite: InvitationLinkData): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_NAVIGATION_DESTINATION, R.id.walletFragment)
                putExtra(EXTRA_INVITE, invite)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val baseAlertDialogBuilder = BaseAlertDialogBuilder(this)
    val viewModel: MainViewModel by viewModels()
    private val inviteHandlerViewModel: InviteHandlerViewModel by viewModels()
    @Inject
    lateinit var config: Configuration
    @Inject
    lateinit var transparentUsernameCreation: SdkTransparentUsernameCreation
    @Inject
    lateinit var shieldedInviteOverageTopUp: ShieldedInviteOverageTopUp
    private lateinit var binding: ActivityMainBinding
    private var isRestoringBackup = false
    private var showBackupWalletDialog = false
    private var retryCreationIfInProgress = true
    private var pendingCrowdNodeWithdrawalReminder = false

    /**
     * The post-upgrade mixed-funds sheet fired while the lock screen was up;
     * shown as soon as it comes down (same deferral as the CrowdNode
     * reminder — a sheet rendered under the PIN screen is invisible).
     */
    private var pendingMixedFundsMigration = false

    /** Same lock-screen deferral for the one-time post-upgrade sync explainer. */
    private var pendingCutoverUpgradeNotice = false
    var composeHostFrameLayout: ComposeHostFrameLayout? = null

    val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestDisableBatteryOptimisation()
    }

    private val timeChangeReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIME_CHANGED) {
                // Time has changed, handle the change here
                log.info("Time or Time Zone changed")
                lifecycleScope.launch {
                    checkTimeSkew(viewModel, force = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        this.setupBottomNavigation(viewModel)

        // The UI is up — recorded immediately, as evidence in the support
        // report. The launch was already declared COMPLETE at the end of
        // Application.onCreate (see StartupBreadcrumbs.markLaunchComplete);
        // neither this marker nor the delayed one below decides that.
        StartupBreadcrumbs.markMainUiShown()
        // Belt and braces: after SURVIVAL_DELAY_MS on screen, re-clear the
        // crash-loop strike counters. Cheap, and it means a healthy session
        // leaves clean state behind even if the trail file is later lost.
        binding.root.postDelayed(
            { StartupBreadcrumbs.markLaunchSurvived() },
            StartupBreadcrumbs.SURVIVAL_DELAY_MS
        )

        initViewModel()

        if (savedInstanceState == null) {
            checkAlerts()
        }
        config.touchLastUsed()

        handleIntent(intent)

        // Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            // Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false)
            upgradeWalletCoinJoin(false)
        }

        // A shielded invite claim whose note exceeded the exit denomination
        // persists a pending OVERAGE record (the remainder must end up on the
        // claimed identity). The worker is normally enqueued right after the
        // claim, but if the app died in between, this launch check is what
        // resumes it — WorkManager KEEP makes the re-enqueue idempotent.
        lifecycleScope.launch {
            try {
                // Retro-fit first: a COMPLETED claim whose overage was never
                // recorded (the S22 gap) gets its record minted from persisted
                // state — one-shot, marker-guarded. Then the normal pending
                // check drains whatever record exists.
                val reconciled = shieldedInviteOverageTopUp.reconcileCompletedClaim()
                if (reconciled || shieldedInviteOverageTopUp.hasPending()) {
                    log.info(
                        "invite overage work found at launch (reconciled={}) — enqueueing its worker",
                        reconciled
                    )
                    ShieldedInviteOverageWorker.enqueue(application)
                }
            } catch (e: Exception) {
                log.warn("pending invite-overage check failed at launch", e)
            }
        }

        viewModel.currencyChangeDetected.observe(
            this
        ) { currencies: Pair<String?, String?> ->
            showFiatCurrencyChangeDetectedDialog(
                viewModel,
                currencies.component1()!!, currencies.component2()!!
            )
        }
        val timeChangedFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
        }
        registerReceiver(timeChangeReceiver, timeChangedFilter)

        viewModel.rateStale.observe(this) { state ->
            log.info("updateTrigger => rateStale: {}", state)
            showStaleRatesToast()
        }
    }

    override fun onStart() {
        super.onStart()

        if (!lockScreenDisplayed && config.showNotificationsExplainer) {
            explainPushNotifications()
        }
    }

    fun initViewModel() {
        viewModel.isAbleToCreateIdentity.observe(this) {
            // empty observer just to trigger data loading
            // viewModel is shared with some fragments keeping the observer active
            // inside the parent Activity will avoid recreation of relatively complex
            // isAbleToCreateIdentityData LiveData
        }
        viewModel.blockchainIdentity.observe(this) {
            if (it != null) {
                if (retryCreationIfInProgress && it.creationInProgress) {
                    retryCreationIfInProgress = false
                    val usingInvite = it.usingInvite
                    // POST-CUTOVER the SDK executor + RestoreIdentityWorker own
                    // resumption of an in-progress creation. This legacy dashj
                    // retry (CreateIdentityService) must NOT fire then: post-cutover
                    // it throws InsufficientMoney and stamps a spurious error on
                    // the home tile even though the SDK path is completing fine.
                    // Gate it on the COMMITTED cutover (the same signal the create
                    // routing uses). Pre-cutover this is unchanged — the dashj
                    // retry still fires. retryCreationIfInProgress is cleared above
                    // so this never lingers or re-fires.
                    lifecycleScope.launch {
                        val cutoverCommitted = try {
                            transparentUsernameCreation.isCutoverCommitted()
                        } catch (e: Exception) {
                            log.warn("cutover-state read failed on creation retry; assuming not committed", e)
                            false
                        }
                        if (cutoverCommitted) {
                            log.info(
                                "cutover committed — skipping legacy dashj creation retry; " +
                                    "the SDK executor + RestoreIdentityWorker own resumption"
                            )
                            return@launch
                        }
                        // should this be executed after syncing is finished?
                        if (usingInvite) {
                            startService(CreateIdentityService.createIntentForRetryFromInvite(this@MainActivity, false))
                        } else {
                            startService(CreateIdentityService.createIntentForRetry(this@MainActivity, false))
                        }
                    }
                }
                setupBottomNavigation(viewModel)
            }
        }

        viewModel.showCreateUsernameEvent.observe(this) {
            startActivity(Intent(this@MainActivity, CreateUsernameActivity::class.java))
        }
        viewModel.showCrowdNodeWithdrawalReminder.observe(this) {
            if (lockScreenDisplayed) {
                // Don't surface the reminder over the lock screen; defer until it's dismissed.
                pendingCrowdNodeWithdrawalReminder = true
            } else {
                presentCrowdNodeWithdrawalReminder()
            }
        }
        viewModel.showMixedFundsMigration.observe(this) {
            if (lockScreenDisplayed) {
                pendingMixedFundsMigration = true
            } else {
                MixedFundsMigrationDialogFragment.showOnce(this)
            }
        }
        viewModel.showCutoverUpgradeNotice.observe(this) {
            if (lockScreenDisplayed) {
                pendingCutoverUpgradeNotice = true
            } else {
                CutoverSyncNoticeDialogFragment.showOnce(this)
            }
        }

        viewModel.sendContactRequestState.observe(this) { workInfoMap ->
            config.inviter?.also { initInvitationUserId ->
                if (!config.inviterContactRequestSentInfoShown) {
                    workInfoMap[initInvitationUserId]?.apply {
                        if (status == Status.SUCCESS) {
                            log.info("showing successfully sent contact request dialog")
                            showInviteSendContactRequestDialog(initInvitationUserId)
                            config.inviterContactRequestSentInfoShown = true
                        }
                    }
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
                    val dialog = AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(R.string.serious_error_title),
                        getString(messageId),
                        getString(R.string.button_ok),
                        getString(R.string.button_cancel)
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
                dialog.show(this@MainActivity) {
                    // nothing
                }
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
        // The periodic contact-request poll is scoped to the blockchain service
        // and can stall across service teardown/restart; force a throttled
        // refresh here so returning to the home screen promptly surfaces new
        // contact requests and acceptances without opening the Contacts screen.
        viewModel.refreshContactsOnResume()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        // Keep getIntent() in step with what is actually being handled: the
        // activity replays getIntent() when it is recreated, so the extras
        // handleIntent consumes must be consumed from that same instance.
        setIntent(intent)
        handleIntent(intent)
    }

    // BIP44 Wallet Upgrade Dialog Dismissed (Ok button pressed)
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

    private fun handleInvite(invite: InvitationLinkData) {
        lifecycleScope.launch {
            inviteHandlerViewModel.setInvitationLink(invite, false)
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
            // Consume the invite so it is handled exactly once. Android
            // redelivers the launching intent every time the task is
            // recreated (relaunch from Recents, process death, config
            // change); without this, an invite the user already dealt with
            // is re-armed on each of those and re-runs validation.
            intent.removeExtra(EXTRA_INVITE)
            if (inviteHandlerViewModel.invitation.value == null) {
                handleInvite(invite)
            } else {
                // TODO: this is not the correct message, we are not onboarding
                InviteHandler(this, viewModel.analytics).showInviteWhileProcessingInviteInProgressDialog()
            }
        }
        if (intent.hasExtra(EXTRA_NAVIGATION_DESTINATION)) {
            try {
                val destination = intent.extras!!.getInt(EXTRA_NAVIGATION_DESTINATION)
                val navController = findNavController(R.id.nav_host_fragment)
                navController.navigate(destination, intent.getBundleExtra(EXTRA_NAVIGATION_ARGS))
            } catch (e: IllegalStateException) {
                // swallow for now, this happens when the MainActivity is first created?
            }
        }
        val action = intent.action
        val extras = intent.extras
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            val inputType = intent.type

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
        } else if (extras != null && extras.containsKey(MainActivityExt.NOTIFICATION_ACTION_KEY)) {
            handleFirebaseAction(extras)
        }
    }

    private fun checkAlerts() {
        if (CrashReporter.hasSavedCrashTrace()) {
            val stackTrace = StringBuilder()
            try {
                CrashReporter.appendSavedCrashTrace(stackTrace)
            } catch (x: IOException) {
                log.info("problem appending crash info", x)
            }
            val contactSupportDialog = ContactSupportDialogFragment.newInstance(
                getString(R.string.report_issue_dialog_title_crash),
                getString(R.string.report_issue_dialog_message_crash),
                stackTrace = stackTrace.toString(),
                isCrash = true
            )
            if (!isFinishing) {
                contactSupportDialog.show(this)
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
                if (reportIssue == true) {
                    ContactSupportDialogFragment.newInstance(
                        getString(R.string.report_issue_dialog_title_issue),
                        getString(R.string.report_issue_dialog_message_issue),
                        contextualData = getString(R.string.wallet_not_encrypted_error_message)
                    ).show(this)
                } else {
                    // is there way to try to fix it?
                    // can we encrypt the wallet with the SecurityGuard.Password
                    // for now, lets close the app
                    this@MainActivity.finishAffinity()
                }
            }
        }
    }

    private fun handleEncryptKeysRestoredWallet() {
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

    private fun upgradeWalletKeyChains(path: ImmutableList<ChildNumber>, restoreBackup: Boolean) {
        val wallet = walletData.wallet!!
        isRestoringBackup = restoreBackup
        if (!wallet.hasKeyChain(path)) {
            if (wallet.isEncrypted) {
                EncryptNewKeyChainDialogFragment.show(supportFragmentManager, path)
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
                UpgradeWalletDisclaimerDialog.show(supportFragmentManager, false)
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog()
            } else checkWalletEncryptionDialog()
        }
    }

    private fun upgradeWalletCoinJoin(restoreBackup: Boolean) {
        val wallet = walletData.wallet!!
        isRestoringBackup = restoreBackup
        val coinJoinPath = DerivationPathFactory(Constants.NETWORK_PARAMETERS).coinJoinDerivationPath(0)
        if ((wallet as WalletEx).coinJoin != null && !wallet.coinJoin.hasKeyChain(coinJoinPath)) {
            if (wallet.isEncrypted()) {
                viewModel.addCoinJoinToWallet()
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog()
            } else checkWalletEncryptionDialog()
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
        unregisterReceiver(timeChangeReceiver)
    }

    override fun onLockScreenDeactivated() {
        super.onLockScreenDeactivated()
        if (config.showNotificationsExplainer) {
            explainPushNotifications()
        }
        showStaleRatesToast()

        if (pendingCrowdNodeWithdrawalReminder) {
            pendingCrowdNodeWithdrawalReminder = false
            presentCrowdNodeWithdrawalReminder()
        }

        if (pendingCutoverUpgradeNotice) {
            pendingCutoverUpgradeNotice = false
            CutoverSyncNoticeDialogFragment.showOnce(this)
        }

        if (pendingMixedFundsMigration) {
            pendingMixedFundsMigration = false
            MixedFundsMigrationDialogFragment.showOnce(this)
        } else {
            // The lock screen may have torn the mixed-funds sheet down before
            // the user chose; if no choice has been recorded yet the ViewModel
            // re-fires the prompt (no-op otherwise).
            viewModel.recheckMixedFundsMigrationPrompt()
        }
    }

    private fun presentCrowdNodeWithdrawalReminder() {
        createCrowdNodeWithdrawalReminderDialog {
            startActivity(StakingActivity.createIntent(this, goToWithdraw = true))
        }.show(this)
    }

    override fun onLockScreenActivated() {
        showStaleRatesToast()
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
