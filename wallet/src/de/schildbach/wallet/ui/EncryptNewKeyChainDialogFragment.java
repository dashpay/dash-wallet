package de.schildbach.wallet.ui;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bouncycastle.crypto.params.KeyParameter;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.security.SecurityFunctions;
import de.schildbach.wallet.service.RestartService;
import de.schildbach.wallet.payments.DecryptSeedTask;
import de.schildbach.wallet.payments.DeriveKeyTask;
import de.schildbach.wallet.util.ParcelableChainPath;
import de.schildbach.wallet_test.R;

/**
 * Created by Hash Engineering on 4/5/2018.
 */
@AndroidEntryPoint
public class EncryptNewKeyChainDialogFragment extends AbstractPINDialogFragment {

    private static final String FRAGMENT_TAG = EncryptNewKeyChainDialogFragment.class.getName();
    private static final String ARGS_PATH = "chain_path";
    @Inject RestartService restartService;
    @Inject SecurityFunctions securityFunctions;

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
        biometricHelper.cancelPending();
        super.onDismiss(dialog);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        setCancelable(false);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @NonNull
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
                FragmentActivity activity = getActivity();

                if (activity instanceof OnNewKeyChainEncryptedListener) {
                    BackupWalletToSeedDialogFragment.show(getParentFragmentManager(), true);
                    ((OnNewKeyChainEncryptedListener) activity).onNewKeyChainEncrypted();

                    if (biometricHelper.getRequiresEnabling()) {
                        biometricHelper.runEnableBiometricReminder(requireActivity(), password);
                    }
                }
            }

            @Override
            protected void onBadPassword() {
                unlockButton.setEnabled(true);
                unlockButton.setText(getText(R.string.wallet_lock_unlock));
                pinView.setEnabled(true);
                if(pinRetryController.failedAttempt(password)) {
                    restartService.performRestart(requireActivity(), true, false);
                    dismiss();
                }
                badPinView.setText(getString(R.string.wallet_lock_wrong_pin,
                        pinRetryController.getRemainingAttemptsMessage(getResources())));
                badPinView.setVisibility(View.VISIBLE);
            }
        }.checkPassword(walletProvider.getWallet(), password);
    }

    private void handleDecryptPIN(final String password) {
        if (walletProvider.getWallet().isEncrypted()) {

            if (pinRetryController.isLocked()) {
                return;
            }
            new DeriveKeyTask(backgroundHandler, securityFunctions.getScryptIterationsTarget()) {
                @Override
                protected void onSuccess(final KeyParameter encryptionKey, final boolean wasChanged) {
                    pinRetryController.clearPinFailPrefs();
                    handleDecryptSeed(encryptionKey, password);
                }
            }.deriveKey(walletProvider.getWallet(), password);

        }
    }

    private void handleDecryptSeed(final KeyParameter encryptionKey, final String password) {
        Wallet wallet = walletProvider.getWallet();
        if (wallet.isEncrypted()) {
            if (pinRetryController.isLocked()) {
                return;
            }
            new DecryptSeedTask(backgroundHandler) {
                @Override
                protected void onSuccess(final DeterministicSeed seed) {
                    pinRetryController.clearPinFailPrefs();
                    assert getArguments() != null;
                    ParcelableChainPath parcelableChainPath = getArguments().getParcelable(ARGS_PATH);
                    handleAddKeyChain(seed, parcelableChainPath.getPath(), encryptionKey);
                    walletProvider.onWalletUpgradeComplete(password);
                }

                protected void onBadPassphrase() {
                    // can this happen?
                }
            }.decryptSeed(wallet.getActiveKeyChain().getSeed(), wallet.getKeyCrypter(), encryptionKey);

        }
    }
    protected void handleAddKeyChain(DeterministicSeed seed, ImmutableList<ChildNumber> path, final KeyParameter encryptionKey) {
        DeterministicKeyChain keyChain = DeterministicKeyChain.builder()
                .seed(seed)
                .accountPath(path)
                .build();
        DeterministicKeyChain encryptedKeyChain = keyChain.toEncrypted(walletProvider.getWallet().getKeyCrypter(), encryptionKey);
        walletProvider.getWallet().addAndActivateHDChain(encryptedKeyChain);
    }

    public interface OnNewKeyChainEncryptedListener {
        void onNewKeyChainEncrypted();
    }
}
