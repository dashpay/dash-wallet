/*
 * Copyright 2018 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;

import androidx.core.os.CancellationSignal;
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
    private static final String ONBOARDING_ARG = "onboarding";

    public static EnableFingerprintDialog show(String password, boolean onboarding, FragmentManager fragmentManager) {
        EnableFingerprintDialog enableFingerprintDialog = new EnableFingerprintDialog();

        Bundle args = new Bundle();
        args.putString(PASSWORD_ARG, password);
        args.putBoolean(ONBOARDING_ARG, onboarding);

        enableFingerprintDialog.setArguments(args);
        enableFingerprintDialog.show(fragmentManager, EnableFingerprintDialog.class.getSimpleName());
        return enableFingerprintDialog;
    }

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

        fingerprintView = view.findViewById(R.id.fingerprint_view);
        fingerprintView.setVisibility(View.VISIBLE);
        fingerprintView.hideSeparator();
        fingerprintView.setText(R.string.touch_fingerprint_to_enable);

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
        } else {
            dismiss();
        }

        return builder.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }

        Activity activity = getActivity();
        Bundle args = getArguments();
        boolean onboarding = args != null && args.getBoolean(ONBOARDING_ARG);
        if (onboarding && activity instanceof EncryptKeysDialogFragment.OnOnboardingCompleteListener) {
            ((EncryptKeysDialogFragment.OnOnboardingCompleteListener) activity).onOnboardingComplete();
        }
        super.onDismiss(dialog);
    }

    public interface OnFingerprintEnabledListener {
        void onFingerprintEnabled();
    }

}
