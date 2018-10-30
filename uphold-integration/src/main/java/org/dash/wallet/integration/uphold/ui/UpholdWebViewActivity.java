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
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.dash.wallet.integration.uphold.R;
import org.dash.wallet.integration.uphold.data.UpholdClient;
import org.dash.wallet.integration.uphold.data.UpholdConstants;

public class UpholdWebViewActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressDialog loadingDialog;
    private int retryCount = 0;
    private static final int MAX_RETRY = 3;
    public static final String BUYING_EXTRA = "uphold_buying_extra";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.webview_activity);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        loadingDialog = new ProgressDialog(this);
        loadingDialog.setIndeterminate(true);
        loadingDialog.setCancelable(false);
        loadingDialog.setMessage(getString(R.string.loading));

        webView = findViewById(R.id.webview);
        webView.getSettings().setJavaScriptEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (retryCount < MAX_RETRY) {
                    webView.reload();
                    retryCount++;
                } else {
                    webView.loadUrl("about:blank");
                    showLoadingErrorAlert();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlChange(url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                webView.scrollTo(0, 0);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadingDialog.show();
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadingDialog.hide();
                super.onPageFinished(view, url);
            }

        });

        Intent intent = getIntent();
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (intent.getDataString() != null) {
            //Confirmation URL
            webView.loadUrl(intent.getDataString());
        } else {
            webView.loadUrl(String.format(UpholdConstants.INITIAL_URL,
                    UpholdClient.getInstance().getEncryptionKey()));
        }

        if (actionBar != null) {
            actionBar.setTitle(R.string.uphold_buy_dash_activity_title);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void finish() {
        if (isTaskRoot()) {
            PackageManager packageManager = getPackageManager();
            startActivity(packageManager.getLaunchIntentForPackage(getApplication().getPackageName()));
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        loadingDialog.dismiss();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String confirmationUrl = intent.getDataString();
        if (confirmationUrl != null) {
            webView.loadUrl(confirmationUrl);
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

    /**
     * Prevents loading unrelated URLs and extracts authorization code from Login flow to
     * get access token from it.
     *
     * @param url that is going to be loaded next.
     * @return <code>true</code> to prevent url change, false to continue.
     */
    private boolean handleUrlChange(String url) {
        //Prevent leaving Uphold website from WebView
        if (!(url.contains("dash.org") || url.contains("uphold.com"))) {
            return true;
        }

        if (url.contains(UpholdClient.UPHOLD_AUTH_REDIRECT_URL)) {
            Uri uri = Uri.parse(url);

            String code = uri.getQueryParameter("code");
            String state = uri.getQueryParameter("state");

            if (code != null && UpholdClient.getInstance().getEncryptionKey().equals(state)) {
                UpholdClient.getInstance().getAccessToken(code, new UpholdClient.Callback<String>() {
                    @Override
                    public void onSuccess(String dashCardId) {
                        if (getIntent().getBooleanExtra(BUYING_EXTRA, false)) {
                            webView.loadUrl(String.format(UpholdConstants.CARD_URL_BASE, dashCardId));
                        } else {
                            startUpholdAccountActivity();
                        }
                    }

                    @Override
                    public void onError(Exception e, boolean otpRequired) {
                        showLoadingErrorAlert();
                    }
                });
                return true;
            } else {
                showLoadingErrorAlert();
            }
        }
        return false;
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

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

}
