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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeemLinkPolicyTest {
    private val prodBase = "https://spend.ctx.com/"
    private val stagingBase = "https://staging.spend.ctx.com/"
    private val prodLink = "https://spend.ctx.com/gift-cards/5f1c2d3e-0a1b-4c2d-8e3f-123456789abc/redeem?token=abc.def"

    private fun canOpen(url: String?, sdkInt: Int = 26, base: String = prodBase) =
        RedeemLinkPolicy.canOpenInApp(url, sdkInt, base)

    @Test
    fun ctxRedeemLink_opensInApp() {
        assertTrue(canOpen(prodLink))
        assertTrue(canOpen("https://spend.ctx.com/gift-cards/abc_123/redeem"))
        assertTrue(canOpen("https://spend.ctx.com:443/gift-cards/abc/redeem"))
    }

    @Test
    fun hostIsTakenFromTheConfiguredBaseUrl() {
        val stagingLink = "https://staging.spend.ctx.com/gift-cards/abc/redeem?token=t"

        assertTrue(canOpen(stagingLink, base = stagingBase))
        assertFalse(canOpen(stagingLink, base = prodBase))
        assertFalse(canOpen(prodLink, base = stagingBase))
        assertFalse(canOpen(prodLink, base = "not a url"))
    }

    @Test
    fun otherHosts_doNotOpenInApp() {
        assertFalse(canOpen("https://claims.storedvalue.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com.evil.io/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://evilspend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com@evil.io/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com./gift-cards/abc/redeem"))
    }

    @Test
    fun userInfoOnTheCtxHost_doesNotOpenInApp() {
        assertFalse(canOpen("https://user@spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://:secret@spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://user:secret@spend.ctx.com/gift-cards/abc/redeem"))
    }

    @Test
    fun onlyHttpsOnTheDefaultPort_opensInApp() {
        assertFalse(canOpen("http://spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("http://spend.ctx.com:443/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com:8443/gift-cards/abc/redeem"))
    }

    @Test
    fun hostAndSchemeCaseIsIgnored_pathCaseIsNot() {
        assertTrue(canOpen("HTTPS://SPEND.CTX.COM/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com/GIFT-CARDS/abc/REDEEM"))
    }

    @Test
    fun backslashAnywhere_doesNotOpenInApp() {
        assertFalse(canOpen("https://spend.ctx.com\\gift-cards\\abc\\redeem"))
        assertFalse(canOpen("https:\\\\spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc/redeem?token=a\\b"))
    }

    @Test
    fun repeatedLeadingSlashes_stillOpenInApp() {
        assertTrue(canOpen("https://spend.ctx.com//gift-cards/abc/redeem?token=t"))
        assertFalse(canOpen("https://spend.ctx.com//api/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc//redeem"))
    }

    @Test
    fun otherPaths_doNotOpenInApp() {
        assertFalse(canOpen("https://spend.ctx.com/"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards//redeem"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc/redeem/"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc/redeem/extra"))
        assertFalse(canOpen("https://spend.ctx.com/api/gift-cards/abc/redeem"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/a%2Fb/redeem"))
        assertFalse(canOpen("https://spend.ctx.com/gift-cards/abc/redeem/../../../login"))
        assertFalse(canOpen("https://spend.ctx.com/login?next=/gift-cards/abc/redeem"))
    }

    @Test
    fun missingOrMalformedLinks_doNotOpenInApp() {
        assertFalse(canOpen(null))
        assertFalse(canOpen(""))
        assertFalse(canOpen("   "))
        assertFalse(canOpen("spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("//spend.ctx.com/gift-cards/abc/redeem"))
        assertFalse(canOpen("javascript:alert(1)"))
    }

    @Test
    fun surroundingWhitespace_doesNotOpenInApp() {
        assertFalse(canOpen(" $prodLink"))
        assertFalse(canOpen("$prodLink "))
        assertFalse(canOpen("$prodLink\n"))
    }

    @Test
    fun inAppBrowserNeedsApi26() {
        assertFalse(canOpen(prodLink, sdkInt = 25))
        assertTrue(canOpen(prodLink, sdkInt = 26))
        assertTrue(canOpen(prodLink, sdkInt = 36))
    }

    @Test
    fun navigation_httpsIsAllowedToAnyHostInAnyFrame() {
        assertTrue(RedeemLinkPolicy.isAllowedNavigation("https://claims.storedvalue.com/claim?x=1", true))
        assertTrue(RedeemLinkPolicy.isAllowedNavigation("https://www.merchant.example/redeem", false))
    }

    @Test
    fun navigation_otherSchemesAreBlockedInAnyFrame() {
        val blocked = listOf(
            "http://claims.storedvalue.com/claim",
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "market://details?id=com.example",
            "file:///data/data/hashengineering.darkcoin.wallet/files/wallet.log",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://claims.storedvalue.com/0f6e6c7e-4a3b-4f0e-9d53-0f8f4b6f6a11",
            "content://com.android.contacts/contacts",
            "about:config",
            "",
            "https"
        )

        for (url in blocked) {
            assertFalse(url, RedeemLinkPolicy.isAllowedNavigation(url, true))
            assertFalse(url, RedeemLinkPolicy.isAllowedNavigation(url, false))
        }
        assertFalse(RedeemLinkPolicy.isAllowedNavigation(null, true))
        assertFalse(RedeemLinkPolicy.isAllowedNavigation(null, false))
    }

    @Test
    fun navigation_blankDocumentsAreAllowedInSubframesOnly() {
        for (url in listOf("about:blank", "about:srcdoc")) {
            assertTrue(url, RedeemLinkPolicy.isAllowedNavigation(url, false))
            assertFalse(url, RedeemLinkPolicy.isAllowedNavigation(url, true))
        }
    }

    @Test
    fun displayHost_isTheRegistrableDomain() {
        assertEquals("storedvalue.com", RedeemLinkPolicy.displayHost("https://claims.storedvalue.com/claim"))
        assertEquals("merchant.co.uk", RedeemLinkPolicy.displayHost("https://gift.shop.merchant.co.uk/"))
        assertEquals("evil.io", RedeemLinkPolicy.displayHost("https://spend.ctx.com.evil.io/"))
    }

    @Test
    fun displayHost_fallsBackToTheHostWhenThereIsNoRegistrableDomain() {
        assertEquals("192.168.1.10", RedeemLinkPolicy.displayHost("https://192.168.1.10/claim"))
        assertEquals("localhost", RedeemLinkPolicy.displayHost("https://localhost/claim"))
        assertNull(RedeemLinkPolicy.displayHost("about:blank"))
        assertNull(RedeemLinkPolicy.displayHost(null))
    }

    @Test
    fun externalOpen_httpsGoesOutForAnyHost() {
        assertTrue(RedeemLinkPolicy.canOpenExternally("https://piggy.cards/claim/abc", prodBase))
        assertTrue(RedeemLinkPolicy.canOpenExternally(prodLink, prodBase))
    }

    @Test
    fun externalOpen_httpGoesOutOnlyOffTheCtxHost() {
        assertTrue(RedeemLinkPolicy.canOpenExternally("http://piggy.cards/claim/abc", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://spend.ctx.com/gift-cards/abc/redeem?token=t", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("HTTP://SPEND.CTX.COM/gift-cards/abc/redeem", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://spend.ctx.com./gift-cards/abc/redeem", prodBase))
        assertFalse(
            RedeemLinkPolicy.canOpenExternally("http://spend.ctx.com/gift-cards/abc/redeem", "https://spend.ctx.com./")
        )
    }

    @Test
    fun externalOpen_httpIsRefusedAcrossTheCtxDomain() {
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://spend.ctx.com/gift-cards/abc/redeem", stagingBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://staging.spend.ctx.com/gift-cards/abc/redeem", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://www.spend.ctx.com/gift-cards/abc/redeem", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://ctx.com/gift-cards/abc/redeem", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("http://staging.spend.ctx.com./x", prodBase))
    }

    @Test
    fun externalOpen_httpOnLookalikeDomainsIsNotTheCtxDomain() {
        assertTrue(RedeemLinkPolicy.canOpenExternally("http://spend.ctx.com.evil.io/claim", prodBase))
        assertTrue(RedeemLinkPolicy.canOpenExternally("http://evilctx.com/claim", prodBase))
        assertTrue(RedeemLinkPolicy.canOpenExternally("http://ctx.com.au/claim", prodBase))
    }

    @Test
    fun externalOpen_refusesSurroundingWhitespace() {
        val claimLink = "https://piggy.cards/claim/abc"

        assertFalse(RedeemLinkPolicy.canOpenExternally(" $claimLink", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("$claimLink ", prodBase))
        assertFalse(RedeemLinkPolicy.canOpenExternally("$claimLink\n", prodBase))
    }

    @Test
    fun externalOpen_isLimitedToWebLinks() {
        val refused = listOf(
            "file:///sdcard/Download/card.html",
            "intent://piggy.cards/#Intent;scheme=https;end",
            "dash:XdAUmwtig27HBG6WfYyHAzP8n6XC9jESEw?amount=1",
            "dashwallet://pay?address=XdAUmwtig27HBG6WfYyHAzP8n6XC9jESEw",
            "pay:XdAUmwtig27HBG6WfYyHAzP8n6XC9jESEw",
            "javascript:alert(1)",
            "",
            "   "
        )

        for (url in refused) {
            assertFalse(url, RedeemLinkPolicy.canOpenExternally(url, prodBase))
        }
        assertFalse(RedeemLinkPolicy.canOpenExternally(null, prodBase))
    }

    @Test
    fun challenge_isTrimmedAndBlankMeansAbsent() {
        assertNull(RedeemLinkPolicy.normalizeChallenge(null))
        assertNull(RedeemLinkPolicy.normalizeChallenge(""))
        assertNull(RedeemLinkPolicy.normalizeChallenge("  \t\n "))
        assertEquals("A1B2C3", RedeemLinkPolicy.normalizeChallenge("  A1B2C3\n"))
        assertEquals("A1 B2", RedeemLinkPolicy.normalizeChallenge(" A1 B2 "))
        assertEquals("A1B2C3", RedeemLinkPolicy.normalizeChallenge("A1B2C3"))
    }

    @Test
    fun onlyAnInitialMainFrameServerError_offersRetry() {
        assertTrue(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 500))
        assertTrue(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 503))
        assertTrue(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 599))

        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = false, hasCommittedPage = false, 503))
        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = true, 503))
        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 404))
        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 410))
        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 499))
        assertFalse(RedeemLinkPolicy.isInitialServerError(isMainFrame = true, hasCommittedPage = false, 600))
    }

    @Test
    fun contactLinks_areMailtoAndTelOnly() {
        assertTrue(RedeemLinkPolicy.isContactLink("mailto:help@storedvalue.com"))
        assertTrue(RedeemLinkPolicy.isContactLink("TEL:+18005550100"))
        assertFalse(RedeemLinkPolicy.isContactLink("sms:+18005550100"))
        assertFalse(RedeemLinkPolicy.isContactLink("intent://mailto/#Intent;scheme=mailto;end"))
        assertFalse(RedeemLinkPolicy.isContactLink("https://storedvalue.com/mailto:help"))
        assertFalse(RedeemLinkPolicy.isContactLink("mailto"))
        assertFalse(RedeemLinkPolicy.isContactLink(null))
    }
}
