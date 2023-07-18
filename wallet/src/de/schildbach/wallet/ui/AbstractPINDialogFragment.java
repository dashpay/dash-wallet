package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.common.util.KeyboardUtil;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.security.BiometricHelper;
import de.schildbach.wallet.security.BiometricLockoutException;
import de.schildbach.wallet.security.PinRetryController;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet_test.R;
import kotlin.Unit;

/**
 * Created by Hash Engineering on 4/8/2018.
 */
@AndroidEntryPoint
public abstract class AbstractPINDialogFragment extends DialogFragment {

    protected DialogInterface.OnDismissListener onDismissListener;

    protected WalletProvider walletProvider;
    protected WalletApplication application;
    protected Handler backgroundHandler;
    protected PinRetryController pinRetryController;

    protected EditText pinView;
    protected TextView badPinView;
    protected Button unlockButton;
    protected TextView messageTextView;
    protected FingerprintView fingerprintView;

    protected int dialogLayout;
    protected int dialogTitle;

    @Inject public BiometricHelper biometricHelper;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.walletProvider = (WalletProvider) activity;
        this.application = (WalletApplication) activity.getApplication();
        this.pinRetryController = PinRetryController.getInstance();
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getContext()).inflate(dialogLayout, null);

        pinView = (EditText) view.findViewById(R.id.pin);
        badPinView = (TextView) view.findViewById(R.id.bad_pin);
        unlockButton = (Button) view.findViewById(R.id.unlock);
        messageTextView = (TextView) view.findViewById(R.id.new_key_chain_dialog_message_text);
        fingerprintView = view.findViewById(R.id.fingerprint_view);

        pinView.addTextChangedListener(privateKeyPasswordListener);
        unlockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPassword(pinView.getText().toString());
            }
        });

        if (biometricHelper.isAvailable()) {
            boolean isFingerprintEnabled = biometricHelper.isEnabled();
            if (isFingerprintEnabled) {
                fingerprintView.setVisibility(View.VISIBLE);
                startFingerprintListener();
            } else {
                fingerprintView.setText(R.string.touch_fingerprint_to_enable);
            }
        }

        if (walletProvider.getWallet().isEncrypted()) {
            HandlerThread backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }

        final BaseAlertDialogBuilder abstractPinAlertDialogBuilder = new BaseAlertDialogBuilder(requireContext());
        abstractPinAlertDialogBuilder.setTitle(getString(dialogTitle));
        abstractPinAlertDialogBuilder.setView(view);
        abstractPinAlertDialogBuilder.setCancelable(false);
        final AlertDialog alertDialog = abstractPinAlertDialogBuilder.buildAlertDialog();
        alertDialog.setOnShowListener(dialog -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pinView.postDelayed(() -> KeyboardUtil.Companion.showSoftKeyboard(getActivity(), pinView), 100);
            } else {
                KeyboardUtil.Companion.showSoftKeyboard(getActivity(), pinView);
            }
        });

        return alertDialog;
    }

    private void startFingerprintListener() {
        biometricHelper.getPassword(requireActivity(), true, (savedPass, error) -> {
            if (error != null) {
                fingerprintView.showError(error instanceof BiometricLockoutException);
            } else if (savedPass != null) {
                checkPassword(savedPass);
            }

            return Unit.INSTANCE;
        });
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

    abstract protected void checkPassword(final String password);

    // TODO: this needs better handling as it can be confused with the WalletDataProvider.
    // Perhaps keep the walletBuffer from the activity in the RestoreWalletFromFileViewModel
    // and share it across fragments
    public interface WalletProvider {

        Wallet getWallet();

        void onWalletUpgradeComplete(String password);
    }
}
