/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
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

import android.app.Dialog;
import android.app.ProgressDialog;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModelProviders;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Transaction;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.common.util.ProgressDialogUtils;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.Arrays;
import java.util.regex.Pattern;

import de.schildbach.wallet.data.ErrorType;
import de.schildbach.wallet.data.LoadingType;
import de.schildbach.wallet.data.Resource;
import de.schildbach.wallet.data.StatusType;
import de.schildbach.wallet.data.SuccessType;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class CreateBlockchainUserDialog extends DialogFragment {

    private static final String ARG_ENCRYPTION_KEY = "arg_encryption_key";
    private ProgressDialog loadingDialog;

    public static void show(FragmentManager fm, KeyParameter encryptionKey) {
        Bundle args = new Bundle();
        args.putByteArray(ARG_ENCRYPTION_KEY, encryptionKey.getKey());

        CreateBlockchainUserDialog dialog = new CreateBlockchainUserDialog();
        dialog.setArguments(args);
        dialog.show(fm, CreateBlockchainUserDialog.class.getSimpleName());
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final FragmentActivity activity = getActivity();
        final BlockchainUserViewModel viewModel = ViewModelProviders.of(activity).get(BlockchainUserViewModel.class);
        final View view = LayoutInflater.from(activity).inflate(R.layout.create_blockchain_user_dialog,
                null);
        final TextView usernameTextView = (TextView) view.findViewById(R.id.buname);

        DialogBuilder dialogBuilder = new DialogBuilder(activity);
        dialogBuilder.setView(view);
        dialogBuilder.setTitle(R.string.create_blockchain_user);
        dialogBuilder.setPositiveButton(R.string.create, null);

        Dialog dialog = dialogBuilder.create();

        dialog.setOnShowListener(dialogInterface -> {
            Button createButton = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE);
            createButton.setEnabled(false);
            Pattern pattern = Pattern.compile("[^a-z0-9._]");
            usernameTextView.addTextChangedListener(new TextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    boolean valid = !pattern.matcher(editable.toString()).find();
                    int length = editable.length();
                    valid = valid && length >= 3 && length <= 24;
                    createButton.setEnabled(valid);
                }

                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }
            });
            createButton.setOnClickListener(v -> {
                createBlockchainUser(usernameTextView.getText().toString(), viewModel);
            });
        });

        return dialog;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        hideLoading();
        super.onDismiss(dialog);
    }

    private void createBlockchainUser(String username, BlockchainUserViewModel viewModel) {
        LiveData<Resource<Transaction>> liveData = viewModel
                .createBlockchainUser(username, getArguments().getByteArray(ARG_ENCRYPTION_KEY));
        liveData.observe(this, transactionResource -> {
            StatusType status = transactionResource.status;
            if (Arrays.asList(SuccessType.values()).contains(status)) {
                String usernameSuccessMessage = getActivity()
                        .getString(R.string.username_registered_success_message, username);
                Toast.makeText(getActivity(), usernameSuccessMessage, Toast.LENGTH_SHORT).show();
                dismiss();
            } else if (Arrays.asList(LoadingType.values()).contains(status)) {
                showLoading();
            } else {
                hideLoading();
                if (ErrorType.INSUFFICIENT_MONEY.equals(status)) {
                    Toast.makeText(getActivity(), R.string.send_coins_fragment_insufficient_money_title,
                            Toast.LENGTH_SHORT).show();
                } else if (ErrorType.TX_REJECT_DUP_USERNAME.equals(status)) {
                    Toast.makeText(getActivity(), R.string.blockchain_user_duplicated_username_error,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), R.string.unknown_error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void showLoading() {
        if (loadingDialog == null) {
            loadingDialog = ProgressDialogUtils.createSpinningLoading(getActivity());
        }
        loadingDialog.show();
    }

    private void hideLoading() {
        if (loadingDialog != null) {
            loadingDialog.cancel();
        }
    }

}
