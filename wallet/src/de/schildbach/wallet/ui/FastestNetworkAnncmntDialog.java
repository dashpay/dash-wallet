package de.schildbach.wallet.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AlertDialog;
import android.text.Html;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;


public class FastestNetworkAnncmntDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = FastestNetworkAnncmntDialog.class.getName();

    public static FastestNetworkAnncmntDialog show(FragmentManager fm) {
        FastestNetworkAnncmntDialog dialogFragment = (FastestNetworkAnncmntDialog) fm.findFragmentByTag(FRAGMENT_TAG);
        if (dialogFragment == null) {
            dialogFragment = new FastestNetworkAnncmntDialog();
            FragmentTransaction ft = fm.beginTransaction();
            ft.add(dialogFragment, FRAGMENT_TAG);
            ft.commitAllowingStateLoss();

        }
        return dialogFragment;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        setCancelable(false);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @SuppressWarnings("ConstantConditions")
    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        Activity activity = getActivity();
        LayoutInflater layoutInflater = LayoutInflater.from(activity);
        @SuppressLint("InflateParams")
        View customView = layoutInflater.inflate(R.layout.fastest_payment_network_dialog, null);
        TextView messageView = customView.findViewById(R.id.message);
        Spanned message = Html.fromHtml(getString(R.string.fastest_payment_network_dialog_message));
        messageView.setText(message);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(customView)
                .setCancelable(false);

        customView.findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                ((WalletApplication) getActivity().getApplication()).getConfiguration().setFastestNetworkAnncmntShown();
            }
        });

        return builder.create();
    }
}
