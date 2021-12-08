package de.schildbach.wallet.ui.coinbase

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.webkit.*
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.integration.liquid.databinding.ActivityLoginWebviewBinding

class CoinBaseWebClientActivity : InteractionAwareActivity() {

    private lateinit var binding: ActivityLoginWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initUI()
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun initUI() {
        val toolbar = findViewById<Toolbar>(org.dash.wallet.integration.liquid.R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(R.string.CoinBase)
        // Clear all the Application Cache, Web SQL Database and the HTML5 Web Storage
        WebStorage.getInstance().deleteAllData()

        // Clear all the cookies
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearHistory()
        binding.webView.clearSslPreferences()

        binding.webView.webViewClient = MyWebViewClient()
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.allowFileAccess = false
        WebStorage.getInstance().deleteAllData()

        val loginUrl =
            "https://www.coinbase.com/oauth/authorize?client_id=1ca2946d789bf6d986f26df03f4a52a8c6f1fe80e469eb1d3477e7c90768559a&redirect_uri=https://coin.base.test/callback&response_type=code&account=all&scope=wallet:accounts:read,wallet:user:read,wallet:transactions:transfer"

        binding.webView.loadUrl(loginUrl)
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    private fun setActivityResult(code: String) {
        val intent = Intent()
        intent.putExtra(RESULT_TEXT, code)
        setResult(RESULT_OK, intent)
        finish()
    }

    private inner class MyWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            val uri = Uri.parse(url)
            val host = uri.host
            if (Uri.parse(url).host == "coin.base.test") {
                // This is my web site, so do not override; let my WebView load the page
                val code = uri.getQueryParameter("code")?.let {
                    // viewModel.setLoginToken(it)
                    setActivityResult(it)
                }

                return false
            }

            url?.let { view?.loadUrl(it) }
            return true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        val RESULT_TEXT = "RESULT_TEXT"
    }
}
