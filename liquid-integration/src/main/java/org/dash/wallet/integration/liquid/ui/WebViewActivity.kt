package org.dash.wallet.integration.liquid.ui

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.widget.Toolbar
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.integration.liquid.R

class WebViewActivity : InteractionAwareActivity() {

    private lateinit var context: Context
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_webview)
        this.context = this@WebViewActivity
        webView = findViewById(R.id.webView)
        initUI()
    }

    private fun initUI() {

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(intent.getStringExtra("title"))
        webView.loadUrl(intent.getStringExtra("url"))

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