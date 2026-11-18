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

package de.schildbach.wallet.service.platform.sdk

import org.bitcoinj.core.Utils
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's identity reads, so [SdkIdentityQueries] is host-JVM unit-testable
 * without `libdash_sdk`. Each call returns what the SDK returned; failures propagate unchanged.
 */
interface SdkIdentityReadSource {
    /** `identities.fetchByPublicKeyHash`: one identity's JSON, or null when no identity owns the unique key hash. */
    suspend fun identityJsonByPublicKeyHash(hashHex: String): String?

    /**
     * `identities.fetchByNonUniquePublicKeyHash`: a JSON array holding at most one identity (one
     * page), the first after [startAfterBase58] when it is set.
     */
    suspend fun identitiesJsonByNonUniquePublicKeyHash(hashHex: String, startAfterBase58: String?): String?

    /** `identities.fetchBalance`, in credits. */
    suspend fun creditBalance(identityIdBase58: String): Long?
}

/** The running SDK, booting it first; throws when the runtime cannot start. */
internal suspend fun DashSdkService.startedSdk(): org.dashfoundation.dashsdk.Sdk {
    ensureStarted()
    return checkNotNull(sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
}

/**
 * The SDK's credit balance read for [identityIdBase58], booting the SDK first. Shared by
 * [DashSdkIdentityReadSource] and the fee-ledger reads of the DashPay-write and username-creation
 * sources, so every SDK balance read takes the same path.
 *
 * The pinned SDK throws (an `InternalError`, "Identity balance not found") for an identity that
 * does not exist rather than returning null; null only comes back when the native result is not
 * a decimal number.
 */
internal suspend fun fetchIdentityCreditBalance(service: DashSdkService, identityIdBase58: String): Long? =
    service.startedSdk().identities.fetchBalance(identityIdBase58)

/** Production [SdkIdentityReadSource]: boots the SDK on demand. */
internal class DashSdkIdentityReadSource(
    private val service: DashSdkService
) : SdkIdentityReadSource {
    override suspend fun identityJsonByPublicKeyHash(hashHex: String): String? =
        service.startedSdk().identities.fetchByPublicKeyHash(hashHex)

    override suspend fun identitiesJsonByNonUniquePublicKeyHash(hashHex: String, startAfterBase58: String?): String? =
        service.startedSdk().identities.fetchByNonUniquePublicKeyHash(hashHex, startAfterBase58)

    override suspend fun creditBalance(identityIdBase58: String): Long? =
        fetchIdentityCreditBalance(service, identityIdBase58)
}

/**
 * True for the SDK failures that mean the network could not be reached or did not answer in
 * time: [DashSdkError.NetworkError] (rs-sdk-ffi maps DAPI transport errors, exhausted retries,
 * `NoAvailableAddressesToRetry`, stale nodes and an unavailable quorum source to it) and
 * [DashSdkError.Timeout]. These are the SDK's counterparts of the legacy client's
 * `MaxRetriesReachedException` and `NoAvailableAddressesForRetryException`.
 */
internal fun isSdkNetworkUnavailable(error: Throwable): Boolean =
    error is DashSdkError.NetworkError || error is DashSdkError.Timeout

/**
 * Identity reads through the Dash Platform Kotlin SDK: discovery by the first identity key's
 * hash and the identity credit balance. Neither touches the legacy `Platform` client or its
 * model enums, so neither loads `libsdklib`; the caller converts a found [SdkIdentityRecord] to the
 * legacy model once that library is loaded.
 *
 * Failures propagate unchanged, including the SDK runtime failing to start and network failures
 * ([isSdkNetworkUnavailable]); each caller applies its own contract.
 */
@Singleton
class SdkIdentityQueries internal constructor(
    private val source: SdkIdentityReadSource
) {
    @Inject
    constructor(sdkService: DashSdkService) : this(DashSdkIdentityReadSource(sdkService))

    /**
     * The identity that owns the 20-byte [pubKeyHash], or null when none does.
     *
     * Same lookup as the legacy `fetch_identity_with_keyhash_sdk` (dashj-platform 5.0.1): the
     * unique key-hash index first, and only when that proves no identity, the non-unique index
     * (an `ECDSA_HASH160` key stores its hash there). The non-unique answer counts only when it
     * is exactly one identity; two or more is reported as none, as the legacy lookup did.
     *
     * @throws SdkIdentityJsonException when the SDK's identity JSON cannot be read
     */
    suspend fun identityByPublicKeyHash(pubKeyHash: ByteArray): SdkIdentityRecord? {
        require(pubKeyHash.size == PUBLIC_KEY_HASH_SIZE) { "public key hash must be $PUBLIC_KEY_HASH_SIZE bytes" }
        val hashHex = Utils.HEX.encode(pubKeyHash)
        source.identityJsonByPublicKeyHash(hashHex)?.let { return SdkIdentityJson.parseIdentity(it) }
        return singleNonUniqueIdentity(hashHex)
    }

    private suspend fun singleNonUniqueIdentity(hashHex: String): SdkIdentityRecord? {
        val first = source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null)
            ?.let { SdkIdentityJson.parseIdentityArray(it) }
            .orEmpty()
        if (first.isEmpty()) return null
        if (first.size > 1) throw SdkIdentityJsonException("non-unique key-hash page holds more than one identity")
        val found = first.single()
        val next = source.identitiesJsonByNonUniquePublicKeyHash(hashHex, found.id.toString())
            ?.let { SdkIdentityJson.parseIdentityArray(it) }
            .orEmpty()
        if (next.isNotEmpty()) {
            log.warn("more than one identity holds the first identity key's hash in the non-unique index; treating it as none")
            return null
        }
        return found
    }

    /**
     * [identityId]'s credit balance (credits, not duffs) as the SDK returns it; see
     * [fetchIdentityCreditBalance] for when that is null.
     */
    suspend fun creditBalance(identityId: Identifier): Long? = source.creditBalance(identityId.toString())

    companion object {
        private val log = LoggerFactory.getLogger(SdkIdentityQueries::class.java)
        private const val PUBLIC_KEY_HASH_SIZE = 20
    }
}
