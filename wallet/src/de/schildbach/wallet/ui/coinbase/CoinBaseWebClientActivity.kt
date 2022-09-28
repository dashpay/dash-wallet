/*
 * Copyright 2021 Dash Core Group.
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
package de.schildbach.wallet.ui.coinbase

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.webkit.*
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.databinding.FragmentWebviewBinding

class CoinBaseWebClientActivity : InteractionAwareActivity() {

    private lateinit var binding: FragmentWebviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = FragmentWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initUI()
        turnOffAutoLogout()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initUI() {
        setSupportActionBar(binding.toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(R.string.coinbase)
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
            "https://www.coinbase.com/oauth/authorize?client_id=${BuildConfig.COINBASE_CLIENT_ID}" +
               "&redirect_uri=authhub://oauth-callback&response_type" +
                "=code&scope=wallet:accounts:read,wallet:user:read,wallet:payment-methods:read," +
                "wallet:buys:read,wallet:buys:create,wallet:transactions:transfer," +
                "wallet:sells:create,wallet:sells:read," +
                "wallet:transactions:request,wallet:transactions:read,wallet:trades:create," +
                "wallet:supported-assets:read,wallet:transactions:send," + "wallet:addresses:read,wallet:addresses:create" + "&meta[send_limit_amount]=1" +
                "&meta[send_limit_currency]=USD" +
                "&meta[send_limit_period]=month" +
                "&account=all"

        binding.webView.loadUrl(loginUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        turnOnAutoLogout()
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
            if (Uri.parse(url).host == "oauth-callback") {
                // This is my web site, so do not override; let my WebView load the page
               uri.getQueryParameter("code")?.let {
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
