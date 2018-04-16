/*
 * Copyright 2011-2015 the original author or authors.
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

package de.schildbach.wallet.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MenuItem;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.UpholdClient;
import de.schildbach.wallet_test.R;

public class WebViewActivity extends AppCompatActivity {

    public static final String WEBVIEW_URL = "webview_url";
    public static final String ACTIVITY_TITLE = "activity_title";

    private WebView webView;
    private String confirmationUrl;
    private ProgressDialog loadingDialog;
    private int retryCount = 0;
    private static final int MAX_RETRY = 3;

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

        webView = (WebView) findViewById(R.id.webview);
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
        String url = intent.getStringExtra(WEBVIEW_URL);
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (url != null) {
            webView.loadUrl(url);
        } else if (intent.getDataString() != null) {
            //Confirmation URL
            webView.loadUrl(intent.getDataString());
        }

        String title = intent.getStringExtra(ACTIVITY_TITLE);
        if (title != null && actionBar != null) {
            actionBar.setTitle(title);
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
        confirmationUrl = intent.getDataString();
        if (confirmationUrl != null) {
            webView.loadUrl(confirmationUrl);
            confirmationUrl = null;
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
     * TODO: Java Doc.
     * @param url
     * @return
     */
    private boolean handleUrlChange(String url) {
        //Prevent leaving Uphold website from WebView
        if (!(url.contains("dash.org") || url.contains("uphold.com"))) {
            Log.d("GB1", "Url change blocked for " + url);
            return true;
        }

        Log.d("Cookies URL", url);
        if (url.contains(Constants.UPHOLD_AUTH_REDIRECT_URL)) {
            Uri uri = Uri.parse(url);
            String code = uri.getQueryParameter("code");
            if (code != null) {
                UpholdClient.getInstance().getAccessToken(code, new UpholdClient.Callback<String>() {
                    @Override
                    public void onSuccess(String dashCardId) {
                        storeUpholdAccessToken(UpholdClient.getInstance().getAccessToken());
                        String url = "https://sandbox.uphold.com/dashboard/cards/" + dashCardId + "/add";
                        webView.loadUrl(url);
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(WebViewActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            } else {
                //TODO: Handle Error
            }
        }
        return false;
    }

    private void storeUpholdAccessToken(String accessToken) {
        SharedPreferences prefs = getSharedPreferences(Constants.UPHOLD_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(Constants.UPHOLD_ACCESS_TOKEN, accessToken).apply();
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
