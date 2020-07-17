/*
 * Copyright 2019 Dash Core Group
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

package org.dash.wallet.integration.uphold.ui;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdApiException;
import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integration.uphold.data.UpholdTransaction;

import java.math.BigDecimal;


public class UpholdWithdrawalHelper {

    private BigDecimal balance;
    private OnTransferListener onTransferListener;
    private UpholdTransaction transaction;

    public UpholdWithdrawalHelper(BigDecimal balance, OnTransferListener onTransferListener) {
        this.balance = balance;
        this.onTransferListener = onTransferListener;
    }

    public void transfer(final AppCompatActivity activity, final String receivingAddress, BigDecimal value, final boolean deductFeeFromAmount) {
        final ProgressDialog progressDialog = showLoading(activity);
        UpholdClient.getInstance().createDashWithdrawalTransaction(value.toPlainString(),
                receivingAddress, new UpholdClient.Callback<UpholdTransaction>() {
                    @Override
                    public void onSuccess(UpholdTransaction tx) {
                        transaction = tx;
                        progressDialog.dismiss();
                        showCommitTransactionConfirmationDialog(activity, receivingAddress);
                    }

                    @Override
                    public void onError(Exception e, boolean otpRequired) {
                        progressDialog.dismiss();
                        if (otpRequired) {
                            showOtpDialog(activity);
                        } else {
                            showLoadingError(activity, e);
                        }
                    }
                });
    }

    @SuppressLint("SetTextI18n")
    private void showCommitTransactionConfirmationDialog(final AppCompatActivity activity, String receivingAddress) {
        BigDecimal fee = transaction.getOrigin().getFee();
        final BigDecimal total = transaction.getOrigin().getAmount();

        if (total.compareTo(balance) > 0) {
            transfer(activity, receivingAddress, balance.subtract(fee), true);
            return;
        }

        this.onTransferListener.onConfirm(transaction);
    }

    public void commitTransaction(final AppCompatActivity activity) {
        final ProgressDialog progressDialog = showLoading(activity);
        final String txId = transaction.getId();
        UpholdClient.getInstance().commitTransaction(txId, new UpholdClient.Callback<Object>() {
            @Override
            public void onSuccess(Object data) {
                progressDialog.dismiss();
                showSuccessDialog(activity, txId);
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                progressDialog.dismiss();
                if (otpRequired) {
                    showOtpDialog(activity);
                } else {
                    showLoadingError(activity, e);
                }
            }
        });
    }

    private void showSuccessDialog(final AppCompatActivity activity, final String txId) {
        DialogBuilder dialogBuilder = new DialogBuilder(activity);
        dialogBuilder.setTitle(R.string.uphold_withdrawal_success_title);
        dialogBuilder.setMessage(activity.getString(R.string.uphold_withdrawal_success_message, txId));
        dialogBuilder.setPositiveButton(android.R.string.ok, null);
        dialogBuilder.setNeutralButton(R.string.uphold_see_on_uphold, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String txUrl = String.format(UpholdConstants.TRANSACTION_URL, txId);
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(txUrl)));
            }
        });
        dialogBuilder.show().setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                dialog.dismiss();
                if (onTransferListener != null) {
                    onTransferListener.onTransfer();
                }
            }
        });
    }

    private void showOtpDialog(final AppCompatActivity activity) {
        UpholdOtpDialog.show(activity.getSupportFragmentManager(), new UpholdOtpDialog.OnOtpSetListener() {
            @Override
            public void onOtpSet() {
                if (transaction != null) {
                    commitTransaction(activity);
                }
            }
        });
    }

    private ProgressDialog showLoading(AppCompatActivity activity) {
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setIndeterminate(true);
        progressDialog.setMessage(activity.getString(R.string.loading));
        progressDialog.show();
        return progressDialog;
    }

    private void showLoadingError(AppCompatActivity activity, Exception e) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        if (e instanceof UpholdApiException) {
            builder.setTitle(R.string.uphold_api_error_title);
            UpholdApiException upholdApiException = (UpholdApiException) e;
            String availableAt = null;
            if (upholdApiException.hasError(UpholdApiException.LOCKED_FUNDS_KEY)) {
                availableAt = upholdApiException.getErrorArg(UpholdApiException.AVAILABLE_AT_KEY);
            }
            builder.setMessage(upholdApiException.getDescription(activity, availableAt));
        } else {
            builder.setTitle(R.string.uphold_general_error_title);
            builder.setMessage(R.string.loading_error);
        }
        builder.setPositiveButton(android.R.string.ok, null);
        builder.show();
    }

    public interface OnTransferListener {

        void onConfirm(UpholdTransaction transaction);

        void onTransfer();
    }
}
