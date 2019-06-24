/*
 * Copyright 2015-present the original author or authors.
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AlertDialog;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integration.uphold.data.UpholdTransaction;

import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.ui.CurrencyAmountView;
import org.dash.wallet.common.ui.DialogBuilder;

import java.math.BigDecimal;


public class UpholdWithdrawalDialog extends DialogFragment {

    private static final String FRAGMENT_TAG = UpholdWithdrawalDialog.class.getName();

    private BigDecimal balance;
    private String receivingAddress;
    private String currencyCode;
    private MonetaryFormat inputFormat;
    private MonetaryFormat hintFormat;
    private OnTransferListener onTransferListener;
    private Button transferButton;
    private UpholdTransaction transaction;

    public static void show(final FragmentManager fm,
                            BigDecimal balance,
                            String receivingAddress,
                            String currencyCode,
                            MonetaryFormat inputFormat,
                            MonetaryFormat hintFormat,
                            OnTransferListener onTransferListener) {

        final UpholdWithdrawalDialog dialog = new UpholdWithdrawalDialog();

        dialog.balance = balance;
        dialog.receivingAddress = receivingAddress;
        dialog.currencyCode = currencyCode;
        dialog.inputFormat = inputFormat;
        dialog.hintFormat = hintFormat;
        dialog.onTransferListener = onTransferListener;

        dialog.show(fm, FRAGMENT_TAG);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        setRetainInstance(true);
        Activity activity = getActivity();
        View view = LayoutInflater.from(activity).inflate(R.layout.uphold_withdrawal_dialog, null);

        final CurrencyAmountView dashAmountView = view.findViewById(R.id.send_coins_amount_dash);
        dashAmountView.setCurrencySymbol(currencyCode);
        dashAmountView.setInputFormat(inputFormat);
        dashAmountView.setHintFormat(hintFormat);
        dashAmountView.getTextView().setText(balance.toString());
        dashAmountView.getTextView().addTextChangedListener(dashAmountTextWatcher);

        TextView hintView = view.findViewById(R.id.hint);
        hintView.setText(Html.fromHtml(getString(R.string.dash_available, balance)));
        hintView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dashAmountView.getTextView().setText(balance.toString());
            }
        });

        DialogBuilder builder = new DialogBuilder(activity);
        builder.setTitle(R.string.uphold_withdrawal_instructions);
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
                        transfer(new BigDecimal(dashAmountView.getTextView().getText().toString()),
                                false);
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

            String textValue = s.toString();
            if (textValue.substring(0, 1).equals(".")) {
                textValue = "0" + textValue;
            }

            BigDecimal value = new BigDecimal(textValue);
            boolean valid = value.compareTo(BigDecimal.ZERO) == 1 && value.compareTo(balance) <= 0;
            transferButton.setEnabled(valid);
        }

        @Override
        public void afterTextChanged(Editable s) {

        }
    };

    private void transfer(BigDecimal value, final boolean deductFeeFromAmount) {
        final ProgressDialog progressDialog = showLoading();
        UpholdClient.getInstance().createDashWithdrawalTransaction(value.toPlainString(),
                receivingAddress, new UpholdClient.Callback<UpholdTransaction>() {
                    @Override
                    public void onSuccess(UpholdTransaction tx) {
                        transaction = tx;
                        progressDialog.dismiss();
                        showCommitTransactionConfirmationDialog(deductFeeFromAmount);
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

    @SuppressLint("SetTextI18n")
    private void showCommitTransactionConfirmationDialog(boolean deductFeeFromAmount) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(getActivity());
        dialogBuilder.setTitle(R.string.uphold_withdrawal_confirm_title);

        View contentView = LayoutInflater.from(getActivity())
                .inflate(R.layout.uphold_confirm_transaction_dialog, null);
        TextView amountTxt = contentView.findViewById(R.id.uphold_withdrawal_amount);
        TextView feeTxt = contentView.findViewById(R.id.uphold_withdrawal_fee);
        TextView totalTxt = contentView.findViewById(R.id.uphold_withdrawal_total);
        View deductFeeDisclaimer = contentView.findViewById(R.id.uphold_withdrawal_confirmation_fee_deduction_disclaimer);

        BigDecimal fee = transaction.getOrigin().getFee();
        final BigDecimal baseAmount = transaction.getOrigin().getBase();
        final BigDecimal total = transaction.getOrigin().getAmount();

        if (total.compareTo(balance) > 0) {
            transfer(balance.subtract(fee), true);
            return;
        }

        amountTxt.setText(baseAmount.toPlainString());
        feeTxt.setText(fee.toPlainString());
        totalTxt.setText(total.toPlainString());

        if (deductFeeFromAmount) {
            deductFeeDisclaimer.setVisibility(View.VISIBLE);
        }

        dialogBuilder.setView(contentView);
        dialogBuilder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                commitTransaction();
            }
        });

        dialogBuilder.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                transaction = null;
            }
        });
        dialogBuilder.setCancelable(false);
        dialogBuilder.show();
    }

    private void commitTransaction() {
        final ProgressDialog progressDialog = showLoading();
        final String txId = transaction.getId();
        UpholdClient.getInstance().commitTransaction(txId, new UpholdClient.Callback<Object>() {
            @Override
            public void onSuccess(Object data) {
                if (onTransferListener != null) {
                    onTransferListener.onTransfer();
                }
                progressDialog.dismiss();
                showSuccessDialog(txId);
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

    private void showSuccessDialog(final String txId) {
        DialogBuilder dialogBuilder = new DialogBuilder(getActivity());
        dialogBuilder.setTitle(R.string.uphold_withdrawal_success_title);
        dialogBuilder.setMessage(getString(R.string.uphold_withdrawal_success_message, txId));
        dialogBuilder.setPositiveButton(android.R.string.ok, null);
        dialogBuilder.setNeutralButton(R.string.uphold_see_on_uphold, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String txUrl = String.format(UpholdConstants.TRANSACTION_URL, txId);
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)));
            }
        });
        dialogBuilder.show().setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                dismiss();
            }
        });
    }

    private void showOtpDialog() {
        UpholdOtpDialog.show(getFragmentManager(), new UpholdOtpDialog.OnOtpSetListener() {
            @Override
            public void onOtpSet() {
                if (transaction != null) {
                    commitTransaction();
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
