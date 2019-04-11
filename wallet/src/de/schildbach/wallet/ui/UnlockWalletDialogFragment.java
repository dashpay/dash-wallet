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

import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.view.View;

import org.spongycastle.crypto.params.KeyParameter;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.WalletLock;
import de.schildbach.wallet.ui.send.DeriveKeyTask;
import de.schildbach.wallet_test.R;

public class UnlockWalletDialogFragment extends AbstractPINDialogFragment {

    private static final String FRAGMENT_TAG = UnlockWalletDialogFragment.class.getName();
    private static final String ARG_DECRYPT_WALLET = "arg_decrypt_wallet";

    public static void show(final FragmentManager fm) {
        new UnlockWalletDialogFragment().show(fm, FRAGMENT_TAG);
    }

    public static void show(FragmentManager fm, DialogInterface.OnDismissListener onDismissListener) {
        show(fm, onDismissListener, false);
    }

    public static void show(FragmentManager fm, DialogInterface.OnDismissListener onDismissListener,
                            boolean decryptWallet) {
        UnlockWalletDialogFragment dialogFragment = new UnlockWalletDialogFragment();

        Bundle args = new Bundle();
        args.putBoolean(ARG_DECRYPT_WALLET, decryptWallet);
        dialogFragment.setArguments(args);

        dialogFragment.onDismissListener = onDismissListener;
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    public UnlockWalletDialogFragment() {
        this.dialogTitle = R.string.wallet_lock_unlock_wallet;
        this.dialogLayout = R.layout.unlock_wallet_dialog;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }
        super.onDismiss(dialog);
    }

    protected void checkPassword(final String password) {
        if (pinRetryController.isLocked()) {
            return;
        }

        fingerprintView.hideError();
        unlockButton.setEnabled(false);
        unlockButton.setText(getText(R.string.encrypt_keys_dialog_state_decrypting));
        pinView.setEnabled(false);

        new CheckWalletPasswordTask(backgroundHandler) {
            @Override
            protected void onSuccess() {
                if (getArguments().getBoolean(ARG_DECRYPT_WALLET, false)) {
                    new DeriveKeyTask(backgroundHandler, application.scryptIterationsTarget()) {
                        @Override
                        protected void onSuccess(KeyParameter encryptionKey, boolean changed) {
                            wallet.decrypt(encryptionKey);
                            onWalletUnlocked(password);
                        }
                    }.deriveKey(wallet, password);
                } else {
                    onWalletUnlocked(password);
                }
            }

            @Override
            protected void onBadPassword() {
                UnlockWalletDialogFragment.this.onBadPassword(password);
            }
        }.checkPassword(wallet, password);
    }

    private void onWalletUnlocked(String password) {
        if (getActivity() != null && isAdded()) {
            pinRetryController.successfulAttempt();
            WalletLock.getInstance().setWalletLocked(false);

            dismissAllowingStateLoss();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintHelper != null) {
                if (!fingerprintHelper.isFingerprintEnabled() && WalletApplication
                        .getInstance().getConfiguration().getRemindEnableFingerprint()) {
                    EnableFingerprintDialog.show(password,
                            getActivity().getFragmentManager());
                }
            }
        }
    }

    private void onBadPassword(String password) {
        if (getActivity() != null && isAdded()) {
            unlockButton.setEnabled(true);
            unlockButton.setText(getText(R.string.wallet_lock_unlock));
            pinView.setEnabled(true);
            pinRetryController.failedAttempt(password);
            badPinView.setText(getString(R.string.wallet_lock_wrong_pin,
                    pinRetryController.getRemainingAttemptsMessage()));
            badPinView.setVisibility(View.VISIBLE);
        }
    }

}
