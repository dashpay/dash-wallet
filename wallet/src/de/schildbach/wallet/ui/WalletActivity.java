/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.telephony.TelephonyManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProviders;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.navigation.NavigationView;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.HttpUrl;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.CurrencyInfo;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import java.io.IOException;
import java.util.Currency;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.backup.BackupWalletDialogFragment;
import de.schildbach.wallet.ui.backup.RestoreFromFileHelper;
import de.schildbach.wallet.ui.explore.ExploreActivity;
import de.schildbach.wallet.ui.preference.PreferenceActivity;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.ui.widget.ShortcutsPane;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import static org.dash.wallet.common.ui.BaseAlertDialogBuilderKt.formatString;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public final class WalletActivity extends AbstractBindServiceActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback,
        NavigationView.OnNavigationItemSelectedListener,
        UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener {

    private static final int DIALOG_BACKUP_WALLET_PERMISSION = 0;
    private static final int DIALOG_RESTORE_WALLET_PERMISSION = 1;
    private static final int DIALOG_RESTORE_WALLET = 2;
    private static final int DIALOG_TIMESKEW_ALERT = 3;
    private static final int DIALOG_VERSION_ALERT = 4;
    private static final int DIALOG_LOW_STORAGE_ALERT = 5;

    public static Intent createIntent(Context context) {
        return new Intent(context, WalletActivity.class);
    }

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;

    private ShortcutsPane shortcutsPane;

    private Handler handler = new Handler();

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int REQUEST_CODE_BACKUP_WALLET = 1;
    private static final int REQUEST_CODE_RESTORE_WALLET = 2;

    private boolean isRestoringBackup;

    private ClipboardManager clipboardManager;

    private boolean showBackupWalletDialog = false;
    private de.schildbach.wallet.data.BlockchainState blockchainState;

    private final FirebaseAnalyticsServiceImpl analytics =
            FirebaseAnalyticsServiceImpl.Companion.getInstance();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        application = getWalletApplication();
        config = application.getConfiguration();
        wallet = application.getWallet();

        setContentViewFooter(R.layout.home_activity);
        setSupportActionBar(findViewById(R.id.toolbar));
        activateHomeButton();

        if (savedInstanceState == null) {
            checkAlerts();
        }

        config.touchLastUsed();

        handleIntent(getIntent());

        initView();

        //Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            //Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false);
        }

        this.clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        View appBar = findViewById(R.id.app_bar);
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) appBar.getLayoutParams();
        if (params.getBehavior() == null) {
            params.setBehavior(new AppBarLayout.Behavior());
        }
        AppBarLayout.Behavior behaviour = (AppBarLayout.Behavior) params.getBehavior();
        behaviour.setDragCallback(new AppBarLayout.Behavior.DragCallback() {
            @Override
            public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
                WalletTransactionsFragment walletTransactionsFragment = (WalletTransactionsFragment)
                        getSupportFragmentManager().findFragmentById(R.id.wallet_transactions_fragment);
                return walletTransactionsFragment != null && !walletTransactionsFragment.isHistoryEmpty();
            }
        });

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(this, new Observer<de.schildbach.wallet.data.BlockchainState>() {
            @Override
            public void onChanged(de.schildbach.wallet.data.BlockchainState blockchainState) {
                WalletActivity.this.blockchainState = blockchainState;
                updateSyncState();
            }
        });

        RefreshUpdateShortcutsPaneViewModel model = new ViewModelProvider(this).get(RefreshUpdateShortcutsPaneViewModel.class);
        model.getOnTransactionsUpdated().observe(this, aVoid -> {
            refreshShortcutBar();
        });
    }

    private void initView() {
        initShortcutActions();
    }

    private void initShortcutActions() {
        shortcutsPane = findViewById(R.id.shortcuts_pane);
        shortcutsPane.setOnShortcutClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v == shortcutsPane.getSecureNowButton()) {
                    analytics.logEvent(AnalyticsConstants.Home.SHORTCUT_SECURE_WALLET, Bundle.EMPTY);
                    handleBackupWalletToSeed();
                } else if (v == shortcutsPane.getScanToPayButton()) {
                    analytics.logEvent(AnalyticsConstants.Home.SHORTCUT_SCAN_TO_PAY, Bundle.EMPTY);
                    handleScan(v);
                } else if (v == shortcutsPane.getBuySellButton()) {
                    analytics.logEvent(AnalyticsConstants.Home.SHORTCUT_BUY_AND_SELL, Bundle.EMPTY);
                    startUpholdActivity();
                } else if (v == shortcutsPane.getPayToAddressButton()) {
                    analytics.logEvent(AnalyticsConstants.Home.SHORTCUT_SEND_TO_ADDRESS, Bundle.EMPTY);
                    handlePaste();
                } else if (v == shortcutsPane.getReceiveButton()) {
                    analytics.logEvent(AnalyticsConstants.Home.SHORTCUT_RECEIVE, Bundle.EMPTY);
                    startActivity(PaymentsActivity.createIntent(WalletActivity.this, PaymentsActivity.ACTIVE_TAB_RECEIVE));
                } else if (v == shortcutsPane.getImportPrivateKey()) {
                    SweepWalletActivity.start(WalletActivity.this, true);
                }
                else if (v == shortcutsPane.getExplore()) {
                    startActivity(new Intent(WalletActivity.this, ExploreActivity.class));
                }
            }
        });
        refreshShortcutBar();
    }

    private void refreshShortcutBar() {
        showHideSecureAction();
        refreshIfUserHasBalance();
    }

    private void showHideSecureAction() {
        shortcutsPane.showSecureNow(config.getRemindBackupSeed());
    }

    private void refreshIfUserHasBalance() {
        Coin balance = wallet.getBalance(Wallet.BalanceType.ESTIMATED);
        shortcutsPane.userHasBalance(balance.value>0);
    }


    @Override
    protected void onResume() {
        super.onResume();

        checkLowStorageAlert();
        checkWalletEncryptionDialog();
        detectUserCountry();
        showBackupWalletDialogIfNeeded();
        showHideSecureAction();
    }

    private void showBackupWalletDialogIfNeeded() {
        if (showBackupWalletDialog) {
            BackupWalletDialogFragment.show(getSupportFragmentManager());
            showBackupWalletDialog = false;
        }
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
                    baseAlertDialogBuilder.setMessage(formatString(WalletActivity.this, messageResId, messageArgs));
                    baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
                    alertDialog = baseAlertDialogBuilder.buildAlertDialog();
                    alertDialog.show();
                }
            }.parse();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_BACKUP_WALLET) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                showBackupWalletDialog = true;
            else
                showDialog(DIALOG_BACKUP_WALLET_PERMISSION);
        } else if (requestCode == REQUEST_CODE_RESTORE_WALLET) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                handleRestoreWallet();
            else
                showDialog(DIALOG_RESTORE_WALLET_PERMISSION);
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            handleString(input, R.string.button_scan, R.string.input_parser_cannot_classify);
        } else {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    private void handleString(String input, final int errorDialogTitleResId, final int cannotClassifyCustomMessageResId) {
        new StringInputParser(input, true) {
            @Override
            protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                SendCoinsInternalActivity.start(WalletActivity.this, paymentIntent, true);
            }

            @Override
            protected void handlePrivateKey(final PrefixedChecksummedBytes key) {
                SweepWalletActivity.start(WalletActivity.this, key, true);
            }

            @Override
            protected void handleDirectTransaction(final Transaction tx) throws VerificationException {
                application.processDirectTransaction(tx);
            }

            @Override
            protected void error(Exception x, final int messageResId, final Object... messageArgs) {
                baseAlertDialogBuilder.setTitle(getString(errorDialogTitleResId));
                baseAlertDialogBuilder.setMessage(formatString(WalletActivity.this, messageResId, messageArgs));
                baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
            }

            @Override
            protected void cannotClassify(String input) {
                log.info("cannot classify: '{}'", input);
                error(null, cannotClassifyCustomMessageResId, input);
            }
        }.parse();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.wallet_options, menu);
        super.onCreateOptionsMenu(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.wallet_options_request:
                handleRequestCoins();
                return true;

            case R.id.wallet_options_send:
                handleSendCoins();
                return true;

			/*case R.id.wallet_options_scan:
                handleScan();
				return true;

			case R.id.wallet_options_address_book:
				AddressBookActivity.start(this);
				return true;

			case R.id.wallet_options_exchange_rates:
				startActivity(new Intent(this, ExchangeRatesActivity.class));
				return true;

			case R.id.wallet_options_sweep_wallet:
				SweepWalletActivity.start(this);
				return true;

			case R.id.wallet_options_network_monitor:
				startActivity(new Intent(this, NetworkMonitorActivity.class));
				return true;

			case R.id.wallet_options_restore_wallet:
				handleRestoreWallet();
				return true;

			case R.id.wallet_options_backup_wallet:
				handleBackupWallet();
				return true;

			case R.id.wallet_options_encrypt_keys:
				handleEncryptKeys();
				return true;

			case R.id.wallet_options_preferences:
				startActivity(new Intent(this, PreferenceActivity.class));
				return true;

			case R.id.wallet_options_safety:
				HelpDialogFragment.page(getFragmentManager(), R.string.help_safety);
				return true;
*/
            case R.id.wallet_options_report_issue:
                handleReportIssue();
                return true;

            case R.id.options_paste:
                handlePaste();
                return true;

        }

        return super.onOptionsItemSelected(item);
    }

    public void handleRequestCoins() {
        startActivity(new Intent(this, RequestCoinsActivity.class));
    }

    public void handleSendCoins() {
        SendCoinsInternalActivity.start(this, null, true);
    }

    public void handleScan(View clickView) {
        ScanActivity.startForResult(this, clickView, REQUEST_CODE_SCAN);
    }

    public void handleBackupWallet() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            BackupWalletDialogFragment.show(getSupportFragmentManager());
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_BACKUP_WALLET);
    }

    public void handleRestoreWallet() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            showDialog(DIALOG_RESTORE_WALLET);
        else
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_RESTORE_WALLET);
    }

    public void handleBackupWalletToSeed() {
        handleVerifySeed();
    }

    private void handleVerifySeed() {
        CheckPinSharedModel checkPinSharedModel = ViewModelProviders.of(this).get(CheckPinSharedModel.class);
        checkPinSharedModel.getOnCorrectPinCallback().observe(this, new Observer<Pair<Integer, String>>() {
            @Override
            public void onChanged(Pair<Integer, String> data) {
                startVerifySeedActivity(data.getSecond());
            }
        });
        CheckPinDialog.show(this, 0);
    }

    private void startVerifySeedActivity(String pin) {
        Intent intent = VerifySeedActivity.createIntent(this, pin);
        startActivity(intent);
    }

    public void handleRestoreWalletFromSeed() {
        showRestoreWalletFromSeedDialog();
    }

    public void handleEncryptKeys() {
        startActivity(SetPinActivity.createIntent(this, R.string.wallet_options_encrypt_keys_change, true));
    }

    public void handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(false, getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                resetBlockchain();
            }
        });
    }

    private void handleReportIssue() {
        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(this, application).buildAlertDialog();
        alertDialog.show();
    }

    private void handlePaste() {
        String input = null;
        if (clipboardManager.hasPrimaryClip()) {
            final ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null) {
                return;
            }
            final ClipDescription clipDescription = clip.getDescription();
            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                final Uri clipUri = clip.getItemAt(0).getUri();
                if (clipUri != null) {
                    input = clipUri.toString();
                }
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    || clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) {
                final CharSequence clipText = clip.getItemAt(0).getText();
                if (clipText != null) {
                    input = clipText.toString();
                }
            }
        }
        if (input != null) {
            handleString(input, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message);
        } else {
            baseAlertDialogBuilder.setTitle(getString(R.string.scan_to_pay_error_dialog_title));
            baseAlertDialogBuilder.setMessage(getString(R.string.scan_to_pay_error_dialog_message_no_data));
            baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
            alertDialog = baseAlertDialogBuilder.buildAlertDialog();
            alertDialog.show();
        }
    }

    private void enableFingerprint() {
        config.setRemindEnableFingerprint(true);
        UnlockWalletDialogFragment.show(getSupportFragmentManager());
    }

    @Override
    protected Dialog onCreateDialog(final int id, final Bundle args) {
        if (id == DIALOG_BACKUP_WALLET_PERMISSION)
            return createBackupWalletPermissionDialog();
        else if (id == DIALOG_RESTORE_WALLET_PERMISSION)
            return createRestoreWalletPermissionDialog();
        //else if (id == DIALOG_RESTORE_WALLET)
        //    return createRestoreWalletDialog();
        else if (id == DIALOG_TIMESKEW_ALERT)
            return createTimeskewAlertDialog(args.getLong("diff_minutes"));
        else if (id == DIALOG_VERSION_ALERT)
            return createVersionAlertDialog();
        else if (id == DIALOG_LOW_STORAGE_ALERT)
            return createLowStorageAlertDialog();
        else
            throw new IllegalArgumentException();
    }

    /*@Override
    protected void onPrepareDialog(final int id, final Dialog dialog) {
        if (id == DIALOG_RESTORE_WALLET)
            prepareRestoreWalletDialog(dialog);
    }*/

    private Dialog createBackupWalletPermissionDialog() {
        baseAlertDialogBuilder.setTitle(getString(R.string.backup_wallet_permission_dialog_title));
        baseAlertDialogBuilder.setMessage(getString(R.string.backup_wallet_permission_dialog_message));
        baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
        return baseAlertDialogBuilder.buildAlertDialog();
    }

    private Dialog createRestoreWalletPermissionDialog() {
        return RestoreFromFileHelper.createRestoreWalletPermissionDialog(this, this, this);
    }

    private void showRestoreWalletFromSeedDialog() {
        RestoreWalletFromSeedDialogFragment.show(getSupportFragmentManager());
    }

    private void checkLowStorageAlert() {
        final Intent stickyIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        if (stickyIntent != null)
            showDialog(DIALOG_LOW_STORAGE_ALERT);
    }

    private Dialog createLowStorageAlertDialog() {
        baseAlertDialogBuilder.setTitle(getString(R.string.wallet_low_storage_dialog_title));
        baseAlertDialogBuilder.setMessage(getString(R.string.wallet_low_storage_dialog_msg));
        baseAlertDialogBuilder.setPositiveText(getString(R.string.wallet_low_storage_dialog_button_apps));
        baseAlertDialogBuilder.setPositiveAction(
                () -> {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                    finish();
                    return Unit.INSTANCE;
                }
        );
        baseAlertDialogBuilder.setNegativeText(getString(R.string.button_dismiss));
        baseAlertDialogBuilder.setShowIcon(true);
        return baseAlertDialogBuilder.buildAlertDialog();
    }

    private void checkAlerts() {

        final PackageInfo packageInfo = getWalletApplication().packageInfo();
        final int versionNameSplit = packageInfo.versionName.indexOf('-');
        final HttpUrl.Builder url = HttpUrl
                .parse(Constants.VERSION_URL
                        + (versionNameSplit >= 0 ? packageInfo.versionName.substring(versionNameSplit) : ""))
                .newBuilder();
        url.addEncodedQueryParameter("package", packageInfo.packageName);
        url.addQueryParameter("current", Integer.toString(packageInfo.versionCode));

		/*new HttpGetThread(url.build(), application.httpUserAgent()) {
			@Override
			protected void handleLine(final String line, final long serverTime) {
				final int serverVersionCode = Integer.parseInt(line.split("\\s+")[0]);

				log.info("according to \"" + url + "\", strongly recommended minimum app version is "
						+ serverVersionCode);

				if (serverTime > 0) {
					final long diffMinutes = Math
							.abs((System.currentTimeMillis() - serverTime) / DateUtils.MINUTE_IN_MILLIS);

					if (diffMinutes >= 60) {
						log.info("according to \"" + url + "\", system clock is off by " + diffMinutes + " minutes");

						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								if (!isFinishing())
									return;
								final Bundle args = new Bundle();
								args.putLong("diff_minutes", diffMinutes);
								showDialog(DIALOG_TIMESKEW_ALERT, args);
							}
						});

						return;
					}
				}

				if (serverVersionCode > packageInfo.versionCode) {
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							if (isFinishing())
								return;
							showDialog(DIALOG_VERSION_ALERT);
						}
					});

					return;
				}
			}

			@Override
			protected void handleException(final Exception x) {
				if (x instanceof UnknownHostException || x instanceof SocketException
						|| x instanceof SocketTimeoutException) {
					// swallow
					log.debug("problem reading", x);
				} else {
					CrashReporter.saveBackgroundTrace(new RuntimeException(url.toString(), x), packageInfo);
				}
			}
		}.start();*/

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
                    CrashReporter.appendApplicationInfo(applicationInfo, application);
                    return applicationInfo;
                }

                @Override
                protected CharSequence collectStackTrace() throws IOException {
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
                    return wallet.toString(false, true, true, null);
                }
            };


            alertDialog = dialog.buildAlertDialog();
            alertDialog.show();
        }
    }

    private Dialog createTimeskewAlertDialog(final long diffMinutes) {
        final PackageManager pm = getPackageManager();
        final Intent settingsIntent = new Intent(android.provider.Settings.ACTION_DATE_SETTINGS);

        baseAlertDialogBuilder.setTitle(getString(R.string.wallet_timeskew_dialog_title));
        baseAlertDialogBuilder.setMessage(getString(R.string.wallet_timeskew_dialog_msg, diffMinutes));
        if (pm.resolveActivity(settingsIntent, 0) != null){
            baseAlertDialogBuilder.setPositiveText(getString(R.string.button_settings));
            baseAlertDialogBuilder.setPositiveAction(
                    () -> {
                        startActivity(settingsIntent);
                        finish();
                        return Unit.INSTANCE;
                    }
            );
        }
        baseAlertDialogBuilder.setNegativeText(getString(R.string.button_dismiss));
        baseAlertDialogBuilder.setShowIcon(true);
        return baseAlertDialogBuilder.buildAlertDialog();
    }

    private Dialog createVersionAlertDialog() {
        final PackageManager pm = getPackageManager();
        final Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(Constants.MARKET_APP_URL, getPackageName())));
        final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL));
        final StringBuilder message = new StringBuilder(getString(R.string.wallet_version_dialog_msg));
        if (Build.VERSION.SDK_INT < Constants.SDK_DEPRECATED_BELOW)
            message.append("\n\n").append(getString(R.string.wallet_version_dialog_msg_deprecated));

        baseAlertDialogBuilder.setTitle(getString(R.string.wallet_version_dialog_title));
        baseAlertDialogBuilder.setMessage(message);
        if (pm.resolveActivity(marketIntent, 0) != null){
            baseAlertDialogBuilder.setPositiveText(getString(R.string.wallet_version_dialog_button_market));
            baseAlertDialogBuilder.setPositiveAction(
                    () -> {
                        startActivity(marketIntent);
                        finish();
                        return Unit.INSTANCE;
                    }
            );
        }
        if (pm.resolveActivity(binaryIntent, 0) != null){
            baseAlertDialogBuilder.setNeutralText(getString(R.string.wallet_version_dialog_button_binary));
            baseAlertDialogBuilder.setNeutralAction(
                    () -> {
                        startActivity(binaryIntent);
                        finish();
                        return Unit.INSTANCE;
                    }
            );
        }
        baseAlertDialogBuilder.setNegativeText(getString(R.string.button_dismiss));
        baseAlertDialogBuilder.setShowIcon(true);
        return baseAlertDialogBuilder.buildAlertDialog();
    }

    public void restoreWallet(final Wallet wallet) {
        application.replaceWallet(wallet);
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();

        config.disarmBackupReminder();
        this.wallet = application.getWallet();
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
        if (!wallet.isEncrypted()) {
            log.info("the wallet is not encrypted");
            analytics.logError(new Exception("the wallet is not encrypted / OnboardingActivity"),
                    "no other details are available without the user submitting a report");
            AdaptiveDialog dialog = AdaptiveDialog.custom(R.layout.dialog_adaptive,
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
                        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(WalletActivity.this,
                                WalletApplication.getInstance()).buildAlertDialog();
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
        if (!wallet.isEncrypted()) {
            handleEncryptKeysRestoredWallet();
        } else {
            resetBlockchain();
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {

            case R.id.wallet_options_backup_wallet:
                handleBackupWallet();
                return true;

            case R.id.wallet_options_restore_wallet:
                handleRestoreWallet();
                return true;

            case R.id.wallet_options_encrypt_keys:
                handleEncryptKeys();
                return true;
            case R.id.wallet_options_backup_wallet_to_seed:
                handleBackupWalletToSeed();
                return true;

            case R.id.wallet_options_restore_wallet_from_seed:
                handleRestoreWalletFromSeed();
                return true;

            case R.id.wallet_options_enable_fingerprint:
                enableFingerprint();
                return true;
        }

        return super.onContextItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_home) {

        } else if (id == R.id.nav_address_book) {
            AddressBookActivity.start(this);
        } else if (id == R.id.nav_exchenge_rates) {
            startActivity(new Intent(this, ExchangeRatesActivity.class));
        } else if (id == R.id.nav_paper_wallet) {
            SweepWalletActivity.start(this, true);
        } else if (id == R.id.nav_network_monitor) {
            startActivity(new Intent(this, NetworkMonitorActivity.class));
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, PreferenceActivity.class));
        } else if (id == R.id.nav_disconnect) {
            handleDisconnect();
        } else if (id == R.id.nav_report_issue) {
            handleReportIssue();
        }
        return true;
    }

    private void startUpholdActivity() {
        startActivity(BuyAndSellIntegrationsActivity.Companion.createIntent(this));
    }

    //Dash Specific
    private void handleDisconnect() {
        getWalletApplication().stopBlockchainService();
        finish();
    }

    public void upgradeWalletKeyChains(final ImmutableList<ChildNumber> path, final boolean restoreBackup) {

        isRestoringBackup = restoreBackup;
        if (!wallet.hasKeyChain(path)) {
            if (wallet.isEncrypted()) {
                EncryptNewKeyChainDialogFragment.show(getSupportFragmentManager(), path);
            } else {
                //
                // Upgrade the wallet now
                //
                wallet.addKeyChain(path);
                application.saveWallet();
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

    private void updateSyncState() {
        if (blockchainState == null) {
            return;
        }

        if (blockchainState != null && blockchainState.syncFailed()) {
            findViewById(R.id.sync_error_pane).setVisibility(View.VISIBLE);
            return;
        }

        updateSyncPaneVisibility(R.id.sync_error_pane, false);
        if(blockchainState.isSynced()) {
            refreshShortcutBar();
        }
    }

    /**
     * Get ISO 3166-1 alpha-2 country code for this device (or null if not available)
     * If available, call {@link #showFiatCurrencyChangeDetectedDialog(String, String)}
     * passing the country code.
     */
    private void detectUserCountry() {
        if (config.getExchangeCurrencyCodeDetected()) {
            return;
        }
        try {
            final TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            final String simCountry = tm.getSimCountryIso();

            log.info("Detecting currency based on device, mobile network or locale:");
            if (simCountry != null && simCountry.length() == 2) { // SIM country code is available
                log.info("Device Sim Country: " + simCountry);
                updateCurrencyExchange(simCountry.toUpperCase());
            } else if (tm.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                String networkCountry = tm.getNetworkCountryIso();
                log.info("Network Country: " + simCountry);
                if (networkCountry != null && networkCountry.length() == 2) { // network country code is available
                    updateCurrencyExchange(networkCountry.toUpperCase());
                } else {
                    //Couldn't obtain country code - Use Default
                    if (config.getExchangeCurrencyCode() == null)
                        setDefaultCurrency();
                }
            } else {
                //No cellular network - Wifi Only
                if (config.getExchangeCurrencyCode() == null)
                    setDefaultCurrency();
            }
        } catch (Exception e) {
            //fail safe
            log.info("NMA-243:  Exception thrown obtaining Locale information: ", e);
            if (config.getExchangeCurrencyCode() == null)
                setDefaultCurrency();
        }
    }

    private void setDefaultCurrency() {
        String countryCode = getCurrentCountry();
        log.info("Setting default currency:");
        if (countryCode != null) {
            try {
                log.info("Local Country: " + countryCode);
                Locale l = new Locale("", countryCode);
                Currency currency = Currency.getInstance(l);
                String newCurrencyCode = currency.getCurrencyCode();
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: " + newCurrencyCode);
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode);
                }

                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode);

                log.info("Setting Local Currency: " + newCurrencyCode);
                config.setExchangeCurrencyCode(newCurrencyCode);

                //Fallback to default
                if (config.getExchangeCurrencyCode() == null) {
                    setDefaultExchangeCurrencyCode();
                }
            } catch (IllegalArgumentException x) {
                log.info("Cannot obtain currency for " + countryCode + ": ", x);
                setDefaultExchangeCurrencyCode();
            }

        } else {
            setDefaultExchangeCurrencyCode();
        }
    }

    private void setDefaultExchangeCurrencyCode() {
        log.info("Using default Country: US");
        log.info("Using default currency: " + Constants.DEFAULT_EXCHANGE_CURRENCY);
        config.setExchangeCurrencyCode(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String getCurrentCountry() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return LocaleList.getDefault().get(0).getCountry();
        } else {
            return Locale.getDefault().getCountry();
        }
    }

    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     *
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private void updateCurrencyExchange(String countryCode) {
        log.info("Updating currency exchange rate based on country: " + countryCode);
        Locale l = new Locale("", countryCode);
        Currency currency = Currency.getInstance(l);
        String newCurrencyCode = currency.getCurrencyCode();
        String currentCurrencyCode = config.getExchangeCurrencyCode();
        if (currentCurrencyCode == null) {
            currentCurrencyCode = Constants.DEFAULT_EXCHANGE_CURRENCY;
        }

        if (!currentCurrencyCode.equalsIgnoreCase(newCurrencyCode)) {
            if (config.wasUpgraded()) {
                showFiatCurrencyChangeDetectedDialog(currentCurrencyCode, newCurrencyCode);
            } else {
                if (CurrencyInfo.hasObsoleteCurrency(newCurrencyCode)) {
                    log.info("found obsolete currency: " + newCurrencyCode);
                    newCurrencyCode = CurrencyInfo.getUpdatedCurrency(newCurrencyCode);
                }
                // check to see if we use a different currency code for exchange rates
                newCurrencyCode = CurrencyInfo.getOtherName(newCurrencyCode);

                log.info("Setting Local Currency: " + newCurrencyCode);
                config.setExchangeCurrencyCodeDetected(true);
                config.setExchangeCurrencyCode(newCurrencyCode);
            }
        }

        //Fallback to default
        if (config.getExchangeCurrencyCode() == null) {
            setDefaultExchangeCurrencyCode();
        }
    }

    /**
     * Show a Dialog and if user confirms it, set the default fiat currency exchange rate using
     * the country code to generate a Locale and get the currency code from it.
     *
     * @param newCurrencyCode currency code.
     */
    private void showFiatCurrencyChangeDetectedDialog(final String currentCurrencyCode,
                                                      final String newCurrencyCode) {
        baseAlertDialogBuilder.setMessage(getString(R.string.change_exchange_currency_code_message,
                newCurrencyCode, currentCurrencyCode));
        baseAlertDialogBuilder.setPositiveText(getString(R.string.change_to, newCurrencyCode));
        baseAlertDialogBuilder.setPositiveAction(
                () -> {
                    config.setExchangeCurrencyCodeDetected(true);
                    config.setExchangeCurrencyCode(newCurrencyCode);
                    WalletBalanceWidgetProvider.updateWidgets(WalletActivity.this, wallet);
                    return Unit.INSTANCE;
                }
        );
        baseAlertDialogBuilder.setNegativeText(getString(R.string.leave_as, currentCurrencyCode));
        baseAlertDialogBuilder.setNegativeAction(
                () -> {
                    config.setExchangeCurrencyCodeDetected(true);
                    return Unit.INSTANCE;
                }
        );
        alertDialog = baseAlertDialogBuilder.buildAlertDialog();
        alertDialog.show();
    }

    private void updateSyncPaneVisibility(int id, boolean visible) {
        findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private final Function0<Unit> neutralActionListener = () -> {
        getWalletApplication().resetBlockchain();
        finish();
        return Unit.INSTANCE;
    };
}
