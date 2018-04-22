package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
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

import org.bitcoinj.wallet.Wallet;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.preference.PinRetryController;
import de.schildbach.wallet.util.KeyboardUtil;
import de.schildbach.wallet_test.R;

/**
 * Created by Hash Engineering on 4/8/2018.
 */

public abstract class AbstractPINDialogFragment extends DialogFragment {

    protected DialogInterface.OnDismissListener onDismissListener;

    protected AbstractWalletActivity activity;
    protected WalletApplication application;
    protected Handler backgroundHandler;
    protected Wallet wallet;
    protected PinRetryController pinRetryController;

    protected EditText pinView;
    protected TextView badPinView;
    protected Button unlockButton;
    protected TextView messageTextView;

    protected int dialogLayout;
    protected int dialogTitle;

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
        final View view = LayoutInflater.from(activity).inflate(dialogLayout, null);

        pinView = (EditText) view.findViewById(R.id.pin);
        badPinView = (TextView) view.findViewById(R.id.bad_pin);
        unlockButton = (Button) view.findViewById(R.id.unlock);
        messageTextView = (TextView) view.findViewById(R.id.new_key_chain_dialog_message_text);

        pinView.addTextChangedListener(privateKeyPasswordListener);
        unlockButton.setOnClickListener(new View.OnClickListener() {
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
        builder.setTitle(dialogTitle);
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

    abstract protected void checkPassword(final String password);
}
