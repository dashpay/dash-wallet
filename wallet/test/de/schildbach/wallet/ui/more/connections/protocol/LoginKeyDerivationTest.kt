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

package de.schildbach.wallet.ui.more.connections.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The login key derivation is the wallet's own recoverability contract. These tests pin its
 * key invariants: determinism per (identity, app) and independence across identities/apps.
 */
class LoginKeyDerivationTest {

    private val chainKey = ByteArray(32) { 0x11 }
    private val identityId = ByteArray(32) { 0xab.toByte() }
    private val contractA = ByteArray(32) { 0xcd.toByte() }
    private val contractB = ByteArray(32) { 0xef.toByte() }

    @Test
    fun deriveLoginKey_isDeterministicForSameInputs() {
        val a = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractA)
        val b = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractA)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun deriveLoginKey_differsPerApp() {
        val a = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractA)
        val b = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractB)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun deriveLoginKey_differsPerIdentity() {
        val a = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractA)
        val b = LoginKeyDerivation.deriveLoginKey(chainKey, ByteArray(32) { 0x01 }, contractA)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun deriveLoginKey_differsPerChainKey() {
        val a = LoginKeyDerivation.deriveLoginKey(chainKey, identityId, contractA)
        val b = LoginKeyDerivation.deriveLoginKey(ByteArray(32) { 0x22 }, identityId, contractA)
        assertFalse(a.contentEquals(b))
    }
}
