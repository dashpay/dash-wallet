/*
 * Copyright 2026 Dash Core Group
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
package de.schildbach.wallet.service.platform

import org.bitcoinj.core.Utils

/**
 * Pure logic for backfilling the wallet's BLOCKCHAIN_IDENTITY authentication key chain with the
 * keys a platform identity actually registered.
 *
 * Why this exists: the legacy dashj-platform signer
 * (`org.dashj.platform.dashpay.callback.WalletSignerCallback.sign`) resolves the private key for
 * a document signature via `AuthenticationKeyChain.findKeyFromPubKey`, which only searches the
 * chain's basic key chain — i.e. keys that were explicitly ISSUED (imported) on that chain. The
 * identity chain is created with lookahead 0, so merely deriving a key (e.g.
 * `AuthenticationKeyChain.getKey(index)` / `getKeyByPath(..., create = true)`) never makes it
 * findable by public key.
 *
 * Identities created by the legacy dashj flow issue their keys during registration
 * (`BlockchainIdentity.privateKeyAtIndex` → `AuthenticationGroupExtension.addNewKey`), but
 * identities created by the Kotlin SDK (canonical 4-key set, derivation index == keyId on the
 * DIP-13 identity-0 chain m/9'/1'/5'/0'/0'/0'/i') reach dashj through the restore path, which
 * issues nothing. Any subsequent legacy-path signature (contact request send/accept, profile
 * create/update) then fails with "signer callback returned 0".
 *
 * This object decides WHICH chain indexes must be issued; the wallet mechanics (derivation,
 * re-encryption, persistence) live in `PlatformRepo.ensureIdentityChainKeys`.
 */
object IdentityKeyChainBackfill {

    /** A registered identity key: its keyId and its on-platform key data. */
    class IdentityKeyRef(val keyId: Int, val keyData: ByteArray)

    /**
     * True if [identityKeyData] (an identity key's on-platform data) refers to
     * [derivedPublicKey] (a serialized public key derived from the wallet's identity chain).
     * Handles both raw public key data (ECDSA_SECP256K1, 33 bytes compressed) and
     * hash160 key data (ECDSA_HASH160, 20 bytes).
     */
    fun matchesKeyData(derivedPublicKey: ByteArray, identityKeyData: ByteArray): Boolean {
        return when {
            identityKeyData.size == derivedPublicKey.size ->
                identityKeyData.contentEquals(derivedPublicKey)
            identityKeyData.size == 20 ->
                identityKeyData.contentEquals(Utils.sha256hash160(derivedPublicKey))
            else -> false
        }
    }

    /**
     * Computes the identity-chain indexes that must be issued (imported into the chain's basic
     * key chain) so that every identity key that was derived from this chain can be found by
     * public key.
     *
     * Assumes the canonical mapping derivation index == keyId (true for both the legacy dashj
     * layout and the SDK layout); a key is only selected when the key derived at that index
     * actually matches the identity key's data, so foreign or non-chain keys are skipped rather
     * than blindly issued.
     *
     * @param identityKeys the identity's registered public keys (keyId + key data)
     * @param derivePublicKey derives the serialized public key at a chain index, or null if the
     *        index cannot be derived
     * @param isIssued true if the chain can already find this public key (nothing to do)
     * @return distinct chain indexes to issue, in ascending order (ascending keeps the chain's
     *         issued-key counter consistent as each key is imported)
     */
    fun indexesToIssue(
        identityKeys: List<IdentityKeyRef>,
        derivePublicKey: (Int) -> ByteArray?,
        isIssued: (ByteArray) -> Boolean
    ): List<Int> {
        return identityKeys
            .asSequence()
            .filter { it.keyId >= 0 }
            .mapNotNull { key ->
                val derived = derivePublicKey(key.keyId) ?: return@mapNotNull null
                if (matchesKeyData(derived, key.keyData) && !isIssued(derived)) key.keyId else null
            }
            .distinct()
            .sorted()
            .toList()
    }
}
