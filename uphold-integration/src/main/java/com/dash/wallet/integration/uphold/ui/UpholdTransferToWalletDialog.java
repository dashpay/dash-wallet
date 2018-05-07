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

package com.dash.wallet.integration.uphold.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.dash.wallet.integration.R;
import com.dash.wallet.integration.uphold.data.UpholdClient;
import com.dash.wallet.integration.uphold.data.UpholdTransaction;

import org.dash.wallet.common.ui.CurrencyAmountView;
import org.dash.wallet.common.ui.DialogBuilder;

import java.math.BigDecimal;


public class UpholdTransferToWalletDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = UpholdTransferToWalletDialog.class.getName();
    private static final String ARGS_BALANCE = "args_balance";
    private static final String ARGS_RECEIVING_ADDRESS = "args_receiving_address";

    private Button transferButton;
    private BigDecimal balance;
    private OnTransferListener onTransferListener;
    private String receivingAddress;
    private UpholdTransaction transaction;

    public static void show(final FragmentManager fm, BigDecimal balance, String receivingAddress,
                            OnTransferListener onTransferListener) {
        final UpholdTransferToWalletDialog dialogFragment = new UpholdTransferToWalletDialog();
        dialogFragment.onTransferListener = onTransferListener;

        Bundle args = new Bundle();
        args.putSerializable(ARGS_BALANCE, balance);
        args.putSerializable(ARGS_RECEIVING_ADDRESS, receivingAddress);
        dialogFragment.setArguments(args);

        dialogFragment.show(fm, FRAGMENT_TAG);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        Activity activity = getActivity();
        View view = LayoutInflater.from(activity).inflate(R.layout.transfer_from_external_account_dialog, null);
        balance = (BigDecimal) getArguments().getSerializable(ARGS_BALANCE);
        receivingAddress = (String) getArguments().getSerializable(ARGS_RECEIVING_ADDRESS);

        final CurrencyAmountView dashAmountView = (CurrencyAmountView) view.findViewById(R.id.send_coins_amount_dash);
        //TODO: pass as argument
        /* * /
        dashAmountView.setCurrencySymbol(config.getFormat().code());
        dashAmountView.setInputFormat(config.getMaxPrecisionFormat());
        dashAmountView.setHintFormat(config.getFormat());
        dashAmountView.getTextView().setText(balance.toString());
        dashAmountView.getTextView().addTextChangedListener(dashAmountTextWatcher);
        /* */

        TextView hintView = (TextView) view.findViewById(R.id.hint);
        hintView.setText(Html.fromHtml(getString(R.string.dash_available, balance)));
        hintView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dashAmountView.getTextView().setText(balance.toString());
            }
        });

        DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.uphold_transfer_from_external_account_title);
        builder.setView(view);
        //click listener is set directly below to prevent dialog from being dismissed
        builder.setPositiveButton(R.string.uphold_transfer, null);
        builder.setNegativeButton(android.R.string.cancel, null);

        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                transferButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                transferButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (transaction == null) {
                            transfer(new BigDecimal(dashAmountView.getTextView().getText().toString()));
                        } else {
                            showCommitTransactionConfirmationDialog();
                        }
                    }
                });
            }
        });
        return dialog;
    }

    private TextWatcher dashAmountTextWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (s.length() == 0) {
                transferButton.setEnabled(false);
                return;
            }
            BigDecimal value = new BigDecimal(s.toString());
            boolean valid = value.compareTo(BigDecimal.ZERO) == 1 && value.compareTo(balance) <= 0;
            transferButton.setEnabled(valid);
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

    private void transfer(BigDecimal value) {
        final ProgressDialog progressDialog = showLoading();
        UpholdClient.getInstance(getActivity()).createDashWithdrawalTransaction(value.toString(),
                receivingAddress, new UpholdClient.Callback<UpholdTransaction>() {
                    @Override
                    public void onSuccess(UpholdTransaction tx) {
                        transaction = tx;
                        progressDialog.dismiss();
                        showCommitTransactionConfirmationDialog();
                    }

                    @Override
                    public void onError(Exception e, boolean otpRequired) {
                        progressDialog.dismiss();
                        if (otpRequired) {
                            showOtpDialog();
                        } else {
                            showLoadingError();
                        }
                    }
                });
    }

    private void showCommitTransactionConfirmationDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        dialogBuilder.setTitle(R.string.uphold_transfer_from_external_account_confirm_title);
        dialogBuilder.setMessage(getString(R.string.uphold_transfer_from_external_account_confirm_message,
                transaction.getOrigin().getAmount(), transaction.getOrigin().getBase(),
                transaction.getOrigin().getFee(), "Uphold"));
        dialogBuilder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                commitUpholdTransaction();
            }
        });

        dialogBuilder.setNegativeButton(android.R.string.no, null);
        dialogBuilder.show();
    }

    private void commitUpholdTransaction() {
        final ProgressDialog progressDialog = showLoading();
        UpholdClient.getInstance(getActivity()).commitTransaction(transaction.getId(), new UpholdClient.Callback<Object>() {
            @Override
            public void onSuccess(Object data) {
                if (onTransferListener != null) {
                    onTransferListener.onTransfer();
                }
                progressDialog.dismiss();
                dismiss();
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                progressDialog.dismiss();
                if (otpRequired) {
                    showOtpDialog();
                } else {
                    showLoadingError();
                }
            }
        });
    }

    private void showOtpDialog() {
        UpholdOtpDialog.show(getFragmentManager(), new UpholdOtpDialog.OnOtpSetListener() {
            @Override
            public void onOtpSet() {
                if (transaction != null) {
                    commitUpholdTransaction();
                }
            }
        });
    }

    private ProgressDialog showLoading() {
        ProgressDialog progressDialog = new ProgressDialog(getActivity());
        progressDialog.setIndeterminate(true);
        progressDialog.setMessage(getString(R.string.loading));
        progressDialog.show();
        return progressDialog;
    }

    private void showLoadingError() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setMessage(R.string.loading_error);
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    public interface OnTransferListener {
        void onTransfer();
    }

}
