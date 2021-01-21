package com.e.liquid_integration.ui

import android.app.ProgressDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.widget.Toolbar
import com.e.liquid_integration.R
import com.e.liquid_integration.data.LiquidConstants
import org.dash.wallet.common.InteractionAwareActivity

class LiquidLoginActivity : InteractionAwareActivity() {

    private lateinit var _context: Context
    private lateinit var webView: WebView
    private var loadingDialog: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_webview)
        this._context = this@LiquidLoginActivity
        webView = findViewById(R.id.webView)
        initUI()
    }

    private fun initUI() {
        // Clear all the cookies
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(null)
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        };

        webView.clearCache(true);
        webView.clearFormData();
        webView.clearHistory();
        webView.clearSslPreferences();

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(intent.getStringExtra("title"))

        webView.getSettings().setJavaScriptEnabled(true)
        webView.setWebViewClient(AppWebViewClients(_context))
        System.out.println("URL::" + intent.getStringExtra("url"))
        webView.loadUrl(intent.getStringExtra("url"))

        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))
        // loadingDialog!!.show()
    }

    class AppWebViewClients(val _context: Context) : WebViewClient() {
        override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
        ): Boolean {
            println("OVERR::$url")
            if (url.contains(LiquidConstants.DEEP_LINK_URL)) {
                if (_context is LiquidLoginActivity) {
                    _context.finishActivity()
                }
            }
            view.loadUrl(url)
            return true
        }

        override fun onPageFinished(
                view: WebView,
                url: String) {
            if (_context is LiquidLoginActivity) {
                _context.hideProgressDialog()
            }
            super.onPageFinished(view, url)

        }
    }

    fun hideProgressDialog() {
        //loadingDialog!!.hide()
    }

    fun finishActivity() {
        setResult(RESULT_OK)
        onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }


    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}