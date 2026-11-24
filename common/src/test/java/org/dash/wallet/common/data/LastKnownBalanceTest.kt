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
package org.dash.wallet.common.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownBalanceTest {
    private val value = longPreferencesKey("balance")
    private val owner = stringPreferencesKey("balance_wallet")

    private fun stored(duffs: Long?, walletId: String?): Preferences = mutablePreferencesOf().apply {
        duffs?.let { this[value] = it }
        walletId?.let { this[owner] = it }
    }

    private fun Preferences.read(walletId: String?) = lastKnownBalance(value, owner, walletId)

    @Test
    fun `a value stored as the installed wallet's is read`() {
        assertEquals(5_000L, stored(5_000L, "a").read("a"))
    }

    /** A recovery-screen restore of wallet B installs without a reset: A's value stays stored. */
    @Test
    fun `another wallet's value is not read`() {
        assertNull(stored(5_000L, "a").read("b"))
    }

    /** It may be a previous wallet's: an earlier version stored it with no id. */
    @Test
    fun `a value an earlier version stored is not read`() {
        assertNull(stored(5_000L, null).read("a"))
    }

    @Test
    fun `nothing is read without a value or an installed wallet`() {
        assertNull(stored(null, "a").read("a"))
        assertNull(stored(5_000L, "a").read(null))
        assertNull(stored(5_000L, null).read(null))
    }
}
