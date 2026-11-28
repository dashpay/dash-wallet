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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeemProxyOverrideClaimsTest {
    private val claims = RedeemProxyOverrideClaims()
    private val first = Any()
    private val second = Any()

    @Test
    fun `the only holder lifts the override when its page is discarded`() {
        claims.claim(first)

        assertTrue(claims.release(first))
        assertTrue(claims.isEmpty)
    }

    @Test
    fun `a holder that already let go does not lift the override again`() {
        claims.claim(first)
        claims.release(first)

        assertFalse(claims.release(first))
    }

    @Test
    fun `a screen that never loaded a page does not lift the override`() {
        claims.claim(second)

        assertFalse(claims.release(first))
        assertFalse(claims.isEmpty)
    }

    @Test
    fun `an earlier screen does not lift the override under a newer one`() {
        claims.claim(first)
        claims.claim(second)

        assertFalse(claims.release(first))
        assertFalse(claims.isEmpty)
        assertTrue(claims.release(second))
    }

    @Test
    fun `a screen only wipes on its first load while no other screen holds a claim`() {
        assertFalse(claims.isOnlyHolder(first))

        claims.claim(first)
        assertTrue(claims.isOnlyHolder(first))

        claims.claim(second)
        assertFalse(claims.isOnlyHolder(first))
        assertFalse(claims.isOnlyHolder(second))
    }

    @Test
    fun `the screen that stays takes over from the one that closes`() {
        claims.claim(first)
        claims.claim(second)

        claims.release(second)
        assertSame(first, claims.latest)

        claims.release(first)
        assertNull(claims.latest)
    }

    @Test
    fun `retrying claims once however often the page is reloaded`() {
        claims.claim(first)
        claims.claim(first)

        assertTrue(claims.release(first))
    }

    @Test
    fun `a retry after a discarded page claims the override again`() {
        claims.claim(first)
        claims.release(first)
        claims.claim(first)

        assertFalse(claims.isEmpty)
        assertTrue(claims.release(first))
    }
}
