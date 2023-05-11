/*
 * Copyright 2015 the original author or authors.
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

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.BaseDialogFragment;
import org.dash.wallet.common.util.KeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.security.BiometricHelper;
import de.schildbach.wallet.security.BiometricLockoutException;
import de.schildbach.wallet.security.SecurityFunctions;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.payments.DecryptSeedTask;
import de.schildbach.wallet.payments.DeriveKeyTask;
import de.schildbach.wallet.ui.verify.VerifySeedActivity;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet.ui.widget.UpgradeWalletDisclaimerDialog;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class BackupWalletToSeedDialogFragment extends BaseDialogFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String FRAGMENT_TAG = BackupWalletToSeedDialogFragment.class.getName();
    private static final String ARGS_IS_UPGRADING = "is_upgrading";

    private FingerprintView fingerprintView;
    @Inject public BiometricHelper biometricHelper;
    @Inject SecurityFunctions securityFunctions;

    public static void show(final FragmentManager fm) {
        final BackupWalletToSeedDialogFragment newFragment = new BackupWalletToSeedDialogFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARGS_IS_UPGRADING, false);
        newFragment.setArguments(args);
        newFragment.show(fm, FRAGMENT_TAG);
    }

    public static void show(final FragmentManager fm, boolean isUpgrading) {
        final BackupWalletToSeedDialogFragment newFragment = new BackupWalletToSeedDialogFragment();

        Bundle args = new Bundle();
        args.putBoolean(ARGS_IS_UPGRADING, isUpgrading);
        newFragment.setArguments(args);
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private AppCompatActivity activity;
    private WalletApplication application;
    private Wallet wallet;
    private PinRetryController pinRetryController;
    private Configuration config;

    private TextView seedView;
    private View privateKeyPasswordViewGroup;
    private EditText privateKeyPasswordView;
    private TextView privateKeyBadPasswordView;
    private View seedViewGroup;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private CheckBox writtenDown;
    private Button showMnemonicSeedButton;

    private static final Logger log = LoggerFactory.getLogger(BackupWalletToSeedDialogFragment.class);

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AppCompatActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
        this.pinRetryController = PinRetryController.getInstance();
        this.config = application.getConfiguration();
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.backup_wallet_to_seed_dialog, null);

        seedView = (TextView) view.findViewById(R.id.backup_wallet_dialog_seed);
        privateKeyPasswordViewGroup = view.findViewById(R.id.backup_wallet_seed_private_key_password_group);
        privateKeyPasswordView = (EditText) view.findViewById(R.id.backup_wallet_seed_private_key_password);
        privateKeyBadPasswordView = (TextView) view.findViewById(R.id.backup_wallet_seed_private_key_bad_password);
        showMnemonicSeedButton = (Button) view.findViewById(R.id.backup_wallet_seed_private_key_enter);
        seedViewGroup = view.findViewById(R.id.backup_wallet_seed_group);
        writtenDown = (CheckBox)view.findViewById(R.id.backup_wallet_seed_private_key_written_down);

        fingerprintView = view.findViewById(R.id.fingerprint_view);
        privateKeyPasswordViewGroup.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE);

        privateKeyPasswordView.addTextChangedListener(privateKeyPasswordListener);
        showMnemonicSeedButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                handleDecryptPIN();
            }
        });
        if (wallet.isEncrypted()) {
            backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
            showPasswordViewGroup(true);
            updateView(false);
        } else {
            showMnemonicSeed(wallet.getActiveKeyChain().getSeed());
            updateView(true);
        }

        baseAlertDialogBuilder.setTitle(getString(R.string.export_keys_dialog_title));
        baseAlertDialogBuilder.setView(view);
        baseAlertDialogBuilder.setCancelable(false);
        baseAlertDialogBuilder.setPositiveText(getString(R.string.button_dismiss));
        baseAlertDialogBuilder.setPositiveAction(
                () -> {
                    if (writtenDown.isChecked()) {
                        config.disarmBackupSeedReminder();
                    }
                    privateKeyPasswordView.removeTextChangedListener(privateKeyPasswordListener);
                    if (getArguments().getBoolean(ARGS_IS_UPGRADING, false)
                            && activity instanceof UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener) {
                        ((UpgradeWalletDisclaimerDialog.OnUpgradeConfirmedListener) activity).onUpgradeConfirmed();
                    }
                    return Unit.INSTANCE;
                }
        );

        initFingerprintHelper();
        alertDialog = baseAlertDialogBuilder.buildAlertDialog();
        return super.onCreateDialog(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        config.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        if (activity instanceof DialogInterface.OnDismissListener) {
            ((DialogInterface.OnDismissListener) activity).onDismiss(getDialog());
        }
        super.onDismiss(dialog);
    }

    private final TextWatcher privateKeyPasswordListener = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            privateKeyBadPasswordView.setVisibility(View.GONE);
            showMnemonicSeedButton.setEnabled(true);
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private void initFingerprintHelper() {
        if (biometricHelper.isEnabled()) {
            fingerprintView.setVisibility(View.VISIBLE);
            biometricHelper.getPassword(requireActivity(), false, (savedPass, error) -> {
                if (error != null) {
                    fingerprintView.showError(error instanceof BiometricLockoutException);
                } else if (savedPass != null) {
                    privateKeyPasswordView.setText(savedPass);
                    handleDecryptPIN();
                }

                return Unit.INSTANCE;
            });
        }
    }

    private void showVerifySeedActivity(final DeterministicSeed seed) {
        List<String> mnemonicCode = seed.getMnemonicCode();
        String[] seedArr = new String[mnemonicCode.size()];
        seedArr = mnemonicCode.toArray(seedArr);
        Intent intent = VerifySeedActivity.Companion.createIntent(activity, seedArr, false);
        startActivity(intent);
    }

    private void showMnemonicSeed(final DeterministicSeed seed) {
        StringBuilder wordlist = new StringBuilder(255);

        List<String> code = seed.getMnemonicCode();


        for (String word : code) {
            wordlist.append(word + " ");
        }
        seedView.setText(wordlist);
    }

    private void handleDecryptPIN() {
        if (wallet.isEncrypted()) {

            if (pinRetryController.isLocked()) {
                return;
            }

            showMnemonicSeedButton.setEnabled(false);
            showMnemonicSeedButton.setText(getText(R.string.encrypt_keys_dialog_state_decrypting));
            privateKeyPasswordView.setEnabled(false);

            final String pin = privateKeyPasswordView.getText().toString().trim();

            new DeriveKeyTask(backgroundHandler, securityFunctions.getScryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    privateKeyBadPasswordView.setVisibility(View.GONE);
                    handleDecryptSeed(encryptionKey, pin);
                }
            }.deriveKey(wallet, pin);

        } else {

        }
    }

    private void handleDecryptSeed(final KeyParameter encryptionKey, final String pin) {
        if (wallet.isEncrypted()) {
            if (pinRetryController.isLocked()) {
                return;
            }
            showMnemonicSeedButton.setEnabled(false);
            new DecryptSeedTask(backgroundHandler) {
                @Override
                protected void onSuccess(final DeterministicSeed seed) {
                    pinRetryController.clearPinFailPrefs();
                    showVerifySeedActivity(seed);
                    dismiss();
                }

                protected void onBadPassphrase() {
                    pinRetryController.failedAttempt(pin);
                    privateKeyBadPasswordView.setVisibility(View.VISIBLE);
                    privateKeyBadPasswordView.setText(getString(R.string.wallet_lock_wrong_pin,
                            pinRetryController.getRemainingAttemptsMessage(getResources())));
                    privateKeyPasswordView.setEnabled(true);
                    privateKeyPasswordView.requestFocus();
                    showMnemonicSeedButton.setText(getText(R.string.backup_wallet_to_seed_show_recovery_phrase));
                    updateView(false);
                }
            }.decryptSeed(wallet.getActiveKeyChain().getSeed(), wallet.getKeyCrypter(), encryptionKey);

        } else {

        }
    }

    private void showPasswordViewGroup(boolean show) {
        if (show) {
            seedViewGroup.setVisibility(View.GONE);
            privateKeyPasswordView.post(() -> {
                if (isAdded()) {
                    KeyboardUtil.Companion.showSoftKeyboard(getActivity(), privateKeyPasswordView);
                }
            });
        } else {
            seedViewGroup.setVisibility(View.VISIBLE);
            privateKeyPasswordViewGroup.setVisibility(View.GONE);
            KeyboardUtil.Companion.hideKeyboard(getActivity(), privateKeyPasswordView);
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_REMIND_BACKUP_SEED.equals(key))
            updateView(false);
    }

    private void updateView(boolean isUnlocked) {
        if(!config.getRemindBackupSeed() || getArguments().getBoolean(ARGS_IS_UPGRADING, false))
            writtenDown.setVisibility(View.GONE);
        else writtenDown.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);
    }
}
