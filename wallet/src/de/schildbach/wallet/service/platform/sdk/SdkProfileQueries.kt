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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's native document search (`sdk.documents.search`
 * against the DashPay contract's `profile` type), so the
 * mapping/orchestration in [SdkProfileQueries] is host-JVM unit-testable
 * with fake JSON — the native calls need `libdash_sdk`.
 */
interface SdkProfileSource {
    /**
     * Search `profile` documents on the DashPay contract. [whereJson] /
     * [orderByJson] are rs-sdk-ffi where/order clause arrays
     * (`[{field, operator, value}]` / `[{field, ascending}]`); limit 0 =
     * server default. Returns the search payload JSON, or null if the SDK
     * returned no payload.
     */
    suspend fun searchProfiles(whereJson: String, orderByJson: String?, limit: Int): String?
}

/**
 * Production [SdkProfileSource]: boots the SDK on demand, fetches (and
 * caches, per SDK handle) the DashPay [org.dashfoundation.dashsdk.queries.DataContractRef],
 * and queries `sdk.documents`.
 */
internal class DashSdkProfileSource(
    private val service: DashSdkService,
    private val dashPayContractId: () -> Identifier?
) : SdkProfileSource {

    private var cachedContract: org.dashfoundation.dashsdk.queries.DataContractRef? = null
    private var cachedForSdk: org.dashfoundation.dashsdk.Sdk? = null

    private suspend fun sdk(): org.dashfoundation.dashsdk.Sdk {
        service.ensureStarted()
        return checkNotNull(service.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
    }

    private suspend fun contract(sdk: org.dashfoundation.dashsdk.Sdk): org.dashfoundation.dashsdk.queries.DataContractRef {
        synchronized(this) {
            cachedContract?.takeIf { cachedForSdk === sdk }?.let { return it }
        }
        val id = checkNotNull(dashPayContractId()) { "DashPay contract id unavailable" }
        val fetched = sdk.contracts.fetch(id.toString())
        synchronized(this) {
            // A racing fetch may have won; keep the first and close ours.
            val existing = cachedContract?.takeIf { cachedForSdk === sdk }
            if (existing != null) {
                fetched.close()
                return existing
            }
            cachedContract = fetched
            cachedForSdk = sdk
            return fetched
        }
    }

    override suspend fun searchProfiles(whereJson: String, orderByJson: String?, limit: Int): String? {
        val sdk = sdk()
        return sdk.documents.search(
            contract = contract(sdk),
            documentType = SdkProfileMapping.DOCUMENT_TYPE,
            whereJson = whereJson,
            orderByJson = orderByJson,
            limit = limit
        )
    }
}

/**
 * Pure JSON → dashj-model mapping for SDK profile document queries — the
 * Phase 3d translation layer (`docs/kotlin-sdk-migration-plan.md`).
 *
 * The FFI (`rs-sdk-ffi/src/document/queries/search.rs`) serializes each
 * document via dpp `to_object()`: system fields at the root (`$id`,
 * `$ownerId` as base58, `$revision`, `$createdAt`/`$updatedAt` as millis)
 * plus the profile properties (`displayName`, `publicMessage`, `avatarUrl`
 * as strings; `avatarHash`, `avatarFingerprint` as base64 strings). The
 * payload is either a bare JSON array of those objects or an object wrapping
 * it as `{"documents":[…],"total_count":N}` — both shapes are accepted.
 *
 * Synthesized [Document]s carry exactly what the routed callers consume:
 * `ownerId` (map keying), `createdAt`/`updatedAt`, and the data fields read
 * by `DashPayProfile.fromDocument` (whose byte-array getter accepts base64
 * strings via `HashUtils.byteArrayfromBase64orByteArray`, so avatar hashes
 * pass through untouched).
 *
 * Everything here is pure JVM (dashj-platform model classes + Gson): no
 * native calls, no Android, so it is covered by host unit tests.
 */
object SdkProfileMapping {

    const val DOCUMENT_TYPE = "profile"

    /**
     * A minimal, locally-built DashPay [DataContract] — just enough for the
     * non-null `Document.dataContract` field. Never sent anywhere and never
     * read by the routed callers; avoids a dashj network fetch of the real
     * contract on the SDK path.
     */
    fun minimalDashPayContract(contractId: Identifier): DataContract = DataContract(
        hashMapOf<String, Any?>(
            "\$id" to contractId.toString(),
            "ownerId" to ByteArray(32),
            "protocolVersion" to 1,
            "version" to 1,
            "documents" to hashMapOf<String, Any?>(DOCUMENT_TYPE to hashMapOf<String, Any?>())
        )
    )

    /**
     * rs-sdk-ffi where clause `$ownerId in [ids]` — mirrors dashj
     * `Profiles.getListHelper`'s `whereIn("$ownerId", ids)`. Identifiers are
     * base58, so they need no JSON escaping.
     */
    fun whereOwnerIdIn(ownerIds: List<Identifier>): String {
        val values = ownerIds.joinToString(",") { "\"$it\"" }
        return """[{"field":"${'$'}ownerId","operator":"in","value":[$values]}]"""
    }

    /**
     * rs-sdk-ffi where clause `$ownerId == id` — mirrors dashj
     * `Profiles.get(userId)`.
     */
    fun whereOwnerIdEquals(ownerId: Identifier): String =
        """[{"field":"${'$'}ownerId","operator":"=","value":"$ownerId"}]"""

    /** Drive requires ordering on the `in` field; dashj orders the same way. */
    const val ORDER_BY_OWNER_ID = """[{"field":"${'$'}ownerId","ascending":true}]"""

    /**
     * Map a document-search payload to synthetic profile [Document]s.
     * Returns null if the payload is not a JSON array (bare or wrapped in
     * `{"documents": …}`) — caller falls back to dashj. Entries missing the
     * required `$ownerId` are skipped defensively.
     */
    fun profileDocumentsFromJson(json: String, contract: DataContract): List<Document>? {
        val array = documentsArray(parseOrNull(json)) ?: return null
        return array.mapNotNull { element ->
            val obj = (element as? JsonObject) ?: return@mapNotNull null
            val ownerId = obj.stringOrNull("\$ownerId") ?: return@mapNotNull null
            // Malformed owner ids invalidate the whole payload keying, but a
            // single bad entry shouldn't break the rest — skip it.
            try {
                Identifier.from(ownerId)
            } catch (e: Exception) {
                return@mapNotNull null
            }
            profileDocument(obj, ownerId, contract)
        }
    }

    private fun profileDocument(obj: JsonObject, ownerId: String, contract: DataContract): Document {
        // Properties may sit at the root (dpp to_object) or under "data"
        // (other FFI surfaces) — read both.
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        fun property(key: String): JsonElement? = obj.get(key) ?: data?.get(key)

        val map = hashMapOf<String, Any?>(
            "\$id" to (obj.stringOrNull("\$id")?.let { idOrNull(it) } ?: SYNTHETIC_DOCUMENT_ID),
            "\$type" to DOCUMENT_TYPE,
            "\$dataContractId" to contract.id.toString(),
            "\$ownerId" to ownerId,
            "\$revision" to (obj.longOrNull("\$revision") ?: 1L)
        )
        obj.longOrNull("\$createdAt")?.let { map["\$createdAt"] = it }
        obj.longOrNull("\$updatedAt")?.let { map["\$updatedAt"] = it }

        // String fields; DashPayProfile.fromDocument reads them as String.
        for (key in listOf("displayName", "publicMessage", "avatarUrl")) {
            (property(key) as? JsonPrimitive)?.takeIf { it.isString }?.let { map[key] = it.asString }
        }
        // Byte-array fields; left as base64 strings —
        // HashUtils.byteArrayfromBase64orByteArray decodes them.
        for (key in listOf("avatarHash", "avatarFingerprint")) {
            (property(key) as? JsonPrimitive)?.takeIf { it.isString }?.let { map[key] = it.asString }
        }

        return Document(map, contract)
    }

    /** Accept `[…]` or `{"documents":[…]}`; anything else is malformed. */
    private fun documentsArray(element: JsonElement?): JsonArray? = when {
        element == null -> null
        element.isJsonArray -> element.asJsonArray
        element.isJsonObject ->
            element.asJsonObject.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray
        else -> null
    }

    private fun idOrNull(base58: String): ByteArray? =
        try {
            Identifier.from(base58).toBuffer()
        } catch (e: Exception) {
            null
        }

    private fun parseOrNull(json: String): JsonElement? =
        try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    /**
     * The document id is never read by the routed profile callers; entries
     * without one share a fixed 32-byte placeholder id.
     */
    private val SYNTHETIC_DOCUMENT_ID: ByteArray get() = ByteArray(32)
}

/**
 * Phase 3d facade: routes the wallet's DashPay profile reads to the Dash
 * Platform Kotlin SDK behind the SAME runtime flag as the Phase 3c DPNS
 * reads, [DashPayConfig.USE_KOTLIN_SDK_DPNS_READS] (default OFF).
 *
 * ## Contract (identical to [SdkUsernameQueries])
 *
 * - Every public method returns **null when the SDK path must not be used**
 *   — flag off, SDK bootstrap failure, native query failure, or malformed
 *   payload — and the call site runs its existing dashj-platform code
 *   unchanged.
 * - Flag off (the default) leaves the dashj path byte-for-byte intact.
 * - The flag is re-read per lookup; toggles take effect on the next call.
 *
 * Routed call sites (the wallet's production profile reads):
 * - [de.schildbach.wallet.ui.dashpay.PlatformRepo.updateDashPayProfile]
 *   (single, `platform.profiles.get`),
 * - [de.schildbach.wallet.service.platform.IdentityRepositoryImpl.searchUsernames]
 *   (batch, `platform.profiles.getList`),
 * - [de.schildbach.wallet.service.platform.PlatformSynchronizationService.updateContactProfiles]
 *   (batch, `platform.profiles.getList(ids, lastContactRequestTime)` — whose
 *   timestamp dashj's `getListHelper` ignores, verified against
 *   dashj-platform 4.0.0-RC2 bytecode, so the SDK query needs no `$updatedAt`
 *   clause for parity).
 *
 * Query parity with dashj `Profiles`: `dashpay.profile` documents,
 * `whereIn($ownerId, ids)` + `orderBy($ownerId)` batched in chunks of 100
 * (getList), `where($ownerId == id)` (get).
 */
@Singleton
class SdkProfileQueries internal constructor(
    private val source: SdkProfileSource,
    private val dashPayConfig: DashPayConfig,
    private val dashPayContractId: () -> Identifier?
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkProfileSource(
            sdkService,
            { platformService.platform.apps["dashpay"]?.contractId }
        ),
        dashPayConfig = dashPayConfig,
        dashPayContractId = { platformService.platform.apps["dashpay"]?.contractId }
    )

    /**
     * SDK-path replacement for `platform.profiles.get(userId)` in
     * `PlatformRepo.updateDashPayProfile`.
     *
     * @return `Optional.of(document)` when the profile exists,
     *   `Optional.empty()` when the SDK definitively reported no profile
     *   (dashj parity: `profiles.get` returns null then), or **null** when
     *   the caller must fall back to dashj.
     */
    suspend fun getProfileDocumentOrNull(userId: String): Optional<Document>? {
        if (!isEnabled()) return null
        return try {
            val contract = contractOrNull() ?: return null
            val ownerId = Identifier.from(userId)
            val json = source.searchProfiles(
                whereJson = SdkProfileMapping.whereOwnerIdEquals(ownerId),
                orderByJson = null,
                limit = 1
            ) ?: return Optional.empty()
            val documents = SdkProfileMapping.profileDocumentsFromJson(json, contract)
            if (documents == null) {
                log.warn("SDK profile get returned malformed payload; falling back to dashj")
                return null
            }
            Optional.ofNullable(documents.firstOrNull())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK profile get failed; falling back to dashj for profiles.get", t)
            null
        }
    }

    /**
     * SDK-path replacement for `platform.profiles.getList(userIds[, time])`
     * in `IdentityRepository.searchUsernames` and
     * `PlatformSyncService.updateContactProfiles`.
     *
     * @return the (possibly empty) synthetic profile-document list, or
     *   **null** when the caller must fall back to dashj.
     */
    suspend fun getProfileDocumentsOrNull(userIds: List<Identifier>): List<Document>? {
        if (!isEnabled()) return null
        if (userIds.isEmpty()) return emptyList()
        return try {
            val contract = contractOrNull() ?: return null
            val documents = ArrayList<Document>(userIds.size)
            // dashj Profiles.getList batches by 100 (Drive's in-clause cap).
            for (chunk in userIds.chunked(GET_LIST_CHUNK_SIZE)) {
                val json = source.searchProfiles(
                    whereJson = SdkProfileMapping.whereOwnerIdIn(chunk),
                    orderByJson = SdkProfileMapping.ORDER_BY_OWNER_ID,
                    limit = chunk.size
                ) ?: continue // no payload = no documents for this chunk
                val mapped = SdkProfileMapping.profileDocumentsFromJson(json, contract)
                if (mapped == null) {
                    log.warn("SDK profile getList returned malformed payload; falling back to dashj")
                    return null
                }
                documents.addAll(mapped)
            }
            documents
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK profile getList failed; falling back to dashj for profiles.getList", t)
            null
        }
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DPNS_READS; keeping dashj path", e)
        false
    }

    private fun contractOrNull(): DataContract? {
        val id = dashPayContractId()
        if (id == null) {
            log.warn("DashPay contract id unavailable; falling back to dashj")
            return null
        }
        return SdkProfileMapping.minimalDashPayContract(id)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkProfileQueries::class.java)

        private const val GET_LIST_CHUNK_SIZE = 100
    }
}
