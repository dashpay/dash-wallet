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
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.ViewModelProviders;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.dash.wallet.common.ui.DialogBuilder;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.Arrays;

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
        //TODO: Move to resource string
        dialogBuilder.setTitle("Create Blockchain User");
        dialogBuilder.setPositiveButton("Create", null);

        Dialog dialog = dialogBuilder.create();

        dialog.setOnShowListener(dialogInterface -> {
            ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String username = usernameTextView.getText().toString();
                LiveData<Resource<Transaction>> liveData = viewModel
                        .createBlockchainUser(username, getArguments().getByteArray(ARG_ENCRYPTION_KEY));
                liveData.observe(this, transactionResource -> {
                    StatusType status = transactionResource.status;
                    if (Arrays.asList(SuccessType.values()).contains(status)) {
                        Toast.makeText(getActivity(), "Success. Username " + username + " registered.",
                                Toast.LENGTH_SHORT).show();
                        dismiss();
                    } else if (Arrays.asList(LoadingType.values()).contains(status)) {
                        showLoading();
                    } else {
                        hideLoading();
                        if (ErrorType.INSUFFICIENT_MONEY.equals(status)) {
                            Toast.makeText(getActivity(), "Not enough minerals", Toast.LENGTH_SHORT).show();
                        } else if (ErrorType.TX_REJECT_DUP_USERNAME.equals(status)) {
                            Toast.makeText(getActivity(), "Error. This username is already registered.", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getActivity(), "Unknown error.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            });
        });

        return dialog;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        hideLoading();
        super.onDismiss(dialog);
    }

    private void showLoading() {
        ProgressDialogFragment.showProgress(getChildFragmentManager(), getString(R.string.loading));
    }

    private void hideLoading() {
        //TODO: Use another loading.
        try {
            ProgressDialogFragment.dismissProgress(getChildFragmentManager());
        } catch (NullPointerException e) {
            //It was not being shown after all
        }
    }

}
