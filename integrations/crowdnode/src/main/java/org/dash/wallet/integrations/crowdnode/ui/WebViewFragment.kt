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

package org.dash.wallet.integrations.crowdnode.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentWebviewBinding

@AndroidEntryPoint
class WebViewFragment : Fragment(R.layout.fragment_webview) {
    private val binding by viewBinding(FragmentWebviewBinding::bind)
    private val args by navArgs<WebViewFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarTitle.text = args.title

        val binding = binding // Avoids IllegalStateException in onPageFinished callback
        binding.webView.settings.javaScriptEnabled = args.enableJavaScript
        binding.webView.webViewClient = object: WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i("CROWDNODE", "onPageStarted, url: ${url}")
                val loginPrefix = "https://logintest.crowdnode.io/login"
                val signUpSuffix = "&view=signup-only"

                url?.let {
                    if (url.startsWith(loginPrefix) && !url.endsWith(signUpSuffix)) {
                        view?.loadUrl("$url$signUpSuffix")
                    }
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                Log.i("CROWDNODE", "onLoadResource, url: ${url}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                Log.i("CROWDNODE", "onPageFinished, url: ${url}")
                binding.progressBar.isVisible = false
            }
        }

        binding.progressBar.isVisible = true
        binding.webView.loadUrl(args.url)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }
}