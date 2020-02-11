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
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.CancellationSignal;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;

import org.dash.wallet.common.ui.DialogBuilder;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.widget.FingerprintView;
import de.schildbach.wallet.util.FingerprintHelper;
import de.schildbach.wallet_test.R;
import kotlin.Pair;

/**
 * @author Samuel Barbosa
 */
public class EnableFingerprintDialog extends DialogFragment {

    private FingerprintView fingerprintView;
    private CancellationSignal fingerprintCancellationSignal;

    private CheckPinSharedModel sharedModel;

    private static final String PASSWORD_ARG = "fingerprint_password";
    private static final String ARG_REQUEST_CODE = "arg_request_code";

    public static boolean shouldBeShown(Activity activity) {
        FingerprintHelper fingerprintHelper = new FingerprintHelper(activity);
        boolean remindEnableFingerprint = WalletApplication.getInstance().getConfiguration().getRemindEnableFingerprint();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintHelper.init() && !fingerprintHelper.isFingerprintEnabled() && remindEnableFingerprint;
    }

    public static EnableFingerprintDialog show(String password, FragmentManager fragmentManager) {
        return show(password, 0, fragmentManager);
    }

    public static EnableFingerprintDialog show(String password, int requestCode, FragmentManager fragmentManager) {
        EnableFingerprintDialog enableFingerprintDialog = new EnableFingerprintDialog();

        Bundle args = new Bundle();
        args.putString(PASSWORD_ARG, password);
        args.putInt(ARG_REQUEST_CODE, requestCode);

        enableFingerprintDialog.setArguments(args);
        enableFingerprintDialog.show(fragmentManager, EnableFingerprintDialog.class.getSimpleName());
        return enableFingerprintDialog;
    }

    @NonNull
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
        fingerprintView.setText(R.string.touch_fingerprint_to_enable);

        final FingerprintHelper fingerprintHelper = new FingerprintHelper(getActivity());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && fingerprintHelper.init()) {
            fingerprintCancellationSignal = new CancellationSignal();
            fingerprintHelper.savePassword(getArguments().getString(PASSWORD_ARG),
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

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        if (fingerprintCancellationSignal != null) {
            fingerprintCancellationSignal.cancel();
        }

        Bundle args = getArguments();
        int requestCode = args != null ? args.getInt(ARG_REQUEST_CODE) : 0;
        sharedModel.getOnCorrectPinCallback().setValue(new Pair<>(requestCode, getArguments().getString(PASSWORD_ARG)));

        super.onDismiss(dialog);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (getActivity() != null) {
            sharedModel = ViewModelProviders.of(getActivity()).get(CheckPinSharedModel.class);
        } else {
            throw new IllegalStateException("Invalid Activity");
        }
    }
}
