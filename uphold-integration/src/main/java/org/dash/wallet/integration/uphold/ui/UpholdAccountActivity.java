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

import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.dash.wallet.common.ui.DialogBuilder;
import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdClient;

import java.math.BigDecimal;

public class UpholdAccountActivity extends AppCompatActivity {

    public static final String WALLET_RECEIVING_ADDRESS_EXTRA = "uphold_receiving_address_extra";

    private CurrencyTextView balanceView;
    private BigDecimal balance;
    private String receivingAddress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uphold_account_screen);

        receivingAddress = getIntent().getStringExtra(WALLET_RECEIVING_ADDRESS_EXTRA);
        if (receivingAddress == null) {
            finish();
        }

        balanceView = findViewById(R.id.uphold_account_balance);
        balanceView.setFormat(new MonetaryFormat().noCode());

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

        findViewById(R.id.uphold_transfer_to_this_wallet_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (balance != null && receivingAddress != null) {
                    showWithdrawalDialog();
                }
            }
        });
        findViewById(R.id.uphold_buy_dash_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWebViewActivity();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUserBalance();
    }

    private void showWebViewActivity() {
        Intent intent = new Intent(this, UpholdWebViewActivity.class);
        intent.putExtra(UpholdWebViewActivity.BUYING_EXTRA, true);
        startActivity(intent);
    }

    private void loadUserBalance() {
        final ProgressDialog loadingDialog = new ProgressDialog(this);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(getString(R.string.loading));
        loadingDialog.show();

        UpholdClient.getInstance().getDashBalance(new UpholdClient.Callback<BigDecimal>() {
            @Override
            public void onSuccess(BigDecimal data) {
                balance = data;
                balanceView.setAmount(Coin.parseCoin(balance.toString()));
                loadingDialog.cancel();
            }

            @Override
            public void onError(Exception e, boolean otpRequired) {
                loadingDialog.cancel();
                showErrorAlert();
            }
        });
    }

    private void showErrorAlert() {
        new DialogBuilder(this).setMessage(R.string.loading_error).show();
    }

    private void showWithdrawalDialog() {
        Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this), getResources());
        String currencyCode = config.getFormat().code();
        MonetaryFormat inputFormat = config.getMaxPrecisionFormat();
        MonetaryFormat hintFormat = config.getFormat();

        UpholdWithdrawalDialog.show(getFragmentManager(), balance,
                receivingAddress, currencyCode, inputFormat, hintFormat,
                new UpholdWithdrawalDialog.OnTransferListener() {
                    @Override
                    public void onTransfer() {
                        loadUserBalance();
                    }
                });
    }

}
