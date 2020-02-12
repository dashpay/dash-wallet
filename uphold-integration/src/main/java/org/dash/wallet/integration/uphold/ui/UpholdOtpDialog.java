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

package org.dash.wallet.integration.uphold.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdClient;

import org.dash.wallet.common.ui.DialogBuilder;

public class UpholdOtpDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = UpholdOtpDialog.class.getName();
    private Button okButton;
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

        DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.uphold_otp_dialog_title);
        builder.setView(view);
        //click listener is set directly below to prevent dialog from being dismissed
        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, null);

        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                okButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                okButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        //Set Otp Code and make request
                        UpholdClient.getInstance().setOtpToken(otpCodeView.getText().toString());
                        dismiss();
                        if (onOtpSetListener != null) {
                            onOtpSetListener.onOtpSet();
                        }
                    }
                });
            }
        });
        return dialog;
    }

    public interface OnOtpSetListener {
        void onOtpSet();
    }

}
