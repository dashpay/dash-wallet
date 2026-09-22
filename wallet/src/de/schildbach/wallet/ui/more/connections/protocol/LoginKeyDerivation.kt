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

/**
 * The wallet's deterministic login-key derivation.
 *
 * ## Contract with the DApp (why this must be deterministic)
 * The DashConnect / Yappr protocol requires the wallet to return the SAME 32-byte `loginKey`
 * for the same `(identity, appContractId)` pair on every login. The DApp re-derives an
 * authentication key and an encryption key from `loginKey` (see
 * [KeyExchangeCrypto.deriveAuthPrivateKey] / [KeyExchangeCrypto.deriveEncryptionPrivateKey]) and
 * checks them against the keys registered on the identity. If the wallet ever produced a
 * different `loginKey` for the same app, those derived keys would no longer match what was
 * registered during first-login (QR #2) and the DApp would reject the session.
 *
 * The exact `loginKey` derivation is NOT specified by the public DApp sources (the reference
 * `YAPPR_DET_SIGNER_SPEC.md` is private). The only hard requirement from the DApp code is
 * determinism per `(identity, appContractId)`. This class therefore defines the wallet's OWN
 * recoverability scheme. It must remain stable forever (or be versioned): changing it silently
 * would break every previously-registered app login.
 *
 * ## Chosen scheme (v1)
 * ```
 * loginKey = HKDF-SHA256(
 *     ikm    = privateKeyBytes(BLOCKCHAIN_IDENTITY authentication-chain key at keyIndex),
 *     salt   = identityId bytes (32),
 *     info   = UTF8("dash:login-key:v1") || appContractId bytes (32),
 *     L      = 32
 * )
 * ```
 *
 * ### Rationale
 *  - **Seed-recoverable**: the ikm is a hardened child of the wallet's HD seed via the existing
 *    `AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY` chain (the same infrastructure that
 *    backs the user's Platform identity). Restoring the wallet from its recovery phrase reproduces
 *    that key, hence the same `loginKey`, hence the same app-side auth/encryption keys.
 *  - **Domain-separated**: the fixed ASCII tag `dash:login-key:v1` in `info` isolates this use
 *    from any other HKDF use of the same key material and carries an explicit version. The
 *    `appContractId` in `info` binds the key to a specific app, so different apps get independent
 *    login keys from the same chain key.
 *  - **Identity-bound**: the identity id is the HKDF salt, binding the key to a specific identity.
 *  - **Not the signing key itself**: we never hand the raw chain private key to the app; the app
 *    only ever sees keys derived from `loginKey` (which is itself one HKDF step removed from the
 *    chain key), and only their public halves are registered on-chain.
 *
 * ### keyIndex semantics (v1)
 * `keyIndex` is the index of the key within the `BLOCKCHAIN_IDENTITY` authentication chain used as
 * ikm. For v1 we use a constant index of `0` for all apps (see [DEFAULT_KEY_INDEX]); per-app
 * separation is already provided by the `appContractId` in `info`, so a single chain key is
 * sufficient and keeps recovery trivial. The chosen index is published in the `keyIndex` field of
 * the `loginKeyResponse` document so the scheme is self-describing on-chain.
 */
object LoginKeyDerivation {

    /** Domain-separation / version tag for the login-key HKDF `info`. */
    val LOGIN_KEY_INFO_PREFIX: ByteArray = "dash:login-key:v1".toByteArray(Charsets.UTF_8)

    /**
     * The BLOCKCHAIN_IDENTITY authentication-chain key index used as HKDF ikm for v1.
     * Constant across apps; per-app separation comes from [deriveLoginKey]'s `info`.
     */
    const val DEFAULT_KEY_INDEX = 0

    const val LOGIN_KEY_LENGTH = KeyExchangeCrypto.LOGIN_KEY_LENGTH

    /**
     * Derives the 32-byte login key deterministically.
     *
     * @param chainKeyPrivateBytes the private key bytes of the BLOCKCHAIN_IDENTITY authentication
     *   chain key at [DEFAULT_KEY_INDEX]. Caller is responsible for obtaining (decrypting) and
     *   wiping this.
     * @param identityIdBytes the 32-byte identity id.
     * @param appContractIdBytes the 32-byte app contract id.
     */
    fun deriveLoginKey(
        chainKeyPrivateBytes: ByteArray,
        identityIdBytes: ByteArray,
        appContractIdBytes: ByteArray
    ): ByteArray {
        require(identityIdBytes.size == 32) { "identityId must be 32 bytes" }
        require(appContractIdBytes.size == 32) { "appContractId must be 32 bytes" }
        val info = LOGIN_KEY_INFO_PREFIX + appContractIdBytes
        return KeyExchangeCrypto.hkdfSha256(
            ikm = chainKeyPrivateBytes,
            salt = identityIdBytes,
            info = info,
            length = LOGIN_KEY_LENGTH
        )
    }
}
