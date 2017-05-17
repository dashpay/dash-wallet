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
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.content.DialogInterface.OnShowListener;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.google.common.base.Charsets;

import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.annotation.Nullable;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.Iso8601Format;
import de.schildbach.wallet_test.R;

import static com.google.common.base.Preconditions.checkState;
import static de.schildbach.wallet.Constants.HEX;

/**
 * @author Andreas Schildbach
 */
public class BackupWalletToSeedDialogFragment extends DialogFragment {
    private static final String FRAGMENT_TAG = BackupWalletToSeedDialogFragment.class.getName();

    public static void show(final FragmentManager fm) {
        final DialogFragment newFragment = new BackupWalletToSeedDialogFragment();
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Wallet wallet;

    @Nullable
    private AlertDialog dialog;

    private TextView seedView;
    private CheckBox showView;
    private Button positiveButton;

    private static final Logger log = LoggerFactory.getLogger(BackupWalletToSeedDialogFragment.class);

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.backup_wallet_to_seed_dialog, null);

        seedView = (TextView) view.findViewById(R.id.backup_wallet_dialog_seed);
        List<String> code = wallet.getKeyChainSeed().getMnemonicCode();

            StringBuilder wordlist1 = new StringBuilder(255);
            for (String word : code)
            {
                wordlist1.append(word + " ");
            }


        seedView.setText(wordlist1);

        final TextView warningView = (TextView) view.findViewById(R.id.backup_wallet_dialog_warning_encrypted);
        warningView.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE);

        final DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.export_keys_dialog_title);
        builder.setView(view);
        //builder.setPositiveButton(R.string.button_ok, null); // dummy, just to make it show
        builder.setNegativeButton(R.string.button_ok, null);
        builder.setCancelable(false);

        final AlertDialog dialog = builder.create();

        return dialog;
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        this.dialog = null;

        super.onDismiss(dialog);
    }

    private void handleGo() {
        /*final String password = seedView.getText().toString().trim();
        final String passwordAgain = passwordAgainView.getText().toString().trim();

        if (passwordAgain.equals(password)) {
            seedView.setText(null); // get rid of it asap
            passwordAgainView.setText(null);

            backupWallet(password);

            dismiss();

            application.getConfiguration().disarmBackupReminder();
        } else {
            passwordMismatchView.setVisibility(View.VISIBLE);
        }*/
    }


}
