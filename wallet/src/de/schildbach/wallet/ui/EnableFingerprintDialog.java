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

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import de.schildbach.wallet.security.FingerprintHelper;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet_test.R;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
public class EnableFingerprintDialog extends DialogFragment {

    private FingerprintView fingerprintView;
    private CancellationSignal fingerprintCancellationSignal;
    private AlertDialog alertDialog;
    @Inject public FingerprintHelper fingerprintHelper;
    @Inject public Configuration configuration;

    private static final String PASSWORD_ARG = "fingerprint_password";
    private final Function1<String, Unit> onSuccessOrDismiss;


    public EnableFingerprintDialog() {
        onSuccessOrDismiss = null;
    }

    public EnableFingerprintDialog(Function1<String, Unit> callback) {
        onSuccessOrDismiss = callback;
    }

    public static EnableFingerprintDialog show(String password, FragmentActivity activity, Function1<String, Unit> callback) {
        EnableFingerprintDialog enableFingerprintDialog = new EnableFingerprintDialog(callback);

        Bundle args = new Bundle();
        args.putString(PASSWORD_ARG, password);

        enableFingerprintDialog.setArguments(args);
        enableFingerprintDialog.show(activity.getSupportFragmentManager(), EnableFingerprintDialog.class.getSimpleName());
        return enableFingerprintDialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = getLayoutInflater()
                .inflate(R.layout.fingerprint_dialog, null);
        fingerprintView = view.findViewById(R.id.fingerprint_view);
        fingerprintView.setVisibility(View.VISIBLE);
        fingerprintView.setText(R.string.touch_fingerprint_to_enable);

        if (fingerprintHelper.isAvailable()) {
            fingerprintCancellationSignal = new CancellationSignal();
            fingerprintHelper.savePassword(requireActivity(), getArguments().getString(PASSWORD_ARG),
                    fingerprintCancellationSignal, new FingerprintHelper.Callback() {
                        @Override
                        public void onSuccess(String savedPass) {
                            fingerprintCancellationSignal = null;
                            fingerprintHelper.resetFingerprintKeyChanged();

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

        BaseAlertDialogBuilder baseAlertDialogBuilder = new BaseAlertDialogBuilder(requireContext());
        baseAlertDialogBuilder.setTitle(getString(R.string.enable_fingerprint));
        baseAlertDialogBuilder.setView(view);
        baseAlertDialogBuilder.setPositiveText(getString(R.string.notification_inactivity_action_dismiss));
        baseAlertDialogBuilder.setNegativeText(getString(R.string.notification_inactivity_action_dismiss_forever));
        baseAlertDialogBuilder.setCancelable(false);
        baseAlertDialogBuilder.setNegativeAction(
                () -> {
                    configuration.setRemindEnableFingerprint(false);
                    return Unit.INSTANCE;
                }
        );
        baseAlertDialogBuilder.setCancelableOnTouchOutside(false);
        alertDialog = baseAlertDialogBuilder.buildAlertDialog();

        return alertDialog;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }

        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }

        this.alertDialog = null;
        Bundle args = getArguments();

        if (onSuccessOrDismiss != null && args != null) {
            onSuccessOrDismiss.invoke(args.getString(PASSWORD_ARG));
        }

        super.onDismiss(dialog);
    }
}
