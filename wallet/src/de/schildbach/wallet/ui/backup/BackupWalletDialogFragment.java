/*
 * Copyright the original author or authors.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.backup;

import static androidx.core.util.Preconditions.checkNotNull;
import static androidx.core.util.Preconditions.checkState;
import static org.dash.wallet.common.ui.BaseAlertDialogBuilderKt.formatString;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.io.CharStreams;

import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.bouncycastle.crypto.params.KeyParameter;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.WalletDataProvider;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.common.ui.BaseDialogFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.payments.DeriveKeyTask;
import de.schildbach.wallet.security.SecurityFunctions;
import de.schildbach.wallet.security.SecurityGuard;
import de.schildbach.wallet.ui.ShowPasswordCheckListener;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.Iso8601Format;
import de.schildbach.wallet.util.Toast;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * @author Andreas Schildbach
 * @author Eric Britten
 */

@AndroidEntryPoint
public class BackupWalletDialogFragment extends DialogFragment {
    private static final String FRAGMENT_TAG = BackupWalletDialogFragment.class.getName();

    public static void show(final FragmentActivity activity) {
        final BackupWalletDialogFragment newFragment = new BackupWalletDialogFragment();
        Log.e("BackupWalletDialogFragm", "Dialog shown");
        newFragment.show(activity.getSupportFragmentManager(), FRAGMENT_TAG);
    }

    @Inject SecurityFunctions securityFunctions;
    @Inject WalletDataProvider walletData;
    @Inject Configuration config;

    private AlertDialog alertDialog;
    private EditText passwordView, passwordAgainView;
    private TextView passwordStrengthView;
    private View passwordMismatchView;
    private CheckBox showView;
    private TextView warningView;
    private Button positiveButton;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private BackupWalletViewModel viewModel;

    private static final int REQUEST_CODE_CREATE_DOCUMENT = 0;

    private static final Logger log = LoggerFactory.getLogger(BackupWalletDialogFragment.class);

    private final TextWatcher textWatcher = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            viewModel.password.postValue(s.toString().trim());
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log.info("opening dialog {}", getClass().getName());
        viewModel = new ViewModelProvider(this).get(BackupWalletViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = getLayoutInflater().inflate(R.layout.backup_wallet_dialog, null);

        passwordView = view.findViewById(R.id.backup_wallet_dialog_password);
        passwordView.setText(null);

        passwordAgainView = view.findViewById(R.id.backup_wallet_dialog_password_again);
        passwordAgainView.setText(null);

        passwordStrengthView = view.findViewById(R.id.backup_wallet_dialog_password_strength);

        passwordMismatchView = view.findViewById(R.id.backup_wallet_dialog_password_mismatch);

        showView = view.findViewById(R.id.backup_wallet_dialog_show);

        warningView = view.findViewById(R.id.backup_wallet_dialog_warning_encrypted);

        final BaseAlertDialogBuilder alertDialogBuilder = new BaseAlertDialogBuilder(requireContext());
        alertDialogBuilder.setTitle(getString(R.string.export_keys_dialog_title));
        alertDialogBuilder.setView(view);
        alertDialogBuilder.setPositiveText(getString(R.string.export_keys_dialog_button_export));
        alertDialogBuilder.setNegativeText(getString(R.string.button_cancel));
        alertDialogBuilder.setPositiveAction(
                () -> {
                    handleGo();
                    return Unit.INSTANCE;
                }
        );
        alertDialogBuilder.setNegativeAction(
                () -> {
                    dismissAllowingStateLoss();
                    return Unit.INSTANCE;
                }
        );
        alertDialogBuilder.setCancelableOnTouchOutside(false);

        alertDialog = alertDialogBuilder.buildAlertDialog();
        alertDialog.setOnShowListener(d -> {
            positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);
            positiveButton.setTypeface(Typeface.DEFAULT_BOLD);

            passwordView.addTextChangedListener(textWatcher);
            passwordAgainView.addTextChangedListener(textWatcher);

            showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView, passwordAgainView));

            //walletActivityViewModel.wallet.observe(BackupWalletDialogFragment.this,
            //        wallet -> warningView.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE));
            warningView.setVisibility(walletData.getWallet().isEncrypted() ? View.VISIBLE : View.GONE);
            viewModel.password.observe(BackupWalletDialogFragment.this, password -> {
                passwordMismatchView.setVisibility(View.INVISIBLE);

                final int passwordLength = password.length();
                passwordStrengthView.setVisibility(passwordLength > 0 ? View.VISIBLE : View.INVISIBLE);
                if (passwordLength < 6) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_weak);
                    passwordStrengthView
                            .setTextColor(getResources().getColor(R.color.system_red));
                } else if (passwordLength < 8) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_fair);
                    passwordStrengthView
                            .setTextColor(getResources().getColor(R.color.tangerine));
                } else if (passwordLength < 10) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_good);
                    passwordStrengthView
                            .setTextColor(getResources().getColor(R.color.content_tertiary));
                } else {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_strong);
                    passwordStrengthView.setTextColor(
                            getResources().getColor(R.color.system_green));
                }

                if (positiveButton != null) {
                    final Wallet wallet = walletData.getWallet();
                    final boolean hasPassword = !password.isEmpty();
                    final boolean hasPasswordAgain = !passwordAgainView.getText().toString().trim().isEmpty();
                    positiveButton.setEnabled(wallet != null && hasPassword && hasPasswordAgain);
                }
            });
        });

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        return alertDialog;
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        passwordView.removeTextChangedListener(textWatcher);
        passwordAgainView.removeTextChangedListener(textWatcher);

        showView.setOnCheckedChangeListener(null);
        alertDialog.dismiss();

        wipePasswords();
        super.onDismiss(dialog);
    }

    private void handleGo() {
        final String password = passwordView.getText().toString().trim();
        final String passwordAgain = passwordAgainView.getText().toString().trim();

        if (passwordAgain.equals(password)) {
            backupWallet();
        } else {
            passwordMismatchView.setVisibility(View.VISIBLE);
        }
    }

    private void wipePasswords() {
        passwordView.setText(null);
        passwordAgainView.setText(null);
    }

    private void backupWallet() {
        passwordView.setEnabled(false);
        passwordAgainView.setEnabled(false);

        final DateFormat dateFormat = new Iso8601Format("yyyy-MM-dd-HH-mm");
        dateFormat.setTimeZone(TimeZone.getDefault());

        final StringBuilder filename = new StringBuilder(Constants.Files.EXTERNAL_WALLET_BACKUP);
        filename.append('-');
        filename.append(dateFormat.format(new Date()));

        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(Constants.MIMETYPE_WALLET_BACKUP);
        intent.putExtra(Intent.EXTRA_TITLE, filename.toString());
        try {
            startActivityForResult(intent, REQUEST_CODE_CREATE_DOCUMENT);
        } catch (final ActivityNotFoundException x) {
            log.warn("Cannot open document selector: {}", intent);
            new Toast(requireContext()).longToast(R.string.toast_start_storage_provider_selector_failed);
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_CREATE_DOCUMENT) {
            if (resultCode == Activity.RESULT_OK) {
                Wallet wallet = walletData.getWallet();
                //walletActivityViewModel.wallet.observe(this, new Observer<Wallet>() {
                //    @Override
                //    public void onChanged(final Wallet wallet) {
                //        walletActivityViewModel.wallet.removeObserver(this);


                final Uri targetUri = checkNotNull(intent.getData());
                final String targetProvider = WalletUtils.uriToProvider(targetUri);
                final String password = passwordView.getText().toString().trim();
                checkState(!password.isEmpty());
                wipePasswords();
                dismiss();

                final Protos.Wallet walletProto = new WalletProtobufSerializer().walletToProto(wallet);

                try {
                    SecurityGuard securityGuard = new SecurityGuard();
                    if (wallet.isEncrypted()) {
                        String walletPassword = securityGuard.retrievePassword();
                        final Wallet decryptedWallet = new WalletProtobufSerializer().readWallet(Constants.NETWORK_PARAMETERS, application.getWalletExtensions(), walletProto);
                        new DeriveKeyTask(backgroundHandler, securityFunctions.getScryptIterationsTarget()) {
                            @Override
                            protected void onSuccess(KeyParameter encryptionKey, boolean changed) {
                                decryptedWallet.decrypt(encryptionKey);
                                backupWallet(decryptedWallet, targetUri, targetProvider, password);
                            }

                            @Override
                            protected void onFailure(KeyCrypterException ex) {
                                super.onFailure(ex);
                                log.error("problem backing up wallet to " + targetUri, ex);
                                ErrorDialogFragment.showDialog(requireActivity().getSupportFragmentManager(), ex.toString());
                            }
                        }.deriveKey(decryptedWallet, walletPassword);
                    } else {
                        backupWallet(wallet, targetUri, targetProvider, password);
                    }
                } catch (GeneralSecurityException | IOException | UnreadableWalletException ex) {
                    log.error("problem backing up wallet to " + targetUri, ex);
                    ErrorDialogFragment.showDialog(requireActivity().getSupportFragmentManager(), ex.toString());
                }


                //    }
                //});
            } else if (resultCode == Activity.RESULT_CANCELED) {
                log.info("cancelled backing up wallet");
                passwordView.setEnabled(true);
                passwordAgainView.setEnabled(true);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    private void backupWallet(Wallet wallet, Uri targetUri, String targetProvider, String password) {
        byte[] plainBytes = null;
        try (final Writer cipherOut = new OutputStreamWriter(
                requireContext().getContentResolver().openOutputStream(targetUri), StandardCharsets.UTF_8)) {
            final Protos.Wallet walletProto = new WalletProtobufSerializer().walletToProto(wallet);
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            walletProto.writeTo(baos);
            baos.close();
            plainBytes = baos.toByteArray();

            final String cipherText = Crypto.encrypt(plainBytes, password.toCharArray());
            cipherOut.write(cipherText);
            cipherOut.flush();

            log.info("backed up wallet to: '{}'{}, {} characters written", targetUri,
                    targetProvider != null ? " (" + targetProvider + ")" : "", cipherText.length());
        } catch (final IOException x) {
            log.error("problem backing up wallet to " + targetUri, x);
            ErrorDialogFragment.showDialog(requireActivity().getSupportFragmentManager(), x.toString());
            return;
        }

        try (final Reader cipherIn = new InputStreamReader(
                requireContext().getContentResolver().openInputStream(targetUri), StandardCharsets.UTF_8)) {
            final StringBuilder cipherText = new StringBuilder();
            CharStreams.copy(cipherIn, cipherText);
            cipherIn.close();

            final byte[] plainBytes2 = Crypto.decryptBytes(cipherText.toString(),
                    password.toCharArray());
            if (!Arrays.equals(plainBytes, plainBytes2))
                throw new IOException("verification failed");

            log.info("verified successfully: '" + targetUri + "'");
            config.disarmBackupReminder();
            SuccessDialogFragment.showDialog(requireActivity().getSupportFragmentManager(),
                    targetProvider != null ? targetProvider : targetUri.toString());
        } catch (final IOException x) {
            log.error("problem verifying backup from " + targetUri, x);
            ErrorDialogFragment.showDialog(requireActivity().getSupportFragmentManager(), x.toString());
        }
    }

    @AndroidEntryPoint
    public static class SuccessDialogFragment extends BaseDialogFragment {
        private static final String FRAGMENT_TAG = SuccessDialogFragment.class.getName();
        private static final String KEY_TARGET = "target";
        private Activity activity;

        public static void showDialog(final FragmentManager fm, final String target) {
            final DialogFragment newFragment = new SuccessDialogFragment();
            final Bundle args = new Bundle();
            args.putString(KEY_TARGET, target);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public void onAttach(final Context context) {
            super.onAttach(context);
            this.activity = (Activity) context;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final String target = getArguments().getString(KEY_TARGET);
            baseAlertDialogBuilder.setTitle(getString(R.string.export_keys_dialog_title));
            baseAlertDialogBuilder.setMessage(Html.fromHtml(getString(R.string.export_keys_dialog_success, target)));
            baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
            baseAlertDialogBuilder.setNeutralAction(
                    () -> {
                        activity.finish();
                        return Unit.INSTANCE;
                    }
            );
            alertDialog = baseAlertDialogBuilder.buildAlertDialog();
            return super.onCreateDialog(savedInstanceState);
        }
    }

    @AndroidEntryPoint
    public static class ErrorDialogFragment extends BaseDialogFragment {
        private static final String FRAGMENT_TAG = ErrorDialogFragment.class.getName();
        private static final String KEY_EXCEPTION_MESSAGE = "exception_message";
        private Activity activity;

        public static void showDialog(final FragmentManager fm, final String exceptionMessage) {
            final DialogFragment newFragment = new ErrorDialogFragment();
            final Bundle args = new Bundle();
            args.putString(KEY_EXCEPTION_MESSAGE, exceptionMessage);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public void onAttach(final Context context) {
            super.onAttach(context);
            this.activity = (Activity) context;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final String exceptionMessage = getArguments().getString(KEY_EXCEPTION_MESSAGE);
            baseAlertDialogBuilder.setTitle(getString( R.string.import_export_keys_dialog_failure_title));
            baseAlertDialogBuilder.setMessage(formatString(requireContext(),R.string.export_keys_dialog_failure, exceptionMessage));
            baseAlertDialogBuilder.setNeutralText(getString(R.string.button_dismiss));
            baseAlertDialogBuilder.setNeutralAction(
                    () -> {
                        activity.finish();
                        return Unit.INSTANCE;
                    }
            );
            baseAlertDialogBuilder.setShowIcon(true);
            alertDialog = baseAlertDialogBuilder.buildAlertDialog();
            return super.onCreateDialog(savedInstanceState);
        }
    }
}
