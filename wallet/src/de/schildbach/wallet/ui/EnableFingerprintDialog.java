package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.os.CancellationSignal;
import android.view.LayoutInflater;
import android.view.View;

import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class EnableFingerprintDialog extends DialogFragment {

    private FingerprintView fingerprintView;
    private CancellationSignal fingerprintCancellationSignal;

    private static final String PASSWORD_ARG = "fingerprint_password";

    public static EnableFingerprintDialog show(String password, FragmentManager fragmentManager) {
        EnableFingerprintDialog enableFingerprintDialog = new EnableFingerprintDialog();

        Bundle args = new Bundle();
        args.putString(PASSWORD_ARG, password);

        enableFingerprintDialog.setArguments(args);
        enableFingerprintDialog.show(fragmentManager, EnableFingerprintDialog.class.getSimpleName());
        return enableFingerprintDialog;
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final Activity activity = getActivity();
        final View view = LayoutInflater.from(activity)
                .inflate(R.layout.fingerprint_dialog, null);

        DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.enable_fingerprint);
        builder.setView(view);
        builder.setPositiveButton(R.string.notification_inactivity_action_dismiss, null);
        builder.setNegativeButton(R.string.notification_inactivity_action_dismiss_forever, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                WalletApplication.getInstance().getConfiguration().setRemindEnableFingerprint(false);
            }
        });
        builder.setCancelable(false);

        FingerprintHelper fingerprintHelper = new FingerprintHelper(getActivity());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintHelper.init()) {
            fingerprintCancellationSignal = new CancellationSignal();
            fingerprintHelper.savePassword(getArguments().getString(PASSWORD_ARG),
                    fingerprintCancellationSignal, new FingerprintHelper.Callback() {
                        @Override
                        public void onSuccess(String savedPass) {
                            fingerprintCancellationSignal = null;

                            if (activity != null && activity instanceof OnFingerprintEnabledListener) {
                                ((OnFingerprintEnabledListener) activity).onFingerprintEnabled();
                            }

                            dismiss();
                        }

                        @Override
                        public void onFailure(String message, boolean canceled) {
                            if (!canceled) {
                                fingerprintView.showError();
                            }
                        }

                        @Override
                        public void onHelp(int helpCode, String helpString) {
                            fingerprintView.showError();
                        }
                    });
        } else {
            dismiss();
        }

        fingerprintView = view.findViewById(R.id.fingerprint_view);
        fingerprintView.setVisibility(View.VISIBLE);
        fingerprintView.hideSeparator();
        fingerprintView.setText(R.string.touch_fingerprint_to_enable);

        return builder.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }
        super.onDismiss(dialog);
    }

    public interface OnFingerprintEnabledListener {
        void onFingerprintEnabled();
    }

}
