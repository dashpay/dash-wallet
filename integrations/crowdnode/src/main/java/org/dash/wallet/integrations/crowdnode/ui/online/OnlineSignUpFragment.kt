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

package org.dash.wallet.integrations.crowdnode.ui.online

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.WebViewFragment
import org.dash.wallet.integrations.crowdnode.R

@AndroidEntryPoint
class OnlineSignUpFragment : WebViewFragment() {
    companion object {
        private const val LOGIN_PREFIX = "https://logintest.crowdnode.io/login"
        private const val SIGNUP_SUFFIX = "&view=signup-only"
    }

    private val args by navArgs<OnlineSignUpFragmentArgs>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        arguments = bundleOf(
            "title" to getString(R.string.crowdnode_signup),
            "url" to args.url,
            "enableJavaScript" to true
        )

        super.onViewCreated(view, savedInstanceState)
    }

    override fun doOnPageStarted(webView: WebView?, url: String?) {
        Log.i("CROWDNODE", "doOnPageStarted: ${url}")
        val fullSuffix = SIGNUP_SUFFIX + "&loginHint=${args.email}"

        url?.let {
            if (url.startsWith(LOGIN_PREFIX) && !url.endsWith(fullSuffix)) {
                webView?.loadUrl("$url$fullSuffix")
            }
        }
    }

    override fun doOnPageFinished(webView: WebView?, url: String?) {
        Log.i("CROWDNODE", "doOnPageFinished: ${url}")
    }
}