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
import org.dashj.platform.dpp.identifier.Identifier
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

/**
 * The Kotlin SDK's native library (`libdash_sdk`) could not be loaded or linked. Thrown in place
 * of the [LinkageError] (its [cause]) by [DashSdkIdentityReadSource], so callers whose handlers
 * catch [Exception] see an ordinary failure instead of an [Error] that escapes them and crashes
 * the coroutine.
 */
class SdkNativeLibraryUnavailableException(cause: LinkageError) :
    IllegalStateException("Dash Platform SDK native library unavailable", cause)

/**
 * An SDK identity round trip did not answer within its [boundedSdkPlatformQuery] budget. The answer
 * is unknown, not absent: callers must treat it as a failure, never as "no identity" or as a
 * balance of nothing.
 */
class SdkIdentityQueryTimeoutException(label: String, timeoutMs: Long) :
    IllegalStateException("Platform did not answer $label within $timeoutMs ms")

/**
 * More than one identity holds the first identity key's hash in the non-unique key-hash index.
 * The owner is ambiguous, not absent: callers must not read it as "no identity" (and go on to
 * register one).
 */
class SdkAmbiguousIdentityException :
    IllegalStateException("more than one identity holds the first identity key's hash; the owner is ambiguous")

/**
 * Production [SdkIdentityReadSource]: boots the SDK on demand. A [LinkageError] from booting or
 * calling the SDK (`Sdk.initialize()` loads `libdash_sdk`) becomes
 * [SdkNativeLibraryUnavailableException]; every other failure propagates unchanged. Not every
 * [Throwable]: unrelated fatal VM errors still propagate (as in
 * `DashSdkServiceImpl.protocolVersionOrUnknown`).
 */
internal class DashSdkIdentityReadSource(
    private val service: DashSdkService
) : SdkIdentityReadSource {
    override suspend fun identityJsonByPublicKeyHash(hashHex: String): String? = nativeRead {
        service.startedSdk().identities.fetchByPublicKeyHash(hashHex)
    }

    override suspend fun identitiesJsonByNonUniquePublicKeyHash(hashHex: String, startAfterBase58: String?): String? =
        nativeRead { service.startedSdk().identities.fetchByNonUniquePublicKeyHash(hashHex, startAfterBase58) }

    override suspend fun creditBalance(identityIdBase58: String): Long? = nativeRead {
        fetchIdentityCreditBalance(service, identityIdBase58)
    }

    private inline fun <T> nativeRead(read: () -> T): T = try {
        read()
    } catch (e: LinkageError) {
        throw SdkNativeLibraryUnavailableException(e)
    }
}

/**
 * Identity reads through the Dash Platform Kotlin SDK: discovery by the first identity key's
 * hash and the identity credit balance. Neither touches the legacy `Platform` client or its
 * model enums, so neither loads `libsdklib`; the caller converts a found [SdkIdentityRecord] to the
 * legacy model once that library is loaded. There is no legacy fallback on any network.
 *
 * Each SDK round trip runs under [boundedSdkPlatformQuery] ([timeoutMs], by default
 * [SDK_PLATFORM_QUERY_TIMEOUT_MS]). With no fallback behind these reads, a timeout is a failure:
 * it throws [SdkIdentityQueryTimeoutException] ("unknown"), and is never reported as the SDK's own
 * null (no identity / no balance). The abandoned native call finishes in the background.
 *
 * Every failure propagates, including the SDK runtime failing to start (on devnet it cannot: the
 * wallet gives the SDK no devnet quorum URL), network failures and timeouts; each caller applies
 * its own contract.
 */
@Singleton
class SdkIdentityQueries internal constructor(
    private val source: SdkIdentityReadSource,
    private val timeoutMs: Long = SDK_PLATFORM_QUERY_TIMEOUT_MS
) {
    @Inject
    constructor(sdkService: DashSdkService) : this(DashSdkIdentityReadSource(sdkService))

    /**
     * The identity that owns the 20-byte [pubKeyHash], or null when Platform proves none does.
     *
     * Same lookup as the legacy `fetch_identity_with_keyhash_sdk` (dashj-platform 5.0.1): the
     * unique key-hash index first, and only when that proves no identity, the non-unique index
     * (an `ECDSA_HASH160` key stores its hash there). The non-unique answer counts only when it
     * is exactly one identity; two or more throws [SdkAmbiguousIdentityException] (the legacy
     * lookup reported none, which now would read as a proved absence). Each of the (up to three)
     * round trips is bounded separately.
     *
     * @throws SdkIdentityJsonException when the SDK's identity JSON cannot be read
     * @throws SdkIdentityQueryTimeoutException when a round trip exceeds its budget
     * @throws SdkAmbiguousIdentityException when more than one identity holds the key hash
     */
    suspend fun identityByPublicKeyHash(pubKeyHash: ByteArray): SdkIdentityRecord? {
        require(pubKeyHash.size == PUBLIC_KEY_HASH_SIZE) { "public key hash must be $PUBLIC_KEY_HASH_SIZE bytes" }
        val hashHex = Utils.HEX.encode(pubKeyHash)
        bounded("identities.fetchByPublicKeyHash") { source.identityJsonByPublicKeyHash(hashHex) }
            ?.let { return SdkIdentityJson.parseIdentity(it) }
        return singleNonUniqueIdentity(hashHex)
    }

    private suspend fun singleNonUniqueIdentity(hashHex: String): SdkIdentityRecord? {
        val first = bounded("identities.fetchByNonUniquePublicKeyHash[first]") {
            source.identitiesJsonByNonUniquePublicKeyHash(hashHex, null)
        }?.let { SdkIdentityJson.parseIdentityArray(it) }.orEmpty()
        if (first.isEmpty()) return null
        if (first.size > 1) throw SdkIdentityJsonException("non-unique key-hash page holds more than one identity")
        val found = first.single()
        val next = bounded("identities.fetchByNonUniquePublicKeyHash[next]") {
            source.identitiesJsonByNonUniquePublicKeyHash(hashHex, found.id.toString())
        }?.let { SdkIdentityJson.parseIdentityArray(it) }.orEmpty()
        if (next.isNotEmpty()) throw SdkAmbiguousIdentityException()
        return found
    }

    /**
     * [identityId]'s credit balance (credits, not duffs) as the SDK returns it; see
     * [fetchIdentityCreditBalance] for when that is null.
     *
     * @throws SdkIdentityQueryTimeoutException when the read exceeds its budget
     */
    suspend fun creditBalance(identityId: Identifier): Long? =
        bounded("identities.fetchBalance") { source.creditBalance(identityId.toString()) }

    /**
     * One bounded round trip: the SDK's own result, null included, or a throw. The helper's null
     * return (budget expired) becomes [SdkIdentityQueryTimeoutException], so it cannot be confused
     * with the SDK's null.
     */
    private suspend fun <T : Any> bounded(label: String, read: suspend () -> T?): T? {
        val boxed = boundedSdkPlatformQuery(label, timeoutMs, read)
            ?: throw SdkIdentityQueryTimeoutException(label, timeoutMs)
        return boxed.orElse(null)
    }

    companion object {
        private const val PUBLIC_KEY_HASH_SIZE = 20
    }
}
