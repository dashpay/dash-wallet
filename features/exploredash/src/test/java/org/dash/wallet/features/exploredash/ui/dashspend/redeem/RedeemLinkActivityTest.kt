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

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RedeemLinkActivityTest {
    private val redeemUrl = "https://spend.ctx.com/gift-cards/abc/redeem"
    private lateinit var activity: RedeemLinkActivity
    private lateinit var view: WebView

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(RedeemLinkActivity::class.java).get()
        activity.redeemUrl = redeemUrl
        view = WebView(activity)
        activity.webView = view
        RedeemLinkActivity.overrideClaims.claim(activity)
    }

    @After
    fun tearDown() {
        RedeemLinkActivity.overrideClaims.release(activity)
        System.clearProperty("https.proxyHost")
        System.clearProperty("https.proxyPort")
    }

    private fun configureProxy() {
        System.setProperty("https.proxyHost", "proxy.example.com")
        System.setProperty("https.proxyPort", "8080")
    }

    private fun request(url: String): WebResourceRequest {
        return mock { on { getUrl() } doReturn Uri.parse(url) }
    }

    private fun mainFrameRequest(url: String): WebResourceRequest {
        return mock {
            on { isForMainFrame } doReturn true
            on { getUrl() } doReturn Uri.parse(url)
        }
    }

    private fun assertPageAndOverrideGone() {
        assertTrue(activity.hasError)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(activity.webView)
        assertTrue(shadowOf(view).wasDestroyCalled())
        assertTrue(RedeemLinkActivity.overrideClaims.isEmpty)
    }

    @Test
    fun `a redirect off https before the first page drops the page and the override`() {
        val request = mainFrameRequest("intent://example.com/#Intent;end")

        assertTrue(activity.redeemWebViewClient.shouldOverrideUrlLoading(view, request))

        assertPageAndOverrideGone()
    }

    @Test
    fun `a network error on the first page drops the page and the override`() {
        activity.redeemWebViewClient.onReceivedError(view, mainFrameRequest(redeemUrl), mock())

        assertPageAndOverrideGone()
    }

    @Test
    fun `a certificate error on the first page drops the page and the override`() {
        val handler = mock<SslErrorHandler>()

        activity.redeemWebViewClient.onReceivedSslError(view, handler, mock())

        verify(handler).cancel()
        assertPageAndOverrideGone()
    }

    @Test
    fun `a download in place of the first page drops the page and the override`() {
        activity.redeemDownloadListener.onDownloadStart(redeemUrl, null, null, null, 0)

        assertPageAndOverrideGone()
    }

    @Test
    fun `an error after a page is showing keeps that page`() {
        activity.redeemWebViewClient.doUpdateVisitedHistory(view, redeemUrl, false)

        activity.redeemWebViewClient.onReceivedError(view, mainFrameRequest(redeemUrl), mock())
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(view, activity.webView)
        assertFalse(activity.hasError)
        assertFalse(RedeemLinkActivity.overrideClaims.isEmpty)
    }

    @Test
    fun `a service worker request over plain http is dropped`() {
        val response = activity.redeemServiceWorkerClient.shouldInterceptRequest(request("http://example.com/a.js"))

        assertEquals(0, response?.data?.available())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(view, activity.webView)
    }

    @Test
    fun `a service worker request goes out while the device connects directly`() {
        assertNull(activity.redeemServiceWorkerClient.shouldInterceptRequest(request("https://example.com/a.js")))
    }

    @Test
    fun `a service worker request under a new proxy is dropped and closes the page`() {
        configureProxy()

        val response = activity.redeemServiceWorkerClient.shouldInterceptRequest(request("https://example.com/a.js"))

        assertEquals(0, response?.data?.available())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isBlockedByProxy)
        assertNull(activity.webView)
        assertTrue(RedeemLinkActivity.overrideClaims.isEmpty)
    }

    @Test
    fun `a page request under a new proxy is dropped and closes the page`() {
        configureProxy()

        val response = activity.redeemWebViewClient.shouldInterceptRequest(view, request("https://example.com/a.js"))

        assertEquals(0, response?.data?.available())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isBlockedByProxy)
        assertNull(activity.webView)
    }

    @Test
    fun `a proxy found on opening stays on the redeem screen`() {
        activity.showLoadResult(RedeemLinkActivity.LoadResult.BLOCKED_BY_PROXY)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
        assertTrue(activity.isBlockedByProxy)
        assertNull(activity.webView)
        assertTrue(RedeemLinkActivity.overrideClaims.isEmpty)
    }

    @Test
    fun `a WebView that cannot isolate the page hands the link to the browser`() {
        activity.showLoadResult(RedeemLinkActivity.LoadResult.UNAVAILABLE)

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(redeemUrl, started.dataString)
        assertTrue(activity.isFinishing)
    }
}
