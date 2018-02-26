package de.schildbach.wallet.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private String url;
    private String confirmationUrl;

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

        webView = (WebView) findViewById(R.id.webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            public void onProgressChanged(WebView view, int progress) {
                // Activities and WebViews measure progress with different scales.
                // The progress meter will automatically disappear when we reach 100%
                WebViewActivity.this.setProgress(progress * 1000);
            }
        });
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String cookies = CookieManager.getInstance().getCookie(url);
                Log.d("Cookies. Finshed: ", url);
                if (cookies != null) {
                    Log.d("Fresh Cookies", cookies);
                }
            }
        });

        Intent intent = getIntent();
        url = intent.getStringExtra(WEBVIEW_URL);
        if (url != null) {
            webView.loadUrl(url);
        }

        String title = intent.getStringExtra(ACTIVITY_TITLE);
        if (title != null && actionBar != null) {
            actionBar.setTitle(title);
        }
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
        } else if (url != null) {
            webView.loadUrl(url);
        }
    }

    /**
     * TODO: Java Doc.
     * @param url
     * @return
     */
    private boolean handleUrlChange(String url) {
        Log.d("Cookies URL", url);
        //TODO: Handle Registration User Case
        if (url.contains(Constants.UPHOLD_AUTH_REDIRECT_URL)) {
            Uri uri = Uri.parse(url);
            String code = uri.getQueryParameter("code");
            if (code != null) {
                UpholdClient.getInstance().getAccessToken(code, new UpholdClient.Callback<String>() {
                    @Override
                    public void onSuccess(String accessToken) {
                        storeUpholdAccessToken(accessToken);
                        Toast.makeText(WebViewActivity.this, accessToken, Toast.LENGTH_SHORT).show();
                        finish();
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
