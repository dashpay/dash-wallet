/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.FragmentWebviewBinding

open class WebViewFragment : Fragment(R.layout.fragment_webview) {
    private val binding by viewBinding(FragmentWebviewBinding::bind)
    private var webView: WebView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("url")
        val title = arguments?.getString("title")
        val enableJavaScript = arguments?.getBoolean("enableJavaScript") ?: false

        binding.toolbarTitle.text = title

        val binding = binding // Avoids IllegalStateException in onPageFinished callback
        webView = binding.webView
        binding.webView.settings.javaScriptEnabled = enableJavaScript
        binding.webView.webViewClient = object: WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                doOnPageStarted(view, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.isVisible = false
                doOnPageFinished(view, url)
            }
        }

        binding.progressBar.isVisible = true

        if (!url.isNullOrEmpty()) {
            binding.webView.loadUrl(url)
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    open fun doOnPageStarted(webView: WebView?, url: String?) { }
    open fun doOnPageFinished(webView: WebView?, url: String?) { }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.resumeTimers()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}