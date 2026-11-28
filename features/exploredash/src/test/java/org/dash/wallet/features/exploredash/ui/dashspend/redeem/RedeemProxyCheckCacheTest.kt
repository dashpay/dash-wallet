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
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeemProxyCheckCacheTest {
    private var time = 0L
    private var checks = 0
    private var isDirect = true
    private val cache = RedeemProxyCheckCache(ttlMillis = 1000) { time }

    private fun isDirect(host: String?): Boolean {
        return cache.isDirect(host) {
            checks++
            isDirect
        }
    }

    @Test
    fun `a page full of requests to one host reads the settings once`() {
        repeat(150) { assertTrue(isDirect("cdn.example.com")) }

        assertEquals(1, checks)
    }

    @Test
    fun `each host is checked on its own`() {
        isDirect("cdn.example.com")
        isDirect("img.example.com")

        assertEquals(2, checks)
    }

    @Test
    fun `a direct answer is read again once it is a second old`() {
        isDirect("cdn.example.com")
        time = 999
        isDirect("cdn.example.com")
        assertEquals(1, checks)

        time = 1000
        isDirect = false

        assertFalse(isDirect("cdn.example.com"))
        assertEquals(2, checks)
    }

    @Test
    fun `a proxy change drops every remembered answer`() {
        isDirect("cdn.example.com")
        isDirect = false
        cache.clear()

        assertFalse(isDirect("cdn.example.com"))
    }

    @Test
    fun `a proxied answer is never remembered`() {
        isDirect = false
        isDirect("cdn.example.com")
        isDirect("cdn.example.com")

        assertEquals(2, checks)
    }
}
