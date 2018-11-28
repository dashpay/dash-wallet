/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionedChecksummedBytes;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.dash.wallet.common.ui.DialogBuilder;

import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity;
import org.dash.wallet.integration.uphold.ui.UpholdSplashActivity;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.squareup.okhttp.HttpUrl;

import org.dash.wallet.common.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.preference.PreferenceActivity;
import de.schildbach.wallet.ui.send.SendCoinsActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet.util.Io;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.RequiresApi;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractBindServiceActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback,
        NavigationView.OnNavigationItemSelectedListener,
        WalletLock.OnLockChangeListener, UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener,
        EnableFingerprintDialog.OnFingerprintEnabledListener {
    private static final int DIALOG_BACKUP_WALLET_PERMISSION = 0;
    private static final int DIALOG_RESTORE_WALLET_PERMISSION = 1;
    private static final int DIALOG_RESTORE_WALLET = 2;
    private static final int DIALOG_TIMESKEW_ALERT = 3;
    private static final int DIALOG_VERSION_ALERT = 4;
    private static final int DIALOG_LOW_STORAGE_ALERT = 5;

    private WalletApplication application;
    private Configuration config;
    private Wallet wallet;
    private FingerprintHelper fingerprintHelper;

    private DrawerLayout viewDrawer;
    private View viewFakeForSafetySubmenu;

    private Handler handler = new Handler();

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int REQUEST_CODE_BACKUP_WALLET = 1;
    private static final int REQUEST_CODE_RESTORE_WALLET = 2;

    private boolean isRestoringBackup;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        application = getWalletApplication();
        config = application.getConfiguration();
        wallet = application.getWallet();

        setContentView(R.layout.wallet_activity_onepane_vertical);

        if (savedInstanceState == null) {
            final View contentView = findViewById(android.R.id.content);
            final View slideInLeftView = contentView.findViewWithTag("slide_in_left");
            if (slideInLeftView != null)
                slideInLeftView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_left));
            final View slideInRightView = contentView.findViewWithTag("slide_in_right");
            if (slideInRightView != null)
                slideInRightView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
            final View slideInTopView = contentView.findViewWithTag("slide_in_top");
            if (slideInTopView != null)
                slideInTopView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_top));
            final View slideInBottomView = contentView.findViewWithTag("slide_in_bottom");
            if (slideInBottomView != null)
                slideInBottomView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom));

            checkAlerts();
        }

        config.touchLastUsed();

        handleIntent(getIntent());

        MaybeMaintenanceFragment.add(getFragmentManager());

        initUphold();
        initView();

        //Prevent showing dialog twice or more when activity is recreated (e.g: rotating device, etc)
        if (savedInstanceState == null) {
            //Add BIP44 support and PIN if missing
            upgradeWalletKeyChains(Constants.BIP44_PATH, false);
        }

        //Init fingerprint helper
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = new FingerprintHelper(this);
            if (!fingerprintHelper.init()) {
                fingerprintHelper = null;
            }
        }
    }

    private void initUphold() {
        //Uses Sha256 hash of excerpt of xpub as Uphold authentication salt
        String xpub = wallet.getWatchingKey().serializePubB58(Constants.NETWORK_PARAMETERS);
        byte[] xpubExcerptHash = Sha256Hash.hash(xpub.substring(4, 15).getBytes());
        String authenticationHash = Sha256Hash.wrap(xpubExcerptHash).toString();

        UpholdClient.init(getApplicationContext(), authenticationHash);
    }

    private void initView() {
        Toolbar toolbarView = initToolbar();
        initNavigationDrawer(toolbarView);
        initFloatingButton();
        findViewById(R.id.uphold_account_section).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpholdActivity();
                viewDrawer.closeDrawer(GravityCompat.START);
            }
        });
    }

    private Toolbar initToolbar() {
        Toolbar toolbarView = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbarView);
        setTitle("");
        return toolbarView;
    }

    private void initNavigationDrawer(Toolbar toolbarView) {
        final NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        viewDrawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, viewDrawer, toolbarView, R.string.navigation_drawer_open,
                R.string.navigation_drawer_close) {

            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                final Resources res = getResources();
                Menu menu = navigationView.getMenu();
                menu.findItem(R.id.nav_exchenge_rates).setEnabled(res.getBoolean(R.bool.show_exchange_rates_option));
            }
        };
        viewDrawer.addDrawerListener(toggle);
        toggle.syncState();

        viewFakeForSafetySubmenu = new View(this);
        viewFakeForSafetySubmenu.setVisibility(View.GONE);
        viewDrawer.addView(viewFakeForSafetySubmenu);
        registerForContextMenu(viewFakeForSafetySubmenu);
    }

    private void initFloatingButton() {
        FloatingActionButton fabScanQr = (FloatingActionButton) findViewById(R.id.fab_scan_qr);
        fabScanQr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleScan();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // delayed start so that UI has enough time to initialize
                getWalletApplication().startBlockchainService(true);
            }
        }, 1000);

        checkLowStorageAlert();
        detectUserCountry();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
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
                protected void error(final int messageResId, final Object... messageArgs) {
                    dialog(WalletActivity.this, null, 0, messageResId, messageArgs);
                }
            }.parse();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        if (requestCode == REQUEST_CODE_BACKUP_WALLET) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                handleBackupWallet();
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

            new StringInputParser(input) {
                @Override
                protected void handlePaymentIntent(final PaymentIntent paymentIntent) {
                    SendCoinsActivity.start(WalletActivity.this, paymentIntent);
                }

                @Override
                protected void handlePrivateKey(final VersionedChecksummedBytes key) {
                    SweepWalletActivity.start(WalletActivity.this, key);
                }

                @Override
                protected void handleDirectTransaction(final Transaction tx) throws VerificationException {
                    application.processDirectTransaction(tx);
                }

                @Override
                protected void error(final int messageResId, final Object... messageArgs) {
                    dialog(WalletActivity.this, null, R.string.button_scan, messageResId, messageArgs);
                }
            }.parse();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.wallet_options, menu);

        MenuItem walletLockMenuItem = menu.findItem(R.id.wallet_options_lock);
        walletLockMenuItem.setVisible(WalletLock.getInstance().isWalletLocked(wallet));

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

            case R.id.wallet_options_help:
                HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_wallet);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void handleRequestCoins() {
        startActivity(new Intent(this, RequestCoinsActivity.class));
    }

    public void handleSendCoins() {
        startActivity(new Intent(this, SendCoinsActivity.class));
    }

    public void handleScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    public void handleBackupWallet() {
        //Only allow to backup when wallet is unlocked
        final WalletLock walletLock = WalletLock.getInstance();
        if (WalletLock.getInstance().isWalletLocked(wallet)) {
            UnlockWalletDialogFragment.show(getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    if (!walletLock.isWalletLocked(wallet)) {
                        handleBackupWallet();
                    }
                }
            });
            return;
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            BackupWalletDialogFragment.show(getFragmentManager());
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
        //if (ContextCompat.checkSelfPermission(this,
        //        Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        BackupWalletToSeedDialogFragment.show(getFragmentManager());
        //else
        //    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
        //            REQUEST_CODE_BACKUP_WALLET);
    }

    public void handleRestoreWalletFromSeed() {
        showRestoreWalletFromSeedDialog();
    }

    public void handleEncryptKeys() {
        EncryptKeysDialogFragment.show(getSupportFragmentManager());
    }

    public void handleEncryptKeysRestoredWallet() {
        EncryptKeysDialogFragment.show(getSupportFragmentManager(), new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                resetBlockchain();
            }
        });
    }

    private void handleReportIssue() {
        final ReportIssueDialogBuilder dialog = new ReportIssueDialogBuilder(this,
                R.string.report_issue_dialog_title_issue, R.string.report_issue_dialog_message_issue) {
            @Override
            protected CharSequence subject() {
                return Constants.REPORT_SUBJECT_ISSUE + " " + application.packageInfo().versionName;
            }

            @Override
            protected CharSequence collectApplicationInfo() throws IOException {
                final StringBuilder applicationInfo = new StringBuilder();
                CrashReporter.appendApplicationInfo(applicationInfo, application);
                return applicationInfo;
            }

            @Override
            protected CharSequence collectDeviceInfo() throws IOException {
                final StringBuilder deviceInfo = new StringBuilder();
                CrashReporter.appendDeviceInfo(deviceInfo, WalletActivity.this);
                return deviceInfo;
            }

            @Override
            protected CharSequence collectWalletDump() {
                return application.getWallet().toString(false, true, true, null);
            }
        };
        dialog.show();
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
        else if (id == DIALOG_RESTORE_WALLET)
            return createRestoreWalletDialog();
        else if (id == DIALOG_TIMESKEW_ALERT)
            return createTimeskewAlertDialog(args.getLong("diff_minutes"));
        else if (id == DIALOG_VERSION_ALERT)
            return createVersionAlertDialog();
        else if (id == DIALOG_LOW_STORAGE_ALERT)
            return createLowStorageAlertDialog();
        else
            throw new IllegalArgumentException();
    }

    @Override
    protected void onPrepareDialog(final int id, final Dialog dialog) {
        if (id == DIALOG_RESTORE_WALLET)
            prepareRestoreWalletDialog(dialog);
    }

    private Dialog createBackupWalletPermissionDialog() {
        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.backup_wallet_permission_dialog_title);
        dialog.setMessage(getString(R.string.backup_wallet_permission_dialog_message));
        dialog.singleDismissButton(null);
        return dialog.create();
    }

    private Dialog createRestoreWalletPermissionDialog() {
        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.restore_wallet_permission_dialog_title);
        dialog.setMessage(getString(R.string.restore_wallet_permission_dialog_message));
        dialog.singleDismissButton(null);
        return dialog.create();
    }

    private Dialog createRestoreWalletDialog() {
        final View view = getLayoutInflater().inflate(R.layout.restore_wallet_dialog, null);
        final TextView messageView = (TextView) view.findViewById(R.id.restore_wallet_dialog_message);
        final Spinner fileView = (Spinner) view.findViewById(R.id.import_keys_from_storage_file);
        final EditText passwordView = (EditText) view.findViewById(R.id.import_keys_from_storage_password);

        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.import_keys_dialog_title);
        dialog.setView(view);
        dialog.setPositiveButton(R.string.import_keys_dialog_button_import, new OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                final File file = (File) fileView.getSelectedItem();
                final String password = passwordView.getText().toString().trim();
                passwordView.setText(null); // get rid of it asap

                if (WalletUtils.BACKUP_FILE_FILTER.accept(file))
                    restoreWalletFromProtobuf(file);
                else if (WalletUtils.KEYS_FILE_FILTER.accept(file))
                    restorePrivateKeysFromBase58(file);
                else if (Crypto.OPENSSL_FILE_FILTER.accept(file))
                    restoreWalletFromEncrypted(file, password);
            }
        });
        dialog.setNegativeButton(R.string.button_cancel, new OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                passwordView.setText(null); // get rid of it asap
            }
        });
        dialog.setOnCancelListener(new OnCancelListener() {
            @Override
            public void onCancel(final DialogInterface dialog) {
                passwordView.setText(null); // get rid of it asap
            }
        });

        final FileAdapter adapter = new FileAdapter(this) {
            @Override
            public View getDropDownView(final int position, View row, final ViewGroup parent) {
                final File file = getItem(position);
                final boolean isExternal = Constants.Files.EXTERNAL_WALLET_BACKUP_DIR.equals(file.getParentFile());
                final boolean isEncrypted = Crypto.OPENSSL_FILE_FILTER.accept(file);

                if (row == null)
                    row = inflater.inflate(R.layout.restore_wallet_file_row, null);

                final TextView filenameView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_filename);
                filenameView.setText(file.getName());

                final TextView securityView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_security);
                final String encryptedStr = context
                        .getString(isEncrypted ? R.string.import_keys_dialog_file_security_encrypted
                                : R.string.import_keys_dialog_file_security_unencrypted);
                final String storageStr = context
                        .getString(isExternal ? R.string.import_keys_dialog_file_security_external
                                : R.string.import_keys_dialog_file_security_internal);
                securityView.setText(encryptedStr + ", " + storageStr);

                final TextView createdView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_created);
                createdView.setText(context.getString(
                        isExternal ? R.string.import_keys_dialog_file_created_manual
                                : R.string.import_keys_dialog_file_created_automatic,
                        DateUtils.getRelativeTimeSpanString(context, file.lastModified(), true)));

                return row;
            }
        };

        final String path;
        final String backupPath = Constants.Files.EXTERNAL_WALLET_BACKUP_DIR.getAbsolutePath();
        final String storagePath = Constants.Files.EXTERNAL_STORAGE_DIR.getAbsolutePath();
        if (backupPath.startsWith(storagePath))
            path = backupPath.substring(storagePath.length());
        else
            path = backupPath;
        messageView.setText(getString(R.string.import_keys_dialog_message, path));

        fileView.setAdapter(adapter);

        return dialog.create();
    }

    private void prepareRestoreWalletDialog(final Dialog dialog) {
        final AlertDialog alertDialog = (AlertDialog) dialog;

        final List<File> files = new LinkedList<File>();

        // external storage
        final File[] externalFiles = Constants.Files.EXTERNAL_WALLET_BACKUP_DIR.listFiles();
        if (externalFiles != null)
            for (final File file : externalFiles)
                if (Crypto.OPENSSL_FILE_FILTER.accept(file))
                    files.add(file);

        // internal storage
        for (final String filename : fileList())
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.'))
                files.add(new File(getFilesDir(), filename));

        // sort
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(final File lhs, final File rhs) {
                return lhs.getName().compareToIgnoreCase(rhs.getName());
            }
        });

        final View replaceWarningView = alertDialog
                .findViewById(R.id.restore_wallet_from_storage_dialog_replace_warning);
        final boolean hasCoins = wallet.getBalance(BalanceType.ESTIMATED).signum() > 0;
        replaceWarningView.setVisibility(hasCoins ? View.VISIBLE : View.GONE);

        final Spinner fileView = (Spinner) alertDialog.findViewById(R.id.import_keys_from_storage_file);
        final FileAdapter adapter = (FileAdapter) fileView.getAdapter();
        adapter.setFiles(files);
        fileView.setEnabled(!adapter.isEmpty());

        final EditText passwordView = (EditText) alertDialog.findViewById(R.id.import_keys_from_storage_password);
        passwordView.setText(null);

        final ImportDialogButtonEnablerListener dialogButtonEnabler = new ImportDialogButtonEnablerListener(
                passwordView, alertDialog) {
            @Override
            protected boolean hasFile() {
                return fileView.getSelectedItem() != null;
            }

            @Override
            protected boolean needsPassword() {
                final File selectedFile = (File) fileView.getSelectedItem();
                return selectedFile != null ? Crypto.OPENSSL_FILE_FILTER.accept(selectedFile) : false;
            }
        };
        passwordView.addTextChangedListener(dialogButtonEnabler);
        fileView.setOnItemSelectedListener(dialogButtonEnabler);

        final CheckBox showView = (CheckBox) alertDialog.findViewById(R.id.import_keys_from_storage_show);
        showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView));
    }

    private void showRestoreWalletFromSeedDialog() {
        RestoreWalletFromSeedDialogFragment.show(getFragmentManager());
    }

    private void checkLowStorageAlert() {
        final Intent stickyIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        if (stickyIntent != null)
            showDialog(DIALOG_LOW_STORAGE_ALERT);
    }

    private Dialog createLowStorageAlertDialog() {
        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_low_storage_dialog_title);
        dialog.setMessage(R.string.wallet_low_storage_dialog_msg);
        dialog.setPositiveButton(R.string.wallet_low_storage_dialog_button_apps, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int id) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                finish();
                finish();
            }
        });
        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
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
                    return Constants.REPORT_SUBJECT_CRASH + " " + packageInfo.versionName;
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

            dialog.show();
        }
    }

    private Dialog createTimeskewAlertDialog(final long diffMinutes) {
        final PackageManager pm = getPackageManager();
        final Intent settingsIntent = new Intent(android.provider.Settings.ACTION_DATE_SETTINGS);

        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_timeskew_dialog_title);
        dialog.setMessage(getString(R.string.wallet_timeskew_dialog_msg, diffMinutes));

        if (pm.resolveActivity(settingsIntent, 0) != null) {
            dialog.setPositiveButton(R.string.button_settings, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    startActivity(settingsIntent);
                    finish();
                }
            });
        }

        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
    }

    private Dialog createVersionAlertDialog() {
        final PackageManager pm = getPackageManager();
        final Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse(String.format(Constants.MARKET_APP_URL, getPackageName())));
        final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL));

        final DialogBuilder dialog = DialogBuilder.warn(this, R.string.wallet_version_dialog_title);
        final StringBuilder message = new StringBuilder(getString(R.string.wallet_version_dialog_msg));
        if (Build.VERSION.SDK_INT < Constants.SDK_DEPRECATED_BELOW)
            message.append("\n\n").append(getString(R.string.wallet_version_dialog_msg_deprecated));
        dialog.setMessage(message);

        if (pm.resolveActivity(marketIntent, 0) != null) {
            dialog.setPositiveButton(R.string.wallet_version_dialog_button_market,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int id) {
                            startActivity(marketIntent);
                            finish();
                        }
                    });
        }

        if (pm.resolveActivity(binaryIntent, 0) != null) {
            dialog.setNeutralButton(R.string.wallet_version_dialog_button_binary,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int id) {
                            startActivity(binaryIntent);
                            finish();
                        }
                    });
        }

        dialog.setNegativeButton(R.string.button_dismiss, null);
        return dialog.create();
    }

    private void restoreWalletFromEncrypted(final File file, final String password) {
        try {
            final BufferedReader cipherIn = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), Charsets.UTF_8));
            final StringBuilder cipherText = new StringBuilder();
            Io.copy(cipherIn, cipherText, Constants.BACKUP_MAX_CHARS);
            cipherIn.close();

            final byte[] plainText = Crypto.decryptBytes(cipherText.toString(), password.toCharArray());
            final InputStream is = new ByteArrayInputStream(plainText);

            restoreWallet(WalletUtils.restoreWalletFromProtobufOrBase58(is, Constants.NETWORK_PARAMETERS));

            log.info("successfully restored encrypted wallet: {}", file);
        } catch (final IOException x) {
            final DialogBuilder dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title);
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.getMessage()));
            dialog.setPositiveButton(R.string.button_dismiss, null);
            dialog.setNegativeButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    showDialog(DIALOG_RESTORE_WALLET);
                }
            });
            dialog.show();

            log.info("problem restoring wallet: " + file, x);
        }
    }

    private void restoreWalletFromProtobuf(final File file) {
        FileInputStream is = null;
        try {
            is = new FileInputStream(file);
            restoreWallet(WalletUtils.restoreWalletFromProtobuf(is, Constants.NETWORK_PARAMETERS));

            log.info("successfully restored unencrypted wallet: {}", file);
        } catch (final IOException x) {
            final DialogBuilder dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title);
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.getMessage()));
            dialog.setPositiveButton(R.string.button_dismiss, null);
            dialog.setNegativeButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    showDialog(DIALOG_RESTORE_WALLET);
                }
            });
            dialog.show();

            log.info("problem restoring unencrypted wallet: " + file, x);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException x2) {
                    // swallow
                }
            }
        }
    }

    private void restorePrivateKeysFromBase58(final File file) {
        FileInputStream is = null;
        try {
            is = new FileInputStream(file);
            restoreWallet(WalletUtils.restorePrivateKeysFromBase58(is, Constants.NETWORK_PARAMETERS));
            //remind user to backup since the key backup file does not have an HD seed
            //Each time the user restores this backup file a new HD seed will be generated
            config.armBackupReminder();
            config.armBackupSeedReminder();
            log.info("successfully restored unencrypted private keys: {}", file);
        } catch (final IOException x) {
            final DialogBuilder dialog = DialogBuilder.warn(this, R.string.import_export_keys_dialog_failure_title);
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.getMessage()));
            dialog.setPositiveButton(R.string.button_dismiss, null);
            dialog.setNegativeButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    showDialog(DIALOG_RESTORE_WALLET);
                }
            });
            dialog.show();

            log.info("problem restoring private keys: " + file, x);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (final IOException x2) {
                    // swallow
                }
            }
        }
    }

    public void restoreWallet(final Wallet wallet) throws IOException {
        application.replaceWallet(wallet);
        getSharedPreferences(Constants.WALLET_LOCK_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();

        config.disarmBackupReminder();
        this.wallet = application.getWallet();
        upgradeWalletKeyChains(Constants.BIP44_PATH, true);
    }

    private void resetBlockchain() {
        isRestoringBackup = false;
        final DialogBuilder dialog = new DialogBuilder(this);
        dialog.setTitle(R.string.restore_wallet_dialog_success);
        dialog.setMessage(getString(R.string.restore_wallet_dialog_success_replay));
        dialog.setNeutralButton(R.string.button_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int id) {
                getWalletApplication().resetBlockchain();
                finish();
            }
        });
        dialog.show();
    }

    private void checkWalletEncryptionDialog() {
        if (!wallet.isEncrypted()) {
            handleEncryptKeys();
        }
    }

    private void checkRestoredWalletEncryptionDialog() {
        if (!wallet.isEncrypted()) {
            handleEncryptKeysRestoredWallet();
        }
        else {
            resetBlockchain();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        if (v == viewFakeForSafetySubmenu) {
            inflater.inflate(R.menu.wallet_safety_options, menu);

            final String externalStorageState = Environment.getExternalStorageState();

            menu.findItem(R.id.wallet_options_restore_wallet).setEnabled(
                    Environment.MEDIA_MOUNTED.equals(externalStorageState) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(externalStorageState));
            menu.findItem(R.id.wallet_options_backup_wallet).setEnabled(Environment.MEDIA_MOUNTED.equals(externalStorageState));
            menu.findItem(R.id.wallet_options_encrypt_keys).setTitle(
                    wallet.isEncrypted() ? R.string.wallet_options_encrypt_keys_change : R.string.wallet_options_encrypt_keys_set);

            boolean showFingerprintOption = fingerprintHelper != null && !fingerprintHelper.isFingerprintEnabled();
            menu.findItem(R.id.wallet_options_enable_fingerprint).setVisible(showFingerprintOption);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.wallet_options_safety:
                HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_safety);
                return true;

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
            SweepWalletActivity.start(this);
        } else if (id == R.id.nav_network_monitor) {
            startActivity(new Intent(this, NetworkMonitorActivity.class));
        } else if (id == R.id.nav_safety) {
            openContextMenu(viewFakeForSafetySubmenu);
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, PreferenceActivity.class));
        } else if (id == R.id.nav_disconnect) {
            handleDisconnect();
        } else if (id == R.id.nav_report_issue) {
            handleReportIssue();
        }

        viewDrawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (viewDrawer.isDrawerOpen(GravityCompat.START)) {
            viewDrawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    private void startUpholdActivity() {
        Intent intent;
        if (UpholdClient.getInstance().isAuthenticated()) {
            intent = new Intent(this, UpholdAccountActivity.class);
        } else {
            intent = new Intent(this, UpholdSplashActivity.class);
        }
        intent.putExtra(UpholdAccountActivity.WALLET_RECEIVING_ADDRESS_EXTRA, wallet.currentReceiveAddress().toString());
        startActivity(intent);
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
                UpgradeWalletDisclaimerDialog.show(getFragmentManager());
            }
        }
        else {
            if(restoreBackup) {
                checkRestoredWalletEncryptionDialog();
            }
            else
                checkWalletEncryptionDialog();
        }
    }

    //BIP44 Wallet Upgrade Dialog Dismissed (Ok button pressed)
    @Override
    public void onUpgradeConfirmed() {
        if(isRestoringBackup) {
            checkRestoredWalletEncryptionDialog();
        }
        else {
            checkWalletEncryptionDialog();
        }
    }

    @Override
    public void onNewKeyChainEncrypted() {
        BackupWalletToSeedDialogFragment.show(getFragmentManager(), true);
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
            if (simCountry != null && simCountry.length() == 2) { // SIM country code is available
                updateCurrencyExchange(simCountry.toUpperCase());
            } else if (tm.getPhoneType() != TelephonyManager.PHONE_TYPE_CDMA) { // device is not 3G (would be unreliable)
                String networkCountry = tm.getNetworkCountryIso();
                if (networkCountry != null && networkCountry.length() == 2) { // network country code is available
                    updateCurrencyExchange(networkCountry.toUpperCase());
                }
            }
        } catch (Exception e) {
            //fail safe
        }
    }

    /**
     * Check whether app was ever updated or if it is an installation that was never updated.
     * Show dialog to update if it's being updated or change it automatically.
     * @param countryCode countryCode ISO 3166-1 alpha-2 country code.
     */
    private void updateCurrencyExchange(String countryCode) {
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
                config.setExchangeCurrencyCodeDetected(true);
                config.setExchangeCurrencyCode(newCurrencyCode);
            }
        }
    }

    /**
     * Show a Dialog and if user confirms it, set the default fiat currency exchange rate using
     * the country code to generate a Locale and get the currency code from it.
     * @param newCurrencyCode currency code.
     */
    private void showFiatCurrencyChangeDetectedDialog(final String currentCurrencyCode,
                                                      final String newCurrencyCode) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        dialogBuilder.setMessage(getString(R.string.change_exchange_currency_code_message,
                newCurrencyCode, currentCurrencyCode));
        dialogBuilder.setPositiveButton(getString(R.string.change_to, newCurrencyCode), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                config.setExchangeCurrencyCodeDetected(true);
                config.setExchangeCurrencyCode(newCurrencyCode);
                WalletBalanceWidgetProvider.updateWidgets(WalletActivity.this, wallet);
            }
        });
        dialogBuilder.setNegativeButton(getString(R.string.leave_as, currentCurrencyCode), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                config.setExchangeCurrencyCodeDetected(true);
            }
        });
        dialogBuilder.show();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onFingerprintEnabled() {
        WalletTransactionsFragment walletTransactionsFragment = (WalletTransactionsFragment)
                getSupportFragmentManager().findFragmentById(R.id.wallet_transactions_fragment);
        if (walletTransactionsFragment != null) {
            walletTransactionsFragment.initFingerprintHelper();
            walletTransactionsFragment.onLockChanged(WalletLock.getInstance().isWalletLocked(wallet));
        }
    }

}
