/*
 * Copyright 2026 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.data

import java.net.URI

/**
 * Turns the long OneLink URL produced by the AppsFlyer SDK into the link we
 * actually hand to the user.
 *
 * The iOS wallet has no AppsFlyer SDK: it can only open an invitation whose
 * `af_dp` payload is in the URL itself, and only from a domain the app claims
 * (`dashpay.onelink.me` / `dashpaytest.onelink.me`). Two SDK behaviours get in
 * the way of that:
 *
 * 1. When the app id is not part of the OneLink template, the SDK never learns
 *    the template's domain and falls back to `go.onelink.me`, whose
 *    apple-app-site-association does not list the Dash app. We put the brand
 *    domain configured for this build back in.
 * 2. The SDK emits the template as `/<id>` while the AASA path pattern is the
 *    template id followed by a slash and a wildcard. A trailing slash makes the
 *    path match the pattern; AppsFlyer
 *    serves `/<id>/` exactly like `/<id>`.
 *
 * The query string is carried over untouched so the encoded `af_dp` survives.
 */
object InviteShareLink {
    const val SDK_FALLBACK_HOST = "go.onelink.me"

    fun from(generatedLink: String, brandDomain: String): String {
        val uri = try {
            URI(generatedLink)
        } catch (e: Exception) {
            return generatedLink
        }
        val host = uri.host ?: return generatedLink
        if (uri.scheme == null || uri.rawPath.isNullOrEmpty()) {
            return generatedLink
        }

        val shareHost = if (host.equals(SDK_FALLBACK_HOST, ignoreCase = true) && brandDomain.isNotBlank()) {
            brandDomain
        } else {
            host
        }
        val path = if (uri.rawPath.endsWith("/")) uri.rawPath else uri.rawPath + "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""

        return "${uri.scheme}://$shareHost$path$query$fragment"
    }
}
