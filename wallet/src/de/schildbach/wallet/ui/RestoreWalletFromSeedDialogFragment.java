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
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.DialogBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.KeyboardUtil;
import de.schildbach.wallet.util.MnemonicCodeExt;
import de.schildbach.wallet.util.WalletUtils;
import de.schildbach.wallet_test.R;
import android.annotation.SuppressLint;


public class RestoreWalletFromSeedDialogFragment extends DialogFragment {

    private static final String FRAGMENT_TAG = RestoreWalletFromSeedDialogFragment.class.getName();
    private static final Logger log = LoggerFactory.getLogger(RestoreWalletFromSeedDialogFragment.class);

    private static final int NUMBER_OF_WORDS_IN_SEED = 12;

    public static void show(final FragmentManager fm) {
        final DialogFragment newFragment = new RestoreWalletFromSeedDialogFragment();
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private View replaceWarningView;
    private EditText passwordView;
    private TextView invalidWordView;


    private AppCompatActivity activity;
    private Wallet wallet;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AppCompatActivity) activity;
        WalletApplication application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
    }

    @Override
    public void onStart() {
        super.onStart();
        initDialog();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        clearPasswordView();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        clearPasswordView();
    }

    private void clearPasswordView() {
        passwordView.setText(null); // get rid of it asap
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {

        final DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.import_keys_dialog_title_from_seed);
        final View view = LayoutInflater.from(activity).inflate(R.layout.restore_wallet_from_seed_dialog, null);
        builder.setView(view);
        final TextView messageView = (TextView) view.findViewById(R.id.restore_wallet_dialog_message);
        messageView.setText(getString(R.string.import_keys_from_seed_dialog_message));
        replaceWarningView = view.findViewById(R.id.restore_wallet_from_storage_dialog_replace_warning);
        passwordView = (EditText) view.findViewById(R.id.import_seed_recovery_phrase);
        setupPasswordView();
        invalidWordView = (TextView)view.findViewById(R.id.restore_wallet_from_invalid_seed_warning);

        builder.setPositiveButton(R.string.import_keys_dialog_button_import, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                final String password = passwordView.getText().toString().trim();
                clearPasswordView();

                List<String> words = new ArrayList<>(Arrays.asList(password.split(" ")));

                restoreWalletFromSeed(words);
            }
        });
        builder.setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                clearPasswordView();
            }
        });

        return builder.create();
    }

    private void setupPasswordView() {
        passwordView.post(new Runnable() {
            @Override
            public void run() {
                KeyboardUtil.showSoftKeyboard(getActivity(), passwordView);
            }
        });
        passwordView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                setupRestoreButtonState();
            }
        });
        passwordView.setFilters(new InputFilter[]{
                new InputFilter() {
                    public CharSequence filter(CharSequence src, int start, int end, Spanned dst, int dstart, int dend) {
                        if (src.equals("")) { // for backspace
                            return src;
                        }
                        int numOfWords = numOfWordsInPasswordView();
                        if (numOfWords >= NUMBER_OF_WORDS_IN_SEED && src.equals(" ")) {
                            return "";
                        }
                        if (src.toString().matches("[a-z ]+")) {
                            return src;
                        }
                        return "";
                    }
                }
        });
    }

    @SuppressLint("StringFormatInvalid")
    private void restoreWalletFromSeed(final List<String> words) {
        final WalletActivity activity = (WalletActivity) this.activity;
        try {
            MnemonicCodeExt.getInstance().check(activity, words);
            activity.restoreWallet(WalletUtils.restoreWalletFromSeed(words, Constants.NETWORK_PARAMETERS));

            log.info("successfully restored wallet from seed: {}", words.size());
        } catch (final IOException | MnemonicException x) {
            final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.import_export_keys_dialog_failure_title);
            dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.getMessage()));
            dialog.setPositiveButton(R.string.button_dismiss, null);
            dialog.setNegativeButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int id) {
                    activity.handleRestoreWalletFromSeed();
                }
            });
            dialog.show();

            log.info("problem restoring wallet from seed: ", x);
        }
    }

    private void initDialog() {
        final List<File> files = new LinkedList<>();
        // external storage
        final File[] externalFiles = Constants.Files.EXTERNAL_WALLET_BACKUP_DIR.listFiles();
        if (externalFiles != null)
            for (final File file : externalFiles)
                if (Crypto.OPENSSL_FILE_FILTER.accept(file))
                    files.add(file);

        // internal storage
        for (final String filename : activity.fileList())
            if (filename.startsWith(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + '.'))
                files.add(new File(activity.getFilesDir(), filename));

        // sort
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(final File lhs, final File rhs) {
                return lhs.getName().compareToIgnoreCase(rhs.getName());
            }
        });

        boolean hasCoins = (wallet != null) && wallet.getBalance(Wallet.BalanceType.ESTIMATED).signum() > 0;
        replaceWarningView.setVisibility(hasCoins ? View.VISIBLE : View.GONE);

        clearPasswordView();
        setupRestoreButtonState();
    }

    private void setupRestoreButtonState() {
        AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null && passwordView != null) {
            int numOfWords = numOfWordsInPasswordView();
            String firstBadWord = firstInvalidWord();
            boolean restoreButtonEnabled = (numOfWords == NUMBER_OF_WORDS_IN_SEED) && firstBadWord == null;
            if(firstBadWord != null) {
                invalidWordView.setText(getString(R.string.restore_wallet_from_invalid_seed_warning_message, firstBadWord));
                invalidWordView.setVisibility(View.VISIBLE);
            }
            else invalidWordView.setVisibility(View.GONE);
            final Button button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            button.setEnabled(restoreButtonEnabled);
            String restoreButtonTitle = getString(R.string.import_keys_dialog_button_import);
            if (!restoreButtonEnabled) {
                restoreButtonTitle += " (" + (NUMBER_OF_WORDS_IN_SEED - numOfWords) + ")";
            }
            button.setText(restoreButtonTitle);
        }
    }

    private int numOfWordsInPasswordView() {
        final String password = passwordView.getText().toString().trim();
        if (password.isEmpty()) {
            return 0;
        } else {
            return password.split(" ").length;
        }
    }

    private String firstInvalidWord() {
        final String password = passwordView.getText().toString().trim();
        if (password.isEmpty()) {
            return null;
        } else {
            String [] words = password.split(" ");
            List<String> wordList = MnemonicCodeExt.getInstance().getWordList();

            for(int i = 0; i < words.length; ++i)
                if(!wordList.contains(words[i]))
                    return words[i];

            return null;
        }
    }
}
