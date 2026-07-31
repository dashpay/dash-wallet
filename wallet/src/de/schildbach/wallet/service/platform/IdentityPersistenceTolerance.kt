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

package de.schildbach.wallet.service.platform

import org.dashj.platform.dpp.identity.Identity

/**
 * WRITE-path mirror of [fetchIdentityToleratingCacheError] (see
 * [IdentityCacheTolerance]).
 *
 * The legacy dashj-platform (org.dashj.platform 4.0.0-RC2) CBOR encoder has no
 * converter for a public key's contract bounds: `IdentityPublicKey.toObject()`
 * puts the raw `SingleContractDocumentType` object straight into the encoded
 * map, and `org.dashj.platform.dpp.util.Cbor` throws
 * `IllegalArgumentException("No converter for ...SingleContractDocumentType...")`
 * when it reaches it. `Identity.toBuffer()` (`BaseObject.toObject()` →
 * drop `protocolVersion` → `encodeProtocolEntity`) therefore throws.
 *
 * The wallet's own newly-registered 6-key identities carry
 * `SingleContractDocumentType(dashpay, "contactRequest")` on keys 4 (ENCRYPTION)
 * and 5 (DECRYPTION), so persisting the identity into the local
 * identity-creation cache (`BlockchainIdentityConfig.saveIdentityPrefs`) throws
 * while updating the creation state, stalling creation at IDENTITY_REGISTERING
 * ("Error Upgrading"). The invite funds are already consumed, so a retry hits
 * the same throw.
 *
 * This is LOCAL persistence only. The bounds still travel on-chain: identity
 * registration is performed by the SDK path, which builds and broadcasts the
 * full bounded keys itself and never routes through this serialization. The
 * local blob is a cache the app reads back for key lookup (by id / purpose /
 * type) and signing — none of which uses the contract bounds.
 *
 * [serializeIdentityToleratingContractBounds] first tries the faithful
 * `toBuffer()`, so an identity WITHOUT contract-bound keys serializes exactly
 * as before. ONLY on that specific CBOR failure it re-serializes a
 * bounds-STRIPPED copy for the local blob. Any other throwable propagates
 * unchanged so real serialization errors are never masked.
 *
 * The stripped blob round-trips cleanly: `Identity.createFromBuffer` (used on
 * read) deserializes a key whose map has no "contractBounds" entry with
 * `contractBounds == null` rather than failing.
 */
internal fun serializeIdentityToleratingContractBounds(identity: Identity): ByteArray = try {
    identity.toBuffer()
} catch (e: IllegalArgumentException) {
    if (isLegacyIdentityCacheCborFailure(e)) {
        encodeIdentityWithoutContractBounds(identity)
    } else {
        throw e
    }
}

/**
 * Reproduces `BaseObject.toBuffer()` (`toObject()` → drop `protocolVersion` →
 * `encodeProtocolEntity`) but removes the un-encodable "contractBounds" entry
 * from every public-key map first.
 *
 * `toObject()` returns a fresh map every call (a new list of fresh per-key
 * maps), so this mutates only throwaway serialization state — never the live
 * [identity] and never anything that gets broadcast on-chain.
 */
private fun encodeIdentityWithoutContractBounds(identity: Identity): ByteArray {
    val obj = identity.toObject().toMutableMap()
    obj.remove("protocolVersion")
    (obj["publicKeys"] as? List<*>)?.forEach { key ->
        @Suppress("UNCHECKED_CAST")
        (key as? MutableMap<String, Any?>)?.remove("contractBounds")
    }
    return identity.encodeProtocolEntity(obj)
}
