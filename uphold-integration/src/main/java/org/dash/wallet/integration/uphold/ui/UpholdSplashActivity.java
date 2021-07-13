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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.dash.wallet.common.InteractionAwareActivity;
import org.dash.wallet.common.customtabs.CustomTabActivityHelper;
import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;


public class UpholdSplashActivity extends InteractionAwareActivity {

    public static final String FINISH_ACTION = "UpholdSplashActivity.FINISH_ACTION";

    public static final String UPHOLD_EXTRA_CODE = "uphold_extra_code";
    public static final String UPHOLD_EXTRA_STATE = "uphold_extra_state";
    public static int taskId = 0;

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
            actionBar.setTitle(R.string.uphold_link_account);
        }

        loadingDialog = new ProgressDialog(this);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(getString(R.string.loading));

        findViewById(R.id.uphold_link_account).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setMessage(R.string.loading_error);
        builder.setPositiveButton(android.R.string.ok, null);
        Dialog dialog = builder.show();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finish();
            }
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
        //Intent intent = new Intent(this, CustomTabActivity.class);
        //startActivity(intent);
        final String url = String.format(UpholdConstants.INITIAL_URL,
                UpholdClient.getInstance().getEncryptionKey());

        CustomTabsIntent.Builder builder = new CustomTabsIntent.Builder();
        int toolbarColor = ContextCompat.getColor(this, R.color.colorPrimary);
        CustomTabsIntent customTabsIntent = builder.setShowTitle(true)
                .setToolbarColor(toolbarColor).build();

        CustomTabActivityHelper.openCustomTab(this, customTabsIntent, Uri.parse(url),
                new CustomTabActivityHelper.CustomTabFallback() {
                    @Override
                    public void openUri(Activity activity, Uri uri) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setData(Uri.parse(url));
                        startActivity(intent);
                    }
                });
        //finish();
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
}
