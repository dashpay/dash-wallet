/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.integrations.uphold.ui;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.integrations.uphold.R;
import org.dash.wallet.integrations.uphold.api.UpholdClient;

import kotlin.Unit;

public class UpholdOtpDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = UpholdOtpDialog.class.getName();
    private OnOtpSetListener onOtpSetListener;

    public static UpholdOtpDialog show(FragmentManager fm, OnOtpSetListener onOtpSetListener) {
        final UpholdOtpDialog dialogFragment = new UpholdOtpDialog();
        dialogFragment.show(fm, FRAGMENT_TAG);
        dialogFragment.onOtpSetListener = onOtpSetListener;
        return dialogFragment;
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        Activity activity = getActivity();
        View view = LayoutInflater.from(activity).inflate(R.layout.uphold_otp_dialog, null);

        final TextView otpCodeView = (TextView) view.findViewById(R.id.otp_code);

        final BaseAlertDialogBuilder upholdOtpAlertDialogBuilder = new BaseAlertDialogBuilder(requireContext());
        upholdOtpAlertDialogBuilder.setTitle(getString(R.string.uphold_otp_dialog_title));
        upholdOtpAlertDialogBuilder.setView(view);
        upholdOtpAlertDialogBuilder.setPositiveText(getString(android.R.string.ok));
        upholdOtpAlertDialogBuilder.setNegativeText(getString(android.R.string.cancel));
        upholdOtpAlertDialogBuilder.setPositiveAction(
                () -> {
                    UpholdClient.getInstance().setOtpToken(otpCodeView.getText().toString());
                    dismiss();
                    if (onOtpSetListener != null) {
                        onOtpSetListener.onOtpSet();
                    }
                    return Unit.INSTANCE;
                }
        );
        upholdOtpAlertDialogBuilder.setCancelableOnTouchOutside(false);

        return upholdOtpAlertDialogBuilder.buildAlertDialog();
    }

    public interface OnOtpSetListener {
        void onOtpSet();
    }

}
