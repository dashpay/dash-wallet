/*
 * Copyright 2026 Dash Core Group.
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
package org.dash.wallet.features.exploredash.ui.dashspend.redeem

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object RedeemLinkPolicy {
    private const val MIN_SDK_INT = 26
    private const val HTTPS_PORT = 443
    private val serverErrors = 500..599
    private val redeemPath = Regex("^/+gift-cards/[A-Za-z0-9_-]+/redeem$")
    private val subframeOnlyUrls = setOf("about:blank", "about:srcdoc")
    private val contactSchemes = setOf("mailto", "tel")

    fun canOpenInApp(url: String?, sdkInt: Int, ctxBaseUrl: String): Boolean {
        if (url == null || sdkInt < MIN_SDK_INT || url.contains('\\') || url != url.trim()) {
            return false
        }
        val link = url.toHttpUrlOrNull() ?: return false
        val ctxHost = ctxBaseUrl.toHttpUrlOrNull()?.host ?: return false

        return link.isHttps &&
            link.host == ctxHost &&
            link.username.isEmpty() &&
            link.password.isEmpty() &&
            link.port == HTTPS_PORT &&
            redeemPath.matches(link.encodedPath)
    }

    fun isAllowedNavigation(url: String?, isMainFrame: Boolean): Boolean {
        if (url == null) {
            return false
        }
        if (url in subframeOnlyUrls) {
            return !isMainFrame
        }
        return url.toHttpUrlOrNull()?.isHttps == true
    }

    fun canOpenExternally(url: String?, ctxBaseUrl: String): Boolean {
        if (url == null || url != url.trim()) {
            return false
        }
        val link = url.toHttpUrlOrNull() ?: return false
        val ctx = ctxBaseUrl.toHttpUrlOrNull()
        return link.isHttps || ctx == null || registrableDomain(link) != registrableDomain(ctx)
    }

    fun isContactLink(url: String?): Boolean {
        return url?.substringBefore(':', "")?.lowercase() in contactSchemes
    }

    fun normalizeChallenge(challenge: String?): String? {
        return challenge?.trim()?.takeUnless { it.isEmpty() }
    }

    fun isInitialServerError(isMainFrame: Boolean, hasCommittedPage: Boolean, statusCode: Int): Boolean {
        return isMainFrame && !hasCommittedPage && statusCode in serverErrors
    }

    fun displayHost(url: String?): String? {
        return url?.toHttpUrlOrNull()?.let { registrableDomain(it) }
    }

    private fun registrableDomain(url: HttpUrl): String {
        return url.topPrivateDomain() ?: url.host
    }
}
