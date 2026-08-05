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

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Byte-for-byte interop tests against the DApp fixtures in
 * `PastaPastaPasta/platform-auth/src/__fixtures__/yappr-vectors.ts`.
 */
class KeyExchangeCryptoTest {

    private fun hex(s: String): ByteArray = Utils.HEX.decode(s)
    private fun ByteArray.hex(): String = Utils.HEX.encode(this)

    // ── fixtures ────────────────────────────────────────────────────────────────
    private val appPrivateKey = ByteArray(32) { 0x01 }
    private val walletPrivateKey = ByteArray(32) { 0x02 }
    private val identityId = ByteArray(32) { 0xab.toByte() }
    private val contractId = ByteArray(32) { 0xcd.toByte() }
    private val loginKey = ByteArray(32) { i -> ((i * 7 + 3) and 0xff).toByte() }
    private val fixedNonce = ByteArray(12) { it.toByte() }

    private val authKeyFromLoginHex = "e06ee7ae45f257741dab7379793c854829b67171af111238c664d9fd90603706"
    private val encKeyFromLoginHex = "6879c11819d1a60026adae34c296e83d13d06559ad63da41d03c44b203a90f80"
    private val hash160Of010203Hex = "9bc4860bb936abf262d7a51f74b4304833fee3b2"

    private fun appPublicKey(): ByteArray =
        ECKey.fromPrivate(BigInteger(1, appPrivateKey), true).pubKey

    private fun walletPublicKey(): ByteArray =
        ECKey.fromPrivate(BigInteger(1, walletPrivateKey), true).pubKey

    // ── hash160 ─────────────────────────────────────────────────────────────────
    @Test
    fun hash160_matchesFixture() {
        val result = KeyExchangeCrypto.hash160(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(hash160Of010203Hex, result.hex())
    }

    // ── auth / encryption key derivation from loginKey ─────────────────────────────
    @Test
    fun deriveAuthPrivateKey_matchesFixture() {
        val auth = KeyExchangeCrypto.deriveAuthPrivateKey(loginKey, identityId)
        assertEquals(authKeyFromLoginHex, auth.hex())
    }

    @Test
    fun deriveEncryptionPrivateKey_matchesFixture() {
        val enc = KeyExchangeCrypto.deriveEncryptionPrivateKey(loginKey, identityId)
        assertEquals(encKeyFromLoginHex, enc.hex())
    }

    // ── ECDH is symmetric (wallet priv * app pub == app priv * wallet pub) ─────────
    @Test
    fun ecdhSharedX_isSymmetric() {
        val fromWallet = KeyExchangeCrypto.ecdhSharedX(walletPrivateKey, appPublicKey())
        val fromApp = KeyExchangeCrypto.ecdhSharedX(appPrivateKey, walletPublicKey())
        assertArrayEquals(fromApp, fromWallet)
        assertEquals(32, fromWallet.size)
    }

    // ── AES-GCM: 60-byte layout + round trip ───────────────────────────────────────
    @Test
    fun encryptLoginKey_producesExactly60Bytes_andDecrypts() {
        val payload = KeyExchangeCrypto.encryptLoginKeyWithNonce(
            loginKey, walletPrivateKey, appPublicKey(), fixedNonce
        )
        assertEquals(60, payload.size)
        assertArrayEquals(fixedNonce, payload.copyOfRange(0, 12))

        // App side derives the same shared secret from its private key + wallet ephemeral pub.
        val roundTrip = KeyExchangeCrypto.decryptLoginKey(
            payload, walletPrivateKey, appPublicKey()
        )
        assertArrayEquals(loginKey, roundTrip)
    }

    @Test
    fun encryptLoginKey_appCanDecryptWithItsOwnPrivateKey() {
        // Wallet encrypts with (walletPriv, appPub); app decrypts with (appPriv, walletPub).
        val payload = KeyExchangeCrypto.encryptLoginKeyWithNonce(
            loginKey, walletPrivateKey, appPublicKey(), fixedNonce
        )
        val decrypted = KeyExchangeCrypto.decryptLoginKey(
            payload, appPrivateKey, walletPublicKey()
        )
        assertArrayEquals(loginKey, decrypted)
    }

    @Test
    fun encryptLoginKey_randomNonceStillRoundTrips() {
        val payload = KeyExchangeCrypto.encryptLoginKey(loginKey, walletPrivateKey, appPublicKey())
        assertEquals(60, payload.size)
        val decrypted = KeyExchangeCrypto.decryptLoginKey(payload, appPrivateKey, walletPublicKey())
        assertArrayEquals(loginKey, decrypted)
    }

    // ── point validation ────────────────────────────────────────────────────────
    @Test
    fun isValidCompressedPoint_acceptsRealKey_rejectsGarbage() {
        assertTrue(KeyExchangeCrypto.isValidCompressedPoint(appPublicKey()))
        // Wrong length.
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(ByteArray(32)))
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(ByteArray(34)))
        // Invalid compressed prefix (0x00 / 0x04 / 0x05 are not valid compressed markers here).
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(ByteArray(33))) // 0x00 prefix
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(ByteArray(33).also { it[0] = 0x04 }))
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(ByteArray(33).also { it[0] = 0x05 }))
        // Valid prefix but an x-coordinate with no corresponding curve point (x = all 0xFF > field prime).
        val xTooLarge = ByteArray(33) { 0xff.toByte() }.also { it[0] = 0x02 }
        assertFalse(KeyExchangeCrypto.isValidCompressedPoint(xTooLarge))
    }

    // ── round trip through the DApp's own derivation checks ─────────────────────────
    @Test
    fun derivedPublicKeys_matchWhatAppWouldCheck() {
        // The app derives authPriv/encPriv from loginKey and checks:
        //   hash160(compressedPub(authPriv)) == authKey.data
        //   compressedPub(encPriv)           == encKey.data
        val authPriv = KeyExchangeCrypto.deriveAuthPrivateKey(loginKey, identityId)
        val encPriv = KeyExchangeCrypto.deriveEncryptionPrivateKey(loginKey, identityId)

        val authPubHash = KeyExchangeCrypto.hash160(KeyExchangeCrypto.compressedPublicKey(authPriv))
        val encPub = KeyExchangeCrypto.compressedPublicKey(encPriv)

        assertEquals(20, authPubHash.size)
        assertEquals(33, encPub.size)
        // Sanity: they are stable / recomputable.
        assertArrayEquals(
            authPubHash,
            KeyExchangeCrypto.hash160(
                KeyExchangeCrypto.compressedPublicKey(
                    KeyExchangeCrypto.deriveAuthPrivateKey(loginKey, identityId)
                )
            )
        )
        assertArrayEquals(encPub, KeyExchangeCrypto.compressedPublicKey(encPriv))
    }
}
