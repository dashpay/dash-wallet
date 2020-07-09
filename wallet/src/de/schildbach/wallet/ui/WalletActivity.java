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
import android.content.DialogInterface.OnClickListener;
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
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProviders;

import com.google.android.material.appbar.AppBarLayout;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.PrefixedChecksummedBytes;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.data.CurrencyInfo;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity;

import java.io.IOException;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.WalletBalanceWidgetProvider;
import de.schildbach.wallet.data.BlockchainIdentityBaseData;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.PaymentIntent;
import de.schildbach.wallet.livedata.Resource;
import de.schildbach.wallet.livedata.Status;
import de.schildbach.wallet.ui.InputParser.BinaryInputParser;
import de.schildbach.wallet.ui.InputParser.StringInputParser;
import de.schildbach.wallet.ui.dashpay.CreateIdentityService;
import de.schildbach.wallet.ui.dashpay.DashPayViewModel;
import de.schildbach.wallet.ui.scan.ScanActivity;
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity;
import de.schildbach.wallet.ui.send.SweepWalletActivity;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet.util.Nfc;
import de.schildbach.wallet_test.R;
import kotlin.Pair;
import okhttp3.HttpUrl;

/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractBindServiceActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback,
        UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener,
        EncryptNewKeyChainDialogFragment.OnNewKeyChainEncryptedListener {

    public static Intent createIntent(Context context) {
        return new Intent(context, WalletActivity.class);
    }

    private static final int DIALOG_BACKUP_WALLET_PERMISSION = 0;
    private static final int DIALOG_RESTORE_WALLET_PERMISSION = 1;
    private static final int DIALOG_RESTORE_WALLET = 2;
    private static final int DIALOG_TIMESKEW_ALERT = 3;
    private static final int DIALOG_VERSION_ALERT = 4;
    private static final int DIALOG_LOW_STORAGE_ALERT = 5;

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int REQUEST_CODE_BACKUP_WALLET = 1;
    private static final int REQUEST_CODE_RESTORE_WALLET = 2;

    private ClipboardManager clipboardManager;

    private de.schildbach.wallet.data.BlockchainState blockchainState;

    private boolean syncComplete = false;
    private View joinDashPayAction;
    private OnCoinsSentReceivedListener coinsSendReceivedListener = new OnCoinsSentReceivedListener();

    private DashPayViewModel dashPayViewModel;
    private boolean isPlatformAvailable = false;
    private boolean noIdentityCreatedOrInProgress = true;
    private boolean retryCreationIfInProgress = true;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //setContentViewFooter(R.layout.home_activity);
        setContentView(R.layout.home_content);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        //TODO: WalletFrag
        initView();

        this.clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        //TODO: <WalletFrag>
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
                showHideJoinDashPayAction();
            }
        });
        //TODO: </WalletFrag>
        registerOnCoinsSentReceivedListener();
        initViewModel();
    }

    //TODO: WalletFrag
    private void initView() {
        initQuickActions();
        findViewById(R.id.pay_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(PaymentsActivity.createIntent(WalletActivity.this, PaymentsActivity.ACTIVE_TAB_PAY));
            }
        });
        findViewById(R.id.receive_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(PaymentsActivity.createIntent(WalletActivity.this, PaymentsActivity.ACTIVE_TAB_RECEIVE));
            }
        });
    }

    //TODO: WalletFrag
    private void initQuickActions() {
        showHideSecureAction();
        findViewById(R.id.secure_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBackupWalletToSeed();
            }
        });
        joinDashPayAction = findViewById(R.id.join_dashpay_action);
        joinDashPayAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(WalletActivity.this, CreateUsernameActivity.class));
            }
        });
        showHideJoinDashPayAction();
        findViewById(R.id.scan_to_pay_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleScan(v);
            }
        });
        findViewById(R.id.buy_sell_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(UpholdAccountActivity.createIntent(this, wallet));
            }
        });
        findViewById(R.id.pay_to_address_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handlePaste();
            }
        });
        findViewById(R.id.import_key_action).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SweepWalletActivity.start(WalletActivity.this, true);
            }
        });
    }

    //TODO: WalletFrag
    private void initViewModel() {
        //
        // Currently this is only used to check the status of Platform before showing
        // the Join DashPay (evolution) button on the shortcuts bar.
        // If that is the only function that the platform required for, then we can
        // conditionally execute this code when a username hasn't been registered.
        dashPayViewModel = new ViewModelProvider(this).get(DashPayViewModel.class);
        dashPayViewModel.isPlatformAvailableLiveData().observe(this, new Observer<Resource<Boolean>>() {
            @Override
            public void onChanged(Resource<Boolean> status) {
                if (status.getStatus() == Status.SUCCESS)
                    isPlatformAvailable = status.getData();
                else isPlatformAvailable = false;
                showHideJoinDashPayAction();
            }
        });

        AppDatabase.getAppDatabase().blockchainIdentityDataDao().loadBase().observe(this, new Observer<BlockchainIdentityBaseData>() {
            @Override
            public void onChanged(BlockchainIdentityBaseData blockchainIdentityData) {
                if (blockchainIdentityData != null) {
                    noIdentityCreatedOrInProgress = (blockchainIdentityData.getCreationState() == BlockchainIdentityData.CreationState.NONE);
                    showHideJoinDashPayAction();
                    if (retryCreationIfInProgress && blockchainIdentityData.getCreationInProgress()) {
                        retryCreationIfInProgress = false;
                        startService(CreateIdentityService.createIntentForRetry(WalletActivity.this, false));
                    }
                }
            }
        });
    }

    //TODO: WalletFrag
    private void showHideSecureAction() {
        View secureActionView = findViewById(R.id.secure_action);
        secureActionView.setVisibility(config.getRemindBackupSeed() ? View.VISIBLE : View.GONE);
        findViewById(R.id.secure_action_space).setVisibility(secureActionView.getVisibility());
    }

    //TODO: WalletFrag
    private void showHideJoinDashPayAction() {
        if (noIdentityCreatedOrInProgress && syncComplete && isPlatformAvailable) {
            final Coin walletBalance = wallet.getBalance(Wallet.BalanceType.ESTIMATED);
            boolean canAffordIt = walletBalance.isGreaterThan(Constants.DASH_PAY_FEE)
                    || walletBalance.equals(Constants.DASH_PAY_FEE);
            boolean visible = canAffordIt && config.getShowJoinDashPay();
            joinDashPayAction.setVisibility(visible ? View.VISIBLE : View.GONE);
        } else {
            joinDashPayAction.setVisibility(View.GONE);
        }
        findViewById(R.id.join_dashpay_action_space).setVisibility(joinDashPayAction.getVisibility());
    }

    //TODO: WalletFrag
    @Override
    protected void onResume() {
        super.onResume();
        showHideSecureAction();
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
                dialog(WalletActivity.this, null, errorDialogTitleResId, messageResId, messageArgs);
            }

            @Override
            protected void cannotClassify(String input) {
                log.info("cannot classify: '{}'", input);
                error(null, cannotClassifyCustomMessageResId, input);
            }
        }.parse();
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
            InputParser.dialog(this, null, R.string.scan_to_pay_error_dialog_title, R.string.scan_to_pay_error_dialog_message_no_data);
        }
    }

    //TODO: WalletFrag
    private void updateSyncState() {
        if (blockchainState == null) {
            return;
        }

        int percentage = blockchainState.getPercentageSync();
        if (blockchainState.getReplaying() && blockchainState.getPercentageSync() == 100) {
            //This is to prevent showing 100% when using the Rescan blockchain function.
            //The first few broadcasted blockchainStates are with percentage sync at 100%
            percentage = 0;
        }

        ProgressBar syncProgressView = findViewById(R.id.sync_status_progress);
        if (blockchainState != null && blockchainState.syncFailed()) {
            updateSyncPaneVisibility(R.id.sync_status_pane, true);
            findViewById(R.id.sync_progress_pane).setVisibility(View.GONE);
            findViewById(R.id.sync_error_pane).setVisibility(View.VISIBLE);
            return;
        }

        updateSyncPaneVisibility(R.id.sync_error_pane, false);
        updateSyncPaneVisibility(R.id.sync_progress_pane, true);
        TextView syncStatusTitle = findViewById(R.id.sync_status_title);
        TextView syncStatusMessage = findViewById(R.id.sync_status_message);
        syncProgressView.setProgress(percentage);
        TextView syncPercentageView = findViewById(R.id.sync_status_percentage);
        syncPercentageView.setText(percentage + "%");


        syncComplete = (blockchainState.isSynced());
        if (syncComplete) {
            syncPercentageView.setTextColor(getResources().getColor(R.color.success_green));
            syncStatusTitle.setText(R.string.sync_status_sync_title);
            syncStatusMessage.setText(R.string.sync_status_sync_completed);
            updateSyncPaneVisibility(R.id.sync_status_pane, false);
        } else {
            syncPercentageView.setTextColor(getResources().getColor(R.color.dash_gray));
            updateSyncPaneVisibility(R.id.sync_status_pane, true);
            syncStatusTitle.setText(R.string.sync_status_syncing_title);
            syncStatusMessage.setText(R.string.sync_status_syncing_sub_title);
        }
    }

    //TODO: WalletFrag
    private void updateSyncPaneVisibility(int id, boolean visible) {
        findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    //TODO: WalletFrag
    @Override
    protected void onDestroy() {
        unregisterWalletListener();
        super.onDestroy();
    }

    //TODO: WalletFrag
    private void registerOnCoinsSentReceivedListener() {
        Executor mainThreadExecutor = ContextCompat.getMainExecutor(this);
        wallet.addCoinsReceivedEventListener(mainThreadExecutor, coinsSendReceivedListener);
        wallet.addCoinsSentEventListener(mainThreadExecutor, coinsSendReceivedListener);
    }

    //TODO: WalletFrag
    private void unregisterWalletListener() {
        wallet.removeCoinsReceivedEventListener(coinsSendReceivedListener);
        wallet.removeCoinsSentEventListener(coinsSendReceivedListener);
    }

    //TODO: WalletFrag
    private class OnCoinsSentReceivedListener implements WalletCoinsReceivedEventListener, WalletCoinsSentEventListener {

        @Override
        public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
            onCoinsSentReceived();
        }

        @Override
        public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
            onCoinsSentReceived();
        }

        private void onCoinsSentReceived() {
            showHideJoinDashPayAction();
        }
    }
}
