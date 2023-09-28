/*
 * Copyright 2020 Dash Core Group.
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

package de.schildbach.wallet.ui.main;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.data.CurrencyInfo;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Currency;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.ui.AbstractBindServiceActivity;
import de.schildbach.wallet.ui.EncryptKeysDialogFragment;
import de.schildbach.wallet.ui.EncryptNewKeyChainDialogFragment;
import de.schildbach.wallet.ui.ReportIssueDialogBuilder;
import de.schildbach.wallet.ui.RestoreWalletFromSeedDialogFragment;
import de.schildbach.wallet.ui.util.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public final class WalletActivity extends AbstractBindServiceActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback,
        UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener {

    private static final Logger log = LoggerFactory.getLogger(WalletActivity.class);

    public static Intent createIntent(Context context) {
        return new Intent(context, WalletActivity.class);
    }

    private final Handler handler = new Handler();
    private boolean isRestoringBackup;

    private BaseAlertDialogBuilder baseAlertDialogBuilder;
    private MainViewModel viewModel;


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        baseAlertDialogBuilder = new BaseAlertDialogBuilder(this);
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        setContentView(R.layout.activity_main);
        WalletActivityExt.INSTANCE.setupBottomNavigation(this, viewModel);

        if (savedInstanceState == null) {
            checkAlerts();
        }

        configuration.touchLastUsed();

        handleIntent(getIntent());

        //Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            //Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false);
        }

        viewModel.getCurrencyChangeDetected().observe(this, currencies ->
            WalletActivityExt.INSTANCE.showFiatCurrencyChangeDetectedDialog(
            this,
                viewModel,
                currencies.component1(),
                currencies.component2()
            )
        );
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!getLockScreenDisplayed() && configuration.getShowNotificationsExplainer()) {
            explainPushNotifications();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        turnOnAutoLogout();
        WalletActivityExt.INSTANCE.checkTimeSkew(this, viewModel);
        WalletActivityExt.INSTANCE.checkLowStorageAlert(this);
        checkWalletEncryptionDialog();
        viewModel.detectUserCountry();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        final String action = intent.getAction();
        final Bundle extras = intent.getExtras();

        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            final String inputType = intent.getType();
            final NdefMessage ndefMessage = (NdefMessage) intent
                    .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
            final byte[] input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage);

            new BinaryInputParser(inputType, input) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    cannotClassify(inputType);
                }

                @Override
                protected void error(Exception x, final int messageResId, final Object... messageArgs) {
                    baseAlertDialogBuilder.setMessage(getString(messageResId, messageArgs));
                    baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
                    alertDialog = baseAlertDialogBuilder.buildAlertDialog();
                    alertDialog.show();
                }
            }.parse();
        } else if (extras != null && extras.containsKey(WalletActivityExt.NOTIFICATION_ACTION_KEY)) {
            WalletActivityExt.INSTANCE.handleFirebaseAction(this, extras);
        }
    }

    public void handleRestoreWalletFromSeed() {
        showRestoreWalletFromSeedDialog();
    }

    public void handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(false, getSupportFragmentManager(), dialog -> resetBlockchain());
    }

    private void showRestoreWalletFromSeedDialog() {
        RestoreWalletFromSeedDialogFragment.show(getSupportFragmentManager());
    }

    private void checkAlerts() {
        final PackageInfo packageInfo = packageInfoProvider.getPackageInfo();

        if (CrashReporter.hasSavedCrashTrace()) {
            final StringBuilder stackTrace = new StringBuilder();

            try {
                CrashReporter.appendSavedCrashTrace(stackTrace);
            } catch (final IOException x) {
                log.info("problem appending crash info", x);
            }

            final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(this,
                    R.string.report_issue_dialog_title_crash, R.string.report_issue_dialog_message_crash) {
                @Override
                protected CharSequence subject() {
                    return Constants.REPORT_SUBJECT_BEGIN + packageInfo.versionName + " " + Constants.REPORT_SUBJECT_CRASH;
                }

                @Override
                protected CharSequence collectApplicationInfo() throws IOException {
                    final StringBuilder applicationInfo = new StringBuilder();
                    CrashReporter.appendApplicationInfo(
                            applicationInfo,
                            packageInfoProvider,
                            configuration,
                            walletData.getWallet()
                    );
                    return applicationInfo;
                }

                @Override
                protected CharSequence collectStackTrace() {
                    if (stackTrace.length() > 0)
                        return stackTrace;
                    else
                        return null;
                }

                @Override
                protected CharSequence collectDeviceInfo() throws IOException {
                    final StringBuilder deviceInfo = new StringBuilder();
                    CrashReporter.appendDeviceInfo(deviceInfo, WalletActivity.this);
                    return deviceInfo;
                }

                @Override
                protected CharSequence collectWalletDump() {
                    return walletData.getWallet().toString(false, true, true, null);
                }
            };

            alertDialog = dialog.buildAlertDialog();
            alertDialog.show();
        }
    }

    public void restoreWallet(final Wallet wallet) {
        walletApplication.replaceWallet(wallet);
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();

        configuration.disarmBackupReminder();
        upgradeWalletKeyChains(Constants.BIP44_PATH, true);
    }

    private void resetBlockchain() {
        isRestoringBackup = false;
        baseAlertDialogBuilder.setTitle(getString(R.string.restore_wallet_dialog_success));
        baseAlertDialogBuilder.setMessage(getString(R.string.restore_wallet_dialog_success_replay));
        baseAlertDialogBuilder.setNeutralText(getString(R.string.button_ok));
        baseAlertDialogBuilder.setNeutralAction(neutralActionListener);
        alertDialog = baseAlertDialogBuilder.buildAlertDialog();
        alertDialog.show();
    }

    // Normally OnboardingActivity will catch the non-encrypted wallets
    // However, if OnboardingActivity does not catch it, such as after a new wallet is created,
    // then we will catch it here.  This scenario was found during QA tests, but in a build that does
    // not encrypt the wallet.

    private void checkWalletEncryptionDialog() {
        if (!walletData.getWallet().isEncrypted()) {
            log.info("the wallet is not encrypted");
            viewModel.logError(new Exception("the wallet is not encrypted / OnboardingActivity"),
                    "no other details are available without the user submitting a report");
            AdaptiveDialog dialog = AdaptiveDialog.create(
                    R.drawable.ic_error,
                    getString(R.string.wallet_encryption_error_title),
                    getString(R.string.wallet_not_encrypted_error_message),
                    getString(R.string.button_cancel),
                    getString(R.string.button_ok)
            );
            dialog.setCancelable(false);
            dialog.show(this, reportIssue -> {
                if (reportIssue != null) {
                    if (reportIssue) {
                        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
                            WalletActivity.this,
                            packageInfoProvider,
                            configuration,
                            walletData.getWallet()
                        ).buildAlertDialog();
                        alertDialog.show();
                    } else {
                        // is there way to try to fix it?
                        // can we encrypt the wallet with the SecurityGuard.Password
                        // for now, lets close the app
                        WalletActivity.this.finishAffinity();
                    }
                }
                return Unit.INSTANCE;
            });
        }
    }

    private void checkRestoredWalletEncryptionDialog() {
        if (!walletData.getWallet().isEncrypted()) {
            handleEncryptKeysRestoredWallet();
        } else {
            resetBlockchain();
        }
    }

    public void upgradeWalletKeyChains(final ImmutableList<ChildNumber> path, final boolean restoreBackup) {

        isRestoringBackup = restoreBackup;
        if (!walletData.getWallet().hasKeyChain(path)) {
            if (walletData.getWallet().isEncrypted()) {
                EncryptNewKeyChainDialogFragment.show(getSupportFragmentManager(), path);
            } else {
                //
                // Upgrade the wallet now
                //
                walletData.getWallet().addKeyChain(path);
                walletApplication.saveWallet();
                //
                // Tell the user that the wallet is being upgraded (BIP44)
                // and they will have to enter a PIN.
                //
                UpgradeWalletDisclaimerDialog.show(getSupportFragmentManager(), true);
            }
        } else {
            if (restoreBackup) {
                checkRestoredWalletEncryptionDialog();
            } else
                checkWalletEncryptionDialog();
        }
    }

    //BIP44 Wallet Upgrade Dialog Dismissed (Ok button pressed)
    @Override
    public void onUpgradeConfirmed() {
        if (isRestoringBackup) {
            checkRestoredWalletEncryptionDialog();
        } else {
            checkWalletEncryptionDialog();
        }
    }

    @Override
    public void onNewKeyChainEncrypted() {

    }

    private final Function0<Unit> neutralActionListener = () -> {
        getWalletApplication().resetBlockchain();
        finish();
        return Unit.INSTANCE;
    };

    @Override
    public void onLockScreenDeactivated() {
        if (configuration.getShowNotificationsExplainer()) {
            explainPushNotifications();
        }
    }

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // do nothing
                requestDisableBatteryOptimisation();
            });

    /**
     * Android 13 - Show system dialog to get notification permission from user, if not granted
     *              ask again with each app upgrade if not granted.  This logic is handled by
     *              {@link #onLockScreenDeactivated} and {@link #onStart}.
     * Android 12 and below - show a explainer dialog once only.
     */
    private void explainPushNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (configuration.getShowNotificationsExplainer()) {
            AdaptiveDialog dialog = AdaptiveDialog.create(
                    R.drawable.ic_info_blue,
                    getString(R.string.notification_explainer_title),
                    getString(R.string.notification_explainer_message),
                    "",
                    getString(R.string.button_okay)
            );

            dialog.show(this, result -> {
                requestDisableBatteryOptimisation();
                return Unit.INSTANCE;
            });
        }
        // only show either the permissions dialog (Android >= 13) or the explainer (Android <= 12) once
        configuration.setShowNotificationsExplainer(false);
    }

    private void requestDisableBatteryOptimisation() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (ContextCompat.checkSelfPermission(walletApplication, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == PackageManager.PERMISSION_GRANTED &&
                !powerManager.isIgnoringBatteryOptimizations(walletApplication.getPackageName())) {
            AdaptiveDialog.create(
                null,
                getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_title),
                getString(R.string.alert_dialogs_fragment_battery_optimization_dialog_message),
                getString(R.string.permission_deny),
                getString(R.string.permission_allow)
            ).show(this, (allow) -> {
                if (allow) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                }
                return Unit.INSTANCE;
            });
        }
    }
}
