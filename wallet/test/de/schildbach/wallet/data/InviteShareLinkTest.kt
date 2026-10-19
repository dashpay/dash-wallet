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

import org.junit.Assert.assertEquals
import org.junit.Test

class InviteShareLinkTest {
    private val query = "af_dp=dashpay%3A%2F%2Finvite%3Fdu%3Dalice%26assetlocktx%3Dab%26pk%3Dcd%26islock%3D01" +
        "&c=dashpay_invitation&pid=af_app_invites&af_siteid=hashengineering.darkcoin.wallet_test"

    @Test
    fun `sdk fallback host is replaced by the brand domain`() {
        val shared = InviteShareLink.from("https://go.onelink.me/k7Pz?$query", "dashpaytest.onelink.me")
        assertEquals("https://dashpaytest.onelink.me/k7Pz/?$query", shared)
    }

    @Test
    fun `brand domain link only gains the trailing slash`() {
        val shared = InviteShareLink.from("https://dashpay.onelink.me/hirm?$query", "dashpay.onelink.me")
        assertEquals("https://dashpay.onelink.me/hirm/?$query", shared)
    }

    @Test
    fun `an existing trailing slash is kept`() {
        val shared = InviteShareLink.from("https://dashpay.onelink.me/hirm/?$query", "dashpay.onelink.me")
        assertEquals("https://dashpay.onelink.me/hirm/?$query", shared)
    }

    @Test
    fun `fallback host is kept when no brand domain is configured`() {
        val shared = InviteShareLink.from("https://go.onelink.me/k7Pz?$query", "")
        assertEquals("https://go.onelink.me/k7Pz/?$query", shared)
    }

    @Test
    fun `other hosts are not rewritten`() {
        val shared = InviteShareLink.from(
            "https://kzqm6r.app.appsflyersdk.com/hashengineering.darkcoin.wallet_test?$query",
            "dashpaytest.onelink.me"
        )
        assertEquals("https://kzqm6r.app.appsflyersdk.com/hashengineering.darkcoin.wallet_test/?$query", shared)
    }

    @Test
    fun `encoded query is carried over byte for byte`() {
        val shared = InviteShareLink.from("https://go.onelink.me/k7Pz?$query", "dashpaytest.onelink.me")
        assertEquals(query, shared.substringAfter('?'))
    }

    @Test
    fun `unparseable input is returned unchanged`() {
        assertEquals("not a link", InviteShareLink.from("not a link", "dashpay.onelink.me"))
        assertEquals("https://", InviteShareLink.from("https://", "dashpay.onelink.me"))
    }
}
