package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.spongycastle.crypto.params.KeyParameter;

import de.schildbach.wallet.ui.send.DecryptSeedTask;
import de.schildbach.wallet.ui.send.DeriveKeyTask;
import de.schildbach.wallet_test.R;

/**
 * Created by Hash Engineering on 4/5/2018.
 */

public class EncryptNewKeyChainFragment extends UnlockWalletDialogFragment {
    private static final String FRAGMENT_TAG = EncryptNewKeyChainFragment.class.getName();

    private DialogInterface.OnDismissListener onDismissListener;
    private ImmutableList<ChildNumber> path;

    public static void show(FragmentManager fm, ImmutableList<ChildNumber> path) {
        EncryptNewKeyChainFragment dialogFragment = new EncryptNewKeyChainFragment();
        dialogFragment.path = path;
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    public static void show(FragmentManager fm, DialogInterface.OnDismissListener onDismissListener, ImmutableList<ChildNumber> path) {
        EncryptNewKeyChainFragment dialogFragment = new EncryptNewKeyChainFragment();
        dialogFragment.path = path;
        dialogFragment.onDismissListener = onDismissListener;
        dialogFragment.show(fm, FRAGMENT_TAG);
    }
    @Override
    protected void checkPassword(final String password) {
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
                handleDecryptPIN(password);
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

    private void handleDecryptPIN(final String password) {
        if (wallet.isEncrypted()) {

            if (pinRetryController.isLocked()) {
                return;
            }
            new DeriveKeyTask(backgroundHandler, application.scryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    pinRetryController.successfulAttempt();
                    handleDecryptSeed(encryptionKey, password);
                }
            }.deriveKey(wallet, password);

        } else {

        }
    }

    private void handleDecryptSeed(final KeyParameter encryptionKey, final String password) {
        if (wallet.isEncrypted()) {
            if (pinRetryController.isLocked()) {
                return;
            }
            new DecryptSeedTask(backgroundHandler) {
                @Override
                protected void onSuccess(final DeterministicSeed seed) {
                    pinRetryController.successfulAttempt();

                    handleCreateKeyChainAndAdd(seed, path, encryptionKey);
                }

                protected void onBadPassphrase() {
                    // can this happen?
                }
            }.decryptSeed(wallet.getActiveKeyChain().getSeed(), wallet.getKeyCrypter(), encryptionKey);

        } else {

        }
    }
    protected void handleCreateKeyChainAndAdd(DeterministicSeed seed, ImmutableList<ChildNumber> path, final KeyParameter encryptionKey)
    {
        DeterministicKeyChain keyChain = new DeterministicKeyChain(seed, path);
        DeterministicKeyChain encryptedKeyChain = keyChain.toEncrypted(application.getWallet().getKeyCrypter(), encryptionKey);
        application.getWallet().addAndActivateHDChain(encryptedKeyChain);
    }
}
