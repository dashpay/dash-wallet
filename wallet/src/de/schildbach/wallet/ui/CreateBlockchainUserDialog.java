package de.schildbach.wallet.ui;

import android.app.Dialog;
import android.arch.lifecycle.ViewModelProviders;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import org.bitcoinj.core.InsufficientMoneyException;
import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class CreateBlockchainUserDialog extends DialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final FragmentActivity activity = getActivity();
        final BlockchainUserViewModel viewModel = ViewModelProviders.of(activity).get(BlockchainUserViewModel.class);
        final View view = LayoutInflater.from(activity).inflate(R.layout.create_blockchain_user_dialog,
                null);
        final TextView usernameTextView = (TextView) view.findViewById(R.id.buname);

        DialogBuilder dialogBuilder = new DialogBuilder(activity);
        //TODO: Move to resource string
        dialogBuilder.setTitle("Create Blockchain User");
        dialogBuilder.setView(view);
        dialogBuilder.setPositiveButton("Create", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                try {
                    viewModel.createBlockchainUser(usernameTextView.getText().toString());
                } catch (InsufficientMoneyException e) {
                    e.printStackTrace();
                }
            }
        });
        return dialogBuilder.create();
    }

}
