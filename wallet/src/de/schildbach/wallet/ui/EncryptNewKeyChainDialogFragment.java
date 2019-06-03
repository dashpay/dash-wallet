package de.schildbach.wallet.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.spongycastle.crypto.params.KeyParameter;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.send.DecryptSeedTask;
import de.schildbach.wallet.ui.send.DeriveKeyTask;
import de.schildbach.wallet.util.ParcelableChainPath;
import de.schildbach.wallet_test.R;

/**
 * Created by Hash Engineering on 4/5/2018.
 */

public class EncryptNewKeyChainDialogFragment extends AbstractPINDialogFragment {

    private static final String FRAGMENT_TAG = EncryptNewKeyChainDialogFragment.class.getName();
    private static final String ARGS_PATH = "chain_path";

    public static void show(FragmentManager fm, ImmutableList<ChildNumber> path) {
        EncryptNewKeyChainDialogFragment dialogFragment = new EncryptNewKeyChainDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARGS_PATH, new ParcelableChainPath(path));
        dialogFragment.setArguments(args);
        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    public EncryptNewKeyChainDialogFragment() {
        this.dialogTitle = R.string.encrypt_new_key_chain_dialog_title;
        this.dialogLayout = R.layout.encrypt_new_key_chain_dialog;
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }
        super.onDismiss(dialog);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        setCancelable(false);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        String message = getString(R.string.encrypt_new_key_chain_dialog_message) + "\n\n" +
                getString(R.string.encrypt_new_key_chain_enter_pin_dialog_message) + "\n\n" +
                getString(R.string.pin_code_required_dialog_message);
        messageTextView.setText(message);
        return dialog;
    }

    @Override
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
                pinRetryController.clearPinFailPrefs();
                handleDecryptPIN(password);
                dismissAllowingStateLoss();
                if (activity != null && activity instanceof OnNewKeyChainEncryptedListener) {
                    ((OnNewKeyChainEncryptedListener) activity).onNewKeyChainEncrypted();
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintHelper != null) {
                    if (!fingerprintHelper.isFingerprintEnabled() && WalletApplication
                            .getInstance().getConfiguration().getRemindEnableFingerprint()) {
                        EnableFingerprintDialog.show(password,
                                getActivity().getFragmentManager());
                    }
                }
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
                    pinRetryController.clearPinFailPrefs();
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
                    pinRetryController.clearPinFailPrefs();
                    ParcelableChainPath parcelableChainPath = getArguments().getParcelable(ARGS_PATH);
                    handleAddKeyChain(seed, parcelableChainPath.getPath(), encryptionKey);
                }

                protected void onBadPassphrase() {
                    // can this happen?
                }
            }.decryptSeed(wallet.getActiveKeyChain().getSeed(), wallet.getKeyCrypter(), encryptionKey);

        } else {

        }
    }
    protected void handleAddKeyChain(DeterministicSeed seed, ImmutableList<ChildNumber> path, final KeyParameter encryptionKey) {
        DeterministicKeyChain keyChain = new DeterministicKeyChain(seed, path);
        DeterministicKeyChain encryptedKeyChain = keyChain.toEncrypted(application.getWallet().getKeyCrypter(), encryptionKey);
        application.getWallet().addAndActivateHDChain(encryptedKeyChain);
    }

    public interface OnNewKeyChainEncryptedListener {
        void onNewKeyChainEncrypted();
    }
}
