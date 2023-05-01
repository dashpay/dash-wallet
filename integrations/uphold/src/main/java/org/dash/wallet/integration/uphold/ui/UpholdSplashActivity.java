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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.dash.wallet.common.InteractionAwareActivity;
import org.dash.wallet.common.services.analytics.AnalyticsConstants;
import org.dash.wallet.common.services.analytics.AnalyticsService;
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
import org.dash.wallet.common.util.ActivityExtKt;
import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.api.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import kotlin.Unit;

@AndroidEntryPoint
// TODO: move this into IntegrationOverviewFragment
public class UpholdSplashActivity extends InteractionAwareActivity {

    public static final String FINISH_ACTION = "UpholdSplashActivity.FINISH_ACTION";

    public static final String UPHOLD_EXTRA_CODE = "uphold_extra_code";
    public static final String UPHOLD_EXTRA_STATE = "uphold_extra_state";
    public static int taskId = 0;
    @Inject
    public AnalyticsService analytics;
    private ProgressDialog loadingDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uphold_splash_screen);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        if (actionBar != null) {
            actionBar.setTitle("");
        }

        loadingDialog = new ProgressDialog(this);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(getString(R.string.loading));

        findViewById(R.id.uphold_link_account).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                analytics.logEvent(AnalyticsConstants.Uphold.LINK_ACCOUNT, new HashMap<>());
                openLoginUrl();
            }
        });

        handleIntent(getIntent());

        IntentFilter filter = new IntentFilter(FINISH_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(finishLinkReceiver, filter);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null && extras.containsKey(UPHOLD_EXTRA_CODE)
                && extras.containsKey(UPHOLD_EXTRA_STATE)) {
            String code = extras.getString(UPHOLD_EXTRA_CODE);
            String state = extras.getString(UPHOLD_EXTRA_STATE);
            getAccessToken(code, state);
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left);
    }

    private final BroadcastReceiver finishLinkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            startActivity(intent); // this will ensure that the custom tab is closed
        }
    };

    @Override
    protected void onDestroy() {
        loadingDialog.dismiss();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(finishLinkReceiver);
        super.onDestroy();
    }

    private void getAccessToken(String code, String state) {
        if (code != null && UpholdClient.getInstance().getEncryptionKey().equals(state)) {
            loadingDialog.show();
            UpholdClient.getInstance().getAccessToken(code, new UpholdClient.Callback<String>() {
                @Override
                public void onSuccess(String dashCardId) {
                    if (isFinishing()) {
                        return;
                    }
                    loadingDialog.hide();
                    startUpholdAccountActivity();
                }

                @Override
                public void onError(Exception e, boolean otpRequired) {
                    if (isFinishing()) {
                        return;
                    }
                    loadingDialog.hide();
                    showLoadingErrorAlert();
                }
            });
        } else {
            showLoadingErrorAlert();
        }
    }

    private void showLoadingErrorAlert() {
        AdaptiveDialog dialog = AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.error),
                getString(R.string.loading_error),
                getString(android.R.string.ok),
                ""
        );
        dialog.setCancelable(false);
        dialog.show(this, result -> {
            finish();
            return Unit.INSTANCE;
        });
    }

    private void startUpholdAccountActivity() {
        Intent intent = new Intent(this, UpholdAccountActivity.class);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            intent.putExtras(extras);
        }
        startActivity(intent);
        finish();
    }

    private void openLoginUrl() {
        final String url = String.format(UpholdConstants.INITIAL_URL,
                UpholdClient.getInstance().getEncryptionKey());
        ActivityExtKt.openCustomTab(this, url);
        super.turnOffAutoLogout();
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
    public void onResume() {
        super.onResume();
        super.turnOnAutoLogout();
    }
}
