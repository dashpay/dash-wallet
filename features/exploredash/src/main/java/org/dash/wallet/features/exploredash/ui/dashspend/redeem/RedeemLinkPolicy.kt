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
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException

object RedeemLinkPolicy {
    private const val MIN_SDK_INT = 26
    private const val HTTPS_PORT = 443
    private val serverErrors = 500..599
    private val redeemPath = Regex("^/+gift-cards/[A-Za-z0-9_-]+/redeem$")
    private val subframeOnlyUrls = setOf("about:blank", "about:srcdoc")
    private val contactSchemes = setOf("mailto", "tel")

    enum class RequestVerdict { ALLOW, DROP, BLOCKED_BY_PROXY }

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

    // The screen connects to HTTPS hosts directly, so it must not be used where the user routes traffic through a
    // proxy. A null or empty selection means the answer is unknown, which counts as a proxy.
    fun connectsDirectly(proxyHost: String?, pacUrl: String?, selectProxies: () -> List<Proxy>?): Boolean {
        if (!proxyHost.isNullOrBlank() || !pacUrl.isNullOrBlank()) {
            return false
        }
        val selected = selectProxies()
        return !selected.isNullOrEmpty() && selected.all { it.type() == Proxy.Type.DIRECT }
    }

    fun requestVerdict(scheme: String?, connectsDirectly: () -> Boolean): RequestVerdict {
        return when {
            scheme.equals("http", ignoreCase = true) -> RequestVerdict.DROP
            scheme.equals("https", ignoreCase = true) && !connectsDirectly() -> RequestVerdict.BLOCKED_BY_PROXY
            else -> RequestVerdict.ALLOW
        }
    }

    // A host java.net.URI cannot hold says nothing about a proxy, so such a request follows the device settings alone.
    fun requestConnectsDirectly(
        url: String?,
        proxyHost: String?,
        pacUrl: String?,
        selectProxies: (URI) -> List<Proxy>?
    ): Boolean {
        val lookup = proxyLookupUri(url) ?: return proxyHost.isNullOrBlank() && pacUrl.isNullOrBlank()
        return connectsDirectly(proxyHost, pacUrl) { selectProxies(lookup) }
    }

    // A proxy selector only looks at the scheme and host, and java.net.URI rejects characters a link may hold.
    fun proxyLookupUri(url: String?): URI? {
        val link = url?.toHttpUrlOrNull() ?: return null

        return try {
            URI(link.scheme, link.host, null, null)
        } catch (e: URISyntaxException) {
            null
        }
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
