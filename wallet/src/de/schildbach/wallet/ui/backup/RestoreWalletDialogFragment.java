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

import android.annotation.SuppressLint;
import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.ui.OnboardingActivity;
import de.schildbach.wallet.ui.RestoreWalletFromFileViewModel;
import de.schildbach.wallet_test.R;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.ShowPasswordCheckListener;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.Toast;
import de.schildbach.wallet.util.WalletUtils;
import kotlin.Unit;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.dash.wallet.common.ui.BaseAlertDialogBuilderKt.formatString;

import javax.inject.Inject;

/**
 * @author Andreas Schildbach
 */
@AndroidEntryPoint
public class RestoreWalletDialogFragment extends DialogFragment {
    private static final String FRAGMENT_TAG = RestoreWalletDialogFragment.class.getName();
    private static final String KEY_BACKUP_URI = "backup_uri";
    private static final int REQUEST_CODE_OPEN_DOCUMENT = 0;

    private OnboardingActivity activity;
    @Inject
    WalletApplication application;
    private ContentResolver contentResolver;
    private Configuration config;
    private FragmentManager fragmentManager;

    private TextView messageView;
    private EditText passwordView;
    private CheckBox showView;
    private View replaceWarningView;

    private RestoreWalletFromFileViewModel viewModel;

    private static final Logger log = LoggerFactory.getLogger(RestoreWalletDialogFragment.class);

    public static void showPick(final FragmentManager fm) {
        final DialogFragment newFragment = new RestoreWalletDialogFragment();
        newFragment.show(fm, FRAGMENT_TAG);
    }

    public static void show(final FragmentManager fm, final Uri backupUri) {
        final DialogFragment newFragment = new RestoreWalletDialogFragment();
        final Bundle args = new Bundle();
        args.putParcelable(KEY_BACKUP_URI, backupUri);
        newFragment.setArguments(args);
        newFragment.show(fm, FRAGMENT_TAG);
    }

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        this.activity = (OnboardingActivity) context;
        this.contentResolver = application.getContentResolver();
        this.config = application.getConfiguration();
        this.fragmentManager = getParentFragmentManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log.info("opening dialog {}", getClass().getName());

        viewModel = new ViewModelProvider(activity).get(RestoreWalletFromFileViewModel.class);

        viewModel.getShowFailureDialog().observe(this, new Observer<String>() {
            @Override
            public void onChanged(final String message) {
                showRetryDialog(activity, listener, message);
            }
        });
        viewModel.getBackupUri().observe(this, uri -> {
            final String backupProvider = WalletUtils.uriToProvider(uri);
            log.info("picked '{}'{}", uri, backupProvider != null ? " (" + backupProvider + ")" : "");
            final Cursor cursor = contentResolver.query(uri, null, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst())
                        viewModel.getDisplayName().setValue(cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)));
                } finally {
                    cursor.close();
                }
            }
        });
        viewModel.getDisplayName().observe(this, name -> messageView.setText(name));

        final Bundle args = getArguments();
        if (args != null) {
            viewModel.getBackupUri().setValue((Uri) args.getParcelable(KEY_BACKUP_URI));
        } else {
            final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT);
            } catch (final ActivityNotFoundException x) {
                log.warn("Cannot open document selector: {}", intent);
                new Toast(activity).longToast(R.string.toast_start_storage_provider_selector_failed);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT) {
            if (resultCode == Activity.RESULT_OK) {
                if (data != null) {
                    viewModel.getBackupUri().setValue(data.getData());
                } else {
                    log.info("didn't get uri");
                    dismiss();
                    maybeFinishActivity();
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                log.info("cancelled restoring wallet");
                dismiss();
                maybeFinishActivity();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.restore_wallet_dialog, null);
        messageView = view.findViewById(R.id.restore_wallet_dialog_message);
        passwordView = view.findViewById(R.id.restore_wallet_dialog_password);
        showView = view.findViewById(R.id.restore_wallet_dialog_show);
        replaceWarningView = view.findViewById(R.id.restore_wallet_dialog_replace_warning);

        final BaseAlertDialogBuilder dialogBuilder = new BaseAlertDialogBuilder(requireActivity());
        dialogBuilder.setTitle(getString(R.string.import_keys_dialog_title));
        dialogBuilder.setView(view);
        dialogBuilder.setPositiveText(getString(R.string.import_keys_dialog_button_import));
        dialogBuilder.setPositiveAction(
                () -> {
                    final String password = passwordView.getText().toString().trim();
                    passwordView.setText(null);
                    handleRestore(password);
                    return Unit.INSTANCE;
                }
        );
        dialogBuilder.setNegativeText(getString(R.string.button_cancel));
        dialogBuilder.setNegativeAction(
                () -> {
                    passwordView.setText(null);
                    maybeFinishActivity();
                    return Unit.INSTANCE;
                }
        );
        dialogBuilder.setCancelAction(
                () -> {
                    passwordView.setText(null);
                    maybeFinishActivity();
                    return Unit.INSTANCE;
                }
        );


        final AlertDialog dialog = dialogBuilder.buildAlertDialog();

        dialog.setOnShowListener(d -> {
            final ImportDialogButtonEnablerListener dialogButtonEnabler = new ImportDialogButtonEnablerListener(
                    passwordView, dialog) {
                @Override
                protected boolean hasFile() {
                    return true;
                }
            };
            passwordView.addTextChangedListener(dialogButtonEnabler);
            showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView));
        });

        return dialog;
    }

    private void handleRestore(final String password) {
        final Uri backupUri = viewModel.getBackupUri().getValue();
        if (backupUri != null) {
            try {
                Wallet wallet = viewModel.restoreWalletFromUri(backupUri, password);
                listener.onRestoreWallet(wallet);
                /*final InputStream is = contentResolver.openInputStream(backupUri);

                if (WalletUtils.isUnencryptedStream(contentResolver.openInputStream(backupUri))) {
                    RestoreFromFileHelper.restoreWalletFromProtobuf(activity,
                            backupUri, contentResolver.openInputStream(backupUri),
                            application.getWalletExtensions(), listener);
                } else if (WalletUtils.isKeysStream(contentResolver.openInputStream(backupUri))) {
                    RestoreFromFileHelper.restorePrivateKeysFromBase58(activity,
                            this.getActivity(), this.getActivity(),
                            backupUri, contentResolver.openInputStream(backupUri), listener);
                } else if (Crypto.isEncryptedStream(is)) {
                    RestoreFromFileHelper.restoreWalletFromEncrypted(activity,
                            this.getActivity(), this.getActivity(),
                            backupUri, contentResolver.openInputStream(backupUri), password, listener);
                }*/

                log.info("successfully restored wallet from external source");
            } catch (final IOException x) {
                viewModel.getShowFailureDialog().setValue(x.getMessage());
                log.info("problem restoring wallet", x);
            }
        } else {
            final String message = "no backup data provided";
            viewModel.getShowFailureDialog().setValue(message);
            log.info("problem restoring wallet: {}", message);
        }
    }

//    public static class SuccessDialogFragment extends DialogFragment {
//        private static final String FRAGMENT_TAG = SuccessDialogFragment.class.getName();
//        private static final String KEY_SHOW_ENCRYPTED_MESSAGE = "show_encrypted_message";
//
//        private Activity activity;
//
//        public static void showDialog(final FragmentManager fm, final boolean showEncryptedMessage) {
//            final DialogFragment newFragment = new SuccessDialogFragment();
//            final Bundle args = new Bundle();
//            args.putBoolean(KEY_SHOW_ENCRYPTED_MESSAGE, showEncryptedMessage);
//            newFragment.setArguments(args);
//            newFragment.show(fm, FRAGMENT_TAG);
//        }
//
//        @Override
//        public void onAttach(final Context context) {
//            super.onAttach(context);
//            this.activity = (Activity) context;
//        }
//
//        @Override
//        public Dialog onCreateDialog(final Bundle savedInstanceState) {
//            final boolean showEncryptedMessage = getArguments().getBoolean(KEY_SHOW_ENCRYPTED_MESSAGE);
//            final StringBuilder message = new StringBuilder();
//            message.append(getString(R.string.restore_wallet_dialog_success));
//            message.append("\n\n");
//            message.append(getString(R.string.restore_wallet_dialog_success_replay));
//            if (showEncryptedMessage) {
//                message.append("\n\n");
//                message.append(getString(R.string.restore_wallet_dialog_success_encrypted));
//            }
//
//            final BaseAlertDialogBuilder restoreWalletSuccessAlertDialogBuilder = new BaseAlertDialogBuilder(requireActivity());
//            restoreWalletSuccessAlertDialogBuilder.setMessage(message);
//            restoreWalletSuccessAlertDialogBuilder.setNeutralText(getString(R.string.button_ok));
//            restoreWalletSuccessAlertDialogBuilder.setNeutralAction(
//                    () -> {
//                        WalletApplication.getInstance().resetBlockchain();
//                        activity.finish();
//                        return Unit.INSTANCE;
//                    }
//            );
//            return restoreWalletSuccessAlertDialogBuilder.buildAlertDialog();
//        }
//    }

//    public static class FailureDialogFragment extends DialogFragment {
//        private static final String FRAGMENT_TAG = FailureDialogFragment.class.getName();
//        private static final String KEY_EXCEPTION_MESSAGE = "exception_message";
//        private static final String KEY_BACKUP_URI = "backup_uri";
//
//        private Activity activity;
//
//        public static void showDialog(final FragmentManager fm, final String exceptionMessage, final Uri backupUri) {
//            final DialogFragment newFragment = new FailureDialogFragment();
//            final Bundle args = new Bundle();
//            args.putString(KEY_EXCEPTION_MESSAGE, exceptionMessage);
//            args.putParcelable(KEY_BACKUP_URI, checkNotNull(backupUri));
//            newFragment.setArguments(args);
//            newFragment.show(fm, FRAGMENT_TAG);
//        }
//
//        @Override
//        public void onAttach(final Context context) {
//            super.onAttach(context);
//            this.activity = (Activity) context;
//        }
//
//        @Override
//        public Dialog onCreateDialog(final Bundle savedInstanceState) {
//            final String exceptionMessage = getArguments().getString(KEY_EXCEPTION_MESSAGE);
//            final Uri backupUri = checkNotNull((Uri) getArguments().getParcelable(KEY_BACKUP_URI));
//
//            BaseAlertDialogBuilder restoreWalletFailAlertDialogBuilder = new BaseAlertDialogBuilder(requireActivity());
//            restoreWalletFailAlertDialogBuilder.setTitle(getString(R.string.import_export_keys_dialog_failure_title));
//            restoreWalletFailAlertDialogBuilder.setMessage(formatString(requireContext(), R.string.import_keys_dialog_failure, exceptionMessage));
//            restoreWalletFailAlertDialogBuilder.setPositiveText(getString(R.string.button_dismiss));
//            restoreWalletFailAlertDialogBuilder.setNegativeText(getString(R.string.button_retry));
//            restoreWalletFailAlertDialogBuilder.setNegativeAction(
//                    () -> {
//                        RestoreWalletDialogFragment.show(getParentFragmentManager(), backupUri);
//                        return Unit.INSTANCE;
//                    }
//            );
//            restoreWalletFailAlertDialogBuilder.setShowIcon(true);
//
//            return restoreWalletFailAlertDialogBuilder.buildAlertDialog();
//        }
//    }

    private void maybeFinishActivity() {
    }

    OnRestoreWalletListener listener = new OnRestoreWalletListener() {

        @Override
        public void onRestoreWallet(Wallet wallet) {
            viewModel.getRestoreWallet().postValue(wallet);
        }

        @Override
        public void onRetryRequest() {
            viewModel.getRetryRequest().call(null);
        }
    };

    @SuppressLint("StringFormatInvalid")
    private static void showRetryDialog(Activity activity, OnRestoreWalletListener listener, String message) {
        AdaptiveDialog.create(R.drawable.ic_backup_info,
                activity.getString(R.string.import_export_keys_dialog_failure_title),
                activity.getString(R.string.import_keys_dialog_failure, message),
                activity.getString(R.string.button_dismiss),
                activity.getString(R.string.button_retry)
        ).show((FragmentActivity) activity, retry -> {
            listener.onRetryRequest();
            return Unit.INSTANCE;
        });
    }
}
