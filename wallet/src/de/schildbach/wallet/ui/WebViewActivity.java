package de.schildbach.wallet.ui;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MenuItem;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
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
                Toast.makeText(WebViewActivity.this, "Oh no! " + description, Toast.LENGTH_SHORT).show();
                //TODO: Error Handling
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
    protected void onDestroy() {
        loadingDialog.dismiss();
        super.onDestroy();
    }

    /**
     * TODO: Java Doc
     * @param intent
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        confirmationUrl = intent.getDataString();
    }

    /**
     * TODO: Java Doc
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (confirmationUrl != null) {
            webView.loadUrl(confirmationUrl);
        }
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
                        //finish();
                    }

                    @Override
                    public void onError(Exception e) {
                        Toast.makeText(WebViewActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
                //TODO: Move the code to a better place?
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
