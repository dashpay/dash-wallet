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
import android.widget.EditText;
import android.widget.TextView;

import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.util.KeyboardUtil;
import de.schildbach.wallet_test.R;

public class UnlockWalletDialogFragment extends DialogFragment {

    private static final String FRAGMENT_TAG = UnlockWalletDialogFragment.class.getName();

    private DialogInterface.OnDismissListener onDismissListener;

    public static void show(final FragmentManager fm) {
        new UnlockWalletDialogFragment().show(fm, FRAGMENT_TAG);
    }


    public static void show(FragmentManager fm, DialogInterface.OnDismissListener onDismissListener) {
        UnlockWalletDialogFragment dialogFragment = new UnlockWalletDialogFragment();
        dialogFragment.onDismissListener = onDismissListener;
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    private AbstractWalletActivity activity;
    private WalletApplication application;
    private Handler backgroundHandler;
    private Wallet wallet;
    private PinRetryController pinRetryController;

    private EditText pinView;
    private TextView badPinView;
    private Button unlockButton;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.wallet = application.getWallet();
        this.pinRetryController = new PinRetryController(activity);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.unlock_wallet_dialog, null);

        pinView = (EditText) view.findViewById(R.id.pin);
        badPinView = (TextView) view.findViewById(R.id.bad_pin);
        unlockButton = (Button) view.findViewById(R.id.unlock);

        pinView.addTextChangedListener(privateKeyPasswordListener);
        unlockButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPassword(pinView.getText().toString());
            }
        });

        if (wallet.isEncrypted()) {
            HandlerThread backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }

        final DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.wallet_lock_unlock_dialog_title);
        builder.setView(view);
        builder.setCancelable(false);

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                KeyboardUtil.showSoftKeyboard(getActivity(), pinView);
            }
        });

        return alertDialog;
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        if (onDismissListener != null) {
            onDismissListener.onDismiss(dialog);
        }
        pinView.removeTextChangedListener(privateKeyPasswordListener);
        super.onDismiss(dialog);
    }

    private final TextWatcher privateKeyPasswordListener = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            badPinView.setVisibility(View.INVISIBLE);
            unlockButton.setEnabled(true);
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private void checkPassword(final String password) {
        if (pinRetryController.isLocked()) {
            return;
        }

        unlockButton.setEnabled(false);
        unlockButton.setText(getText(R.string.encrypt_keys_dialog_state_decrypting));
        pinView.setEnabled(false);
        new CheckWalletPasswordTask(backgroundHandler) {

            @Override
            protected void onSuccess() {
                pinRetryController.successfulAttempt();
                WalletLock.getInstance().setWalletLocked(false);
                dismissAllowingStateLoss();
            }

            @Override
            protected void onBadPassword() {
                unlockButton.setEnabled(true);
                unlockButton.setText(getText(R.string.wallet_lock_unlock));
                pinView.setEnabled(true);
                pinRetryController.failedAttempt(password);
                badPinView.setText(getString(R.string.wallet_lock_wrong_pin,
                        pinRetryController.getRemainingAttemptsMessage()));
                badPinView.setVisibility(View.VISIBLE);
            }
        }.checkPassword(wallet, password);
    }

}
