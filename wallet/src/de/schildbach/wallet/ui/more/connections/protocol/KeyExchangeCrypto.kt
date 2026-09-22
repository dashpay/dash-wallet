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
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure, Android-free implementation of the cryptographic primitives of the Yappr
 * key-exchange login protocol (the wallet side).
 *
 * Every constant and byte layout here is a faithful port of the DApp reference
 * implementation `PastaPastaPasta/platform-auth` (`src/key-exchange/yappr-protocol.ts`),
 * which uses `@noble/secp256k1` + `@noble/hashes` (RFC-5869 HKDF-SHA256) and WebCrypto
 * AES-256-GCM. The wallet MUST reproduce these byte-for-byte or the DApp will reject
 * (or fail to decrypt) the login response.
 *
 * Interop-critical facts (do not "simplify"):
 *  - ECDH shared secret = the 32-byte big-endian X coordinate of the raw EC point
 *    `walletEphemeralPriv * appEphemeralPub` (noble's `getSharedSecret` returns the
 *    65-byte uncompressed point `0x04||X||Y`; the protocol takes bytes `[1,33)` = X).
 *    There is NO extra hashing of the point before HKDF.
 *  - The AES key = HKDF-SHA256(ikm = sharedX, salt = UTF8("dash:key-exchange:v1"),
 *    info = EMPTY, L = 32).
 *  - The encrypted payload = nonce(12) || AES-256-GCM ciphertext(32) || tag(16) = 60 bytes,
 *    no AAD, 128-bit tag APPENDED (the JCE/WebCrypto default), plaintext = the 32-byte loginKey.
 *  - The login-key-derived keys the DApp checks against the identity:
 *      authPriv = HKDF-SHA256(ikm = loginKey, salt = identityId(32), info = UTF8("auth"), 32)
 *      encPriv  = HKDF-SHA256(ikm = loginKey, salt = identityId(32), info = UTF8("encryption"), 32)
 *
 * This object is stateless. Callers are responsible for zeroing any sensitive byte
 * arrays they own after use; helpers that generate secrets internally wipe their own
 * intermediates.
 */
object KeyExchangeCrypto {

    /** HKDF salt for the ECDH-derived AES key. Exactly `UTF8("dash:key-exchange:v1")`. */
    val KEY_EXCHANGE_HKDF_SALT: ByteArray = "dash:key-exchange:v1".toByteArray(Charsets.UTF_8)

    /** HKDF info for the login-key-derived authentication key. */
    val AUTH_KEY_INFO: ByteArray = "auth".toByteArray(Charsets.UTF_8)

    /** HKDF info for the login-key-derived encryption key. */
    val ENCRYPTION_KEY_INFO: ByteArray = "encryption".toByteArray(Charsets.UTF_8)

    const val COMPRESSED_PUBKEY_LENGTH = 33
    const val NONCE_LENGTH = 12
    const val LOGIN_KEY_LENGTH = 32
    const val GCM_TAG_BITS = 128

    /** nonce(12) || ciphertext(32) || tag(16). */
    const val ENCRYPTED_PAYLOAD_LENGTH = NONCE_LENGTH + LOGIN_KEY_LENGTH + (GCM_TAG_BITS / 8)

    private val secureRandom = SecureRandom()

    // ── hashing ────────────────────────────────────────────────────────────────

    /** RIPEMD160(SHA256(data)) — the DApp's `hash160`. Delegates to dashj's implementation. */
    fun hash160(data: ByteArray): ByteArray = Utils.sha256hash160(data)

    // ── secp256k1 helpers ────────────────────────────────────────────────────────

    /**
     * Validates that [pubKey] is a well-formed 33-byte compressed secp256k1 point that
     * actually lies on the curve, and returns the decoded [ECPoint]. Throws
     * [IllegalArgumentException] otherwise. This MUST be called before feeding an
     * app-supplied ephemeral public key into ECDH (the QR is untrusted input).
     */
    fun decodeCompressedPoint(pubKey: ByteArray): ECPoint {
        require(pubKey.size == COMPRESSED_PUBKEY_LENGTH) {
            "compressed public key must be $COMPRESSED_PUBKEY_LENGTH bytes, was ${pubKey.size}"
        }
        require(pubKey[0] == 0x02.toByte() || pubKey[0] == 0x03.toByte()) {
            "compressed public key must start with 0x02 or 0x03"
        }
        val point = try {
            ECKey.CURVE.curve.decodePoint(pubKey)
        } catch (ex: Exception) {
            throw IllegalArgumentException("invalid secp256k1 point", ex)
        }
        require(point.isValid) { "public key is not a valid curve point" }
        return point
    }

    /** True if [pubKey] is a valid compressed secp256k1 point. Never throws. */
    fun isValidCompressedPoint(pubKey: ByteArray): Boolean = try {
        decodeCompressedPoint(pubKey)
        true
    } catch (ex: IllegalArgumentException) {
        false
    }

    /** The 33-byte compressed public key for a 32-byte private key. */
    fun compressedPublicKey(privateKey: ByteArray): ByteArray =
        ECKey.fromPrivate(BigInteger(1, privateKey), true).pubKey

    // ── ECDH + HKDF (AES key) ─────────────────────────────────────────────────────

    /**
     * Computes the 32-byte ECDH X coordinate exactly as the DApp does:
     * `sharedPoint = walletEphemeralPriv * appEphemeralPub`, then take the affine X
     * as a 32-byte big-endian value (left-padded). This is the ikm to HKDF.
     */
    fun ecdhSharedX(walletEphemeralPriv: ByteArray, appEphemeralPub: ByteArray): ByteArray {
        val appPoint = decodeCompressedPoint(appEphemeralPub)
        val priv = BigInteger(1, walletEphemeralPriv)
        val shared = appPoint.multiply(priv).normalize()
        require(!shared.isInfinity) { "ECDH produced the point at infinity" }
        val x = shared.affineXCoord.encoded // ECFieldElement.getEncoded() is already 32 bytes big-endian
        return leftPad32(x)
    }

    /**
     * Derives the 32-byte AES-256 key from the ECDH shared X coordinate:
     * HKDF-SHA256(ikm = sharedX, salt = "dash:key-exchange:v1", info = EMPTY, L = 32).
     */
    fun deriveAesKey(sharedX: ByteArray): ByteArray =
        hkdfSha256(ikm = sharedX, salt = KEY_EXCHANGE_HKDF_SALT, info = ByteArray(0), length = 32)

    /**
     * Full wallet-side encrypt step. Generates a fresh 12-byte nonce, derives the AES key
     * from ECDH, and returns `nonce || ciphertext || tag` (60 bytes).
     *
     * @param loginKey the 32-byte deterministic login key (plaintext)
     * @param walletEphemeralPriv the wallet's fresh ephemeral private key (32 bytes)
     * @param appEphemeralPub the app's ephemeral public key from the QR (33 bytes, validated)
     */
    fun encryptLoginKey(
        loginKey: ByteArray,
        walletEphemeralPriv: ByteArray,
        appEphemeralPub: ByteArray
    ): ByteArray {
        require(loginKey.size == LOGIN_KEY_LENGTH) { "loginKey must be $LOGIN_KEY_LENGTH bytes" }
        val nonce = ByteArray(NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        return encryptLoginKeyWithNonce(loginKey, walletEphemeralPriv, appEphemeralPub, nonce)
    }

    /**
     * Deterministic variant used by unit tests (fixed nonce) and internally by
     * [encryptLoginKey]. Wipes the derived AES key after use.
     */
    fun encryptLoginKeyWithNonce(
        loginKey: ByteArray,
        walletEphemeralPriv: ByteArray,
        appEphemeralPub: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        require(loginKey.size == LOGIN_KEY_LENGTH) { "loginKey must be $LOGIN_KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "nonce must be $NONCE_LENGTH bytes" }
        val sharedX = ecdhSharedX(walletEphemeralPriv, appEphemeralPub)
        val aesKey = deriveAesKey(sharedX)
        try {
            wipe(sharedX)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            val ciphertextWithTag = cipher.doFinal(loginKey) // 32 + 16 = 48 bytes, tag appended
            val out = ByteArray(ENCRYPTED_PAYLOAD_LENGTH)
            System.arraycopy(nonce, 0, out, 0, NONCE_LENGTH)
            System.arraycopy(ciphertextWithTag, 0, out, NONCE_LENGTH, ciphertextWithTag.size)
            check(out.size == ENCRYPTED_PAYLOAD_LENGTH) {
                "encrypted payload must be $ENCRYPTED_PAYLOAD_LENGTH bytes, was ${out.size}"
            }
            return out
        } finally {
            wipe(aesKey)
        }
    }

    /**
     * Decrypts a `nonce || ciphertext || tag` payload back to the 32-byte login key,
     * given the wallet ephemeral private key and app ephemeral public key. Provided
     * mainly to support the round-trip unit tests; not used in the login flow.
     */
    fun decryptLoginKey(
        encryptedPayload: ByteArray,
        walletEphemeralPriv: ByteArray,
        appEphemeralPub: ByteArray
    ): ByteArray {
        require(encryptedPayload.size == ENCRYPTED_PAYLOAD_LENGTH) {
            "encrypted payload must be $ENCRYPTED_PAYLOAD_LENGTH bytes"
        }
        val nonce = encryptedPayload.copyOfRange(0, NONCE_LENGTH)
        val ciphertextWithTag = encryptedPayload.copyOfRange(NONCE_LENGTH, encryptedPayload.size)
        val sharedX = ecdhSharedX(walletEphemeralPriv, appEphemeralPub)
        val aesKey = deriveAesKey(sharedX)
        try {
            wipe(sharedX)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce)
            )
            val plaintext = cipher.doFinal(ciphertextWithTag)
            check(plaintext.size == LOGIN_KEY_LENGTH) { "decrypted login key must be 32 bytes" }
            return plaintext
        } finally {
            wipe(aesKey)
        }
    }

    // ── login-key-derived keys (auth / encryption) ──────────────────────────────────

    /**
     * authPriv = HKDF-SHA256(ikm = loginKey, salt = identityId(32), info = UTF8("auth"), 32).
     * This is what the DApp derives and whose hash160(compressed pub) it checks against the
     * authentication key registered on the identity.
     */
    fun deriveAuthPrivateKey(loginKey: ByteArray, identityIdBytes: ByteArray): ByteArray {
        require(loginKey.size == LOGIN_KEY_LENGTH) { "loginKey must be 32 bytes" }
        require(identityIdBytes.size == 32) { "identityId must be 32 bytes" }
        return hkdfSha256(loginKey, identityIdBytes, AUTH_KEY_INFO, 32)
    }

    /**
     * encPriv = HKDF-SHA256(ikm = loginKey, salt = identityId(32), info = UTF8("encryption"), 32).
     */
    fun deriveEncryptionPrivateKey(loginKey: ByteArray, identityIdBytes: ByteArray): ByteArray {
        require(loginKey.size == LOGIN_KEY_LENGTH) { "loginKey must be 32 bytes" }
        require(identityIdBytes.size == 32) { "identityId must be 32 bytes" }
        return hkdfSha256(loginKey, identityIdBytes, ENCRYPTION_KEY_INFO, 32)
    }

    // ── HKDF (RFC 5869, SHA-256) ─────────────────────────────────────────────────────

    /**
     * RFC 5869 HKDF-SHA256 (extract + expand). Matches `@noble/hashes` `hkdf(sha256, ...)`.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        generator.generateBytes(out, 0, length)
        return out
    }

    // ── utilities ───────────────────────────────────────────────────────────────────

    /** Left-pads (or, defensively, trims a leading zero from) a big-endian value to exactly 32 bytes. */
    private fun leftPad32(value: ByteArray): ByteArray {
        if (value.size == 32) return value
        if (value.size < 32) {
            val padded = ByteArray(32)
            System.arraycopy(value, 0, padded, 32 - value.size, value.size)
            return padded
        }
        // BigInteger sign byte can add a leading 0x00; strip it.
        require(value.size == 33 && value[0] == 0.toByte()) { "unexpected field element length ${value.size}" }
        return value.copyOfRange(1, 33)
    }

    /** Overwrites a byte array with zeros. Best-effort scrubbing of sensitive material. */
    fun wipe(bytes: ByteArray?) {
        if (bytes != null) bytes.fill(0)
    }
}
