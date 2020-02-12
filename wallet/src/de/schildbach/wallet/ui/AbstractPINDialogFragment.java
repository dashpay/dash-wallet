package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import androidx.annotation.RequiresApi;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.DialogFragment;
import androidx.appcompat.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.DialogFragment;

import org.bitcoinj.wallet.Wallet;
import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet.util.KeyboardUtil;
import de.schildbach.wallet_test.R;

/**
 * Created by Hash Engineering on 4/8/2018.
 */

public abstract class AbstractPINDialogFragment extends DialogFragment {

    protected DialogInterface.OnDismissListener onDismissListener;

    protected WalletProvider walletProvider;
    protected WalletApplication application;
    protected Handler backgroundHandler;
    protected PinRetryController pinRetryController;
    protected FingerprintHelper fingerprintHelper;
    protected CancellationSignal fingerprintCancellationSignal;

    protected EditText pinView;
    protected TextView badPinView;
    protected Button unlockButton;
    protected TextView messageTextView;
    protected FingerprintView fingerprintView;

    protected int dialogLayout;
    protected int dialogTitle;

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            fingerprintHelper = new FingerprintHelper(getActivity());
            if (fingerprintHelper.init()) {
                boolean isFingerprintEnabled = fingerprintHelper.isFingerprintEnabled();
                if (isFingerprintEnabled) {
                    fingerprintView.setVisibility(View.VISIBLE);
                    startFingerprintListener();
                } else {
                    fingerprintView.setText(R.string.touch_fingerprint_to_enable);
                }
            } else {
                fingerprintHelper = null;
            }
        }

        if (walletProvider.getWallet().isEncrypted()) {
            HandlerThread backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }

        final DialogBuilder builder = new DialogBuilder(getContext());
        builder.setTitle(dialogTitle);
        builder.setView(view);
        builder.setCancelable(false);

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pinView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            KeyboardUtil.showSoftKeyboard(getActivity(), pinView);
                        }
                    }, 100);
                } else {
                    KeyboardUtil.showSoftKeyboard(getActivity(), pinView);
                }
            }
        });

        return alertDialog;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void startFingerprintListener() {
        fingerprintCancellationSignal = new CancellationSignal();
        fingerprintHelper.getPassword(fingerprintCancellationSignal, new FingerprintHelper.Callback() {
            @Override
            public void onSuccess(String savedPass) {
                checkPassword(savedPass);
            }

            @Override
            public void onFailure(String message, boolean canceled, boolean exceededMaxAttempts) {
                if (!canceled) {
                    fingerprintView.showError(exceededMaxAttempts);
                }
            }

            @Override
            public void onHelp(int helpCode, String helpString) {
                fingerprintView.showError(false);
            }
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

    public interface WalletProvider {

        Wallet getWallet();

        void onWalletUpgradeComplete(String password);
    }
}
