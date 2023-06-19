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

import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.InteractionAwareActivity;
import org.dash.wallet.common.WalletDataProvider;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.services.analytics.AnalyticsService;
import org.dash.wallet.common.ui.BaseAlertDialogBuilder;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import org.dash.wallet.common.util.ActivityExtKt;
import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.api.UpholdClientExtKt;
import org.dash.wallet.integration.uphold.data.RequirementsCheckResult;
import org.dash.wallet.integration.uphold.data.UpholdCard;
import org.dash.wallet.integration.uphold.api.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;
import org.dash.wallet.integration.uphold.data.UpholdException;

import java.math.BigDecimal;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import kotlin.Unit;

@AndroidEntryPoint
public class UpholdAccountActivity extends InteractionAwareActivity {

    public static final int REQUEST_CODE_TRANSFER = 1;

    private AlertDialog alertDialog;
    private CurrencyTextView balanceView;
    private BigDecimal balance;
    private String receivingAddress;
    private final MonetaryFormat monetaryFormat = new MonetaryFormat().noCode().minDecimals(8);

    @Inject
    public AnalyticsService analytics;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uphold_account_screen);

        receivingAddress = ((WalletDataProvider) getApplication()).freshReceiveAddress().toBase58();

        balanceView = findViewById(R.id.uphold_account_balance);
        balanceView.setFormat(monetaryFormat);
        balanceView.setApplyMarkup(false);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        if (actionBar != null) {
            actionBar.setTitle(R.string.uphold_account);
        }

        findViewById(R.id.uphold_transfer_to_this_wallet_button).setOnClickListener(v -> {
            if (balance != null && receivingAddress != null) {
                analytics.logEvent(AnalyticsConstants.Uphold.TRANSFER_DASH, Bundle.EMPTY);
                openWithdrawals();
            }
        });
        findViewById(R.id.uphold_buy_dash_btn).setOnClickListener(v -> {
            analytics.logEvent(AnalyticsConstants.Uphold.BUY_DASH, Bundle.EMPTY);
            openBuyDashUrl();
        });

        findViewById(R.id.uphold_logout).setOnClickListener(v -> openLogOutUrl());

    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }
        if(item.getItemId() == R.id.uphold_logout) {
            openLogOutUrl();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        super.turnOnAutoLogout();
        loadUserBalance();
        UpholdClient.getInstance().checkCapabilities();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

    private void loadUserBalance() {
        final ProgressDialog loadingDialog = new ProgressDialog(this);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(getString(R.string.loading));
        loadingDialog.show();

        UpholdClientExtKt.getDashBalance(UpholdClient.getInstance(), new UpholdClient.Callback<BigDecimal>() {
            @Override
            public void onSuccess(BigDecimal data) {
                if (isFinishing()) {
                    return;
                }
                balance = data;
                balanceView.setAmount(Coin.parseCoin(balance.toString()));
                loadingDialog.cancel();
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                if (isFinishing()) {
                    return;
                }
                loadingDialog.cancel();

                if(e instanceof UpholdException) {
                    UpholdException ue = (UpholdException)e;
                    if(ue.getCode() == 401) {
                        //we don't have the correct access token
                        showUpholdBalanceErrorDialog();
                    } else
                        showErrorAlert(ue.getCode());
                } else showErrorAlert(-1);
            }
        });
    }

    private void openBuyDashUrl() {
        UpholdCard dashCard = UpholdClient.getInstance().getCurrentDashCard();
        if (dashCard != null) {
            final String url = String.format(UpholdConstants.CARD_URL_BASE, dashCard.getId());
            openUpholdUrl(url);
        } else {
            showErrorAlert(-1);
        }
    }

    private void openUpholdUrl(String url) {
        ActivityExtKt.openCustomTab(this, url);
        super.turnOffAutoLogout();
    }

    private void showErrorAlert(int code) {
        int messageId = R.string.loading_error;

        if(code == 400 || code == 408 || code >= 500)
            messageId = R.string.uphold_error_not_available;
        if(code == 400 || code == 403 || code >= 400)
                messageId = R.string.uphold_error_report_issue;

        AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.uphold_error),
                getString(messageId),
                getString(android.R.string.ok),
                ""
        ).show(this, result -> Unit.INSTANCE);
    }

    private void openWithdrawals() {
        UpholdWithdrawalHelper.requirementsSatisfied(this, result -> {
            if (result == RequirementsCheckResult.Satisfied) {
                openTransferActivity();
            } else if (result == RequirementsCheckResult.Resolve) {
                openUpholdUrl(UpholdConstants.PROFILE_URL);
            }

            return Unit.INSTANCE;
        });
    }

    private void openTransferActivity() {
        Intent intent = new Intent();
        intent.setClassName(this, "de.schildbach.wallet.ui.send.UpholdTransferActivity");
        intent.putExtra("extra_title", getString(R.string.uphold_account));
        intent.putExtra("extra_message", getString(R.string.uphold_withdrawal_instructions));
        intent.putExtra("extra_max_amount", balance.toString());
        startActivityForResult(intent, REQUEST_CODE_TRANSFER);
    }

    private void revokeAccessToken() {
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.uphold_logout_confirm, null);
        BaseAlertDialogBuilder alertDialogBuilder = new BaseAlertDialogBuilder(this);
        alertDialogBuilder.setTitle(getString(R.string.uphold_logout_title));
        alertDialogBuilder.setPositiveText(getString(R.string.uphold_go_to_website));
        alertDialogBuilder.setPositiveAction(
                () -> {
                    analytics.logEvent(AnalyticsConstants.Uphold.DISCONNECT, Bundle.EMPTY);
                    revokeUpholdAccessToken();
                    return Unit.INSTANCE;
                }
        );
        alertDialogBuilder.setNegativeText(getString(android.R.string.cancel));
        alertDialogBuilder.setView(dialogView);
        alertDialog = alertDialogBuilder.buildAlertDialog();
        alertDialog.show();
    }

    private void revokeUpholdAccessToken() {
        UpholdClient.getInstance().revokeAccessToken(new UpholdClient.Callback<String>() {
            @Override
            public void onSuccess(String result) {
                if (isFinishing()) {
                    return;
                }
                startUpholdSplashActivity();
                openUpholdToLogout();
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                if (isFinishing()) {
                    return;
                }
                if(e instanceof UpholdException) {
                    UpholdException ue = (UpholdException)e;
                    showErrorAlert(ue.getCode());
                } else
                    showErrorAlert(-1);
            }
        });
    }

    private void openUpholdToLogout() {
        final String url = UpholdConstants.LOGOUT_URL;
        ActivityExtKt.openCustomTab(this, url);
        super.turnOffAutoLogout();
    }

    private void openLogOutUrl() {
        //revoke access to the token
        revokeAccessToken();
    }

    private void showUpholdBalanceErrorDialog() {
        AdaptiveDialog dialog = AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.uphold_error),
                getString(R.string.uphold_error_not_logged_in),
                getString(R.string.uphold_link_account),
                ""
        );
        dialog.setCancelable(false);
        dialog.show(this, result -> {
            if (result != null && result) {
                startUpholdSplashActivity();
            }
            return Unit.INSTANCE;
        });
    }

    private void startUpholdSplashActivity() {
        Intent intent = new Intent(this, UpholdSplashActivity.class);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            intent.putExtras(extras);
        }
        startActivity(intent);
        finish();
    }

    @Override
    protected void onPause() {
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }

        super.onPause();
    }
}
