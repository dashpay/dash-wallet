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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.PlatformService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.contract.DataContract
import org.dashj.platform.dpp.document.Document
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's native DPNS queries (`sdk.dpns`), so the
 * mapping/orchestration in [SdkUsernameQueries] is host-JVM unit-testable
 * with fake JSON — the native calls themselves need `libdash_sdk`.
 */
interface SdkDpnsSource {
    /**
     * Resolve a DPNS name to its record JSON (`{"identityId": "<base58>"}`),
     * or null if the SDK returned no payload. "Name not found" surfaces as
     * a thrown [DashSdkError] (see [SdkDpnsMapping.isNotFound]).
     */
    suspend fun resolve(name: String): String?

    /**
     * Prefix-search DPNS names; returns a JSON array of
     * `{"label","normalizedLabel","fullName","ownerId","recordsIdentityId"?}`
     * objects, or null if the SDK returned no payload. limit 0 = server default.
     */
    suspend fun search(prefix: String, limit: Int): String?
}

/** Production [SdkDpnsSource]: boots the SDK on demand and queries `sdk.dpns`. */
internal class DashSdkDpnsSource(private val service: DashSdkService) : SdkDpnsSource {

    private suspend fun sdk(): org.dashfoundation.dashsdk.Sdk {
        service.ensureStarted()
        return checkNotNull(service.sdkOrNull()) { "SDK runtime missing after ensureStarted()" }
    }

    override suspend fun resolve(name: String): String? = sdk().dpns.resolve(name)

    override suspend fun search(prefix: String, limit: Int): String? =
        sdk().dpns.search(prefix, limit)
}

/**
 * Pure JSON → dashj-model mapping for the Kotlin SDK's DPNS query results —
 * the Phase 3c translation layer (`docs/kotlin-sdk-migration-plan.md`).
 *
 * The SDK returns *projections* of the DPNS domain document (identity id for
 * resolve; label/owner tuples for search), not full documents. This mapper
 * synthesizes minimal [Document]s carrying exactly the fields the wallet's
 * DPNS read path consumes ([org.dashj.platform.sdk.platform.DomainDocument]:
 * `label`, `normalizedLabel`, `records.identity` → `dashUniqueIdentityId`,
 * parent domain). Fields the SDK does not provide (`$createdAt`, alias
 * records, preorder salt) are left absent — no current caller on the routed
 * paths reads them (verified: `PlatformSyncService`, `RequestUserNameViewModel`,
 * `IdentityRepository.searchUsernames` pipeline).
 *
 * Everything here is pure JVM (dashj-platform model classes + Gson): no
 * native calls, no Android, so it is covered by host unit tests.
 */
object SdkDpnsMapping {

    /**
     * True when [t] is the SDK's "name is not registered" outcome, which the
     * FFI reports as an error rather than a null payload
     * (`rs-sdk-ffi/src/dpns/queries/resolve.rs` returns
     * `InternalError("Name '<x>' not found")`; a dedicated NotFound code is
     * an SDK gap). Matching NotFound too keeps this correct if the FFI is
     * fixed to use the proper code.
     */
    fun isNotFound(t: Throwable): Boolean =
        t is DashSdkError.NotFound ||
            (t is DashSdkError && t.message?.contains("not found", ignoreCase = true) == true)

    /**
     * A minimal, locally-built DPNS [DataContract] — just enough for the
     * non-null `Document.dataContract` field. Never sent anywhere and never
     * read by the routed callers; avoids a dashj network fetch of the real
     * contract on the SDK path.
     */
    fun minimalDpnsContract(contractId: Identifier): DataContract = DataContract(
        hashMapOf<String, Any?>(
            "\$id" to contractId.toString(),
            "ownerId" to ByteArray(32),
            "protocolVersion" to 1,
            "version" to 1,
            "documents" to hashMapOf<String, Any?>(DOCUMENT_TYPE to hashMapOf<String, Any?>())
        )
    )

    /**
     * Map a `dpns.resolve` payload (`{"identityId": "<base58>"}`) to a
     * synthetic domain [Document], or null if the JSON is malformed
     * (caller falls back to dashj).
     *
     * The SDK does not return the document's label, so [label] /
     * [normalizedLabel] are the caller's query values — matching what every
     * routed consumer compares against.
     */
    fun documentFromResolveJson(
        json: String,
        label: String,
        normalizedLabel: String,
        contract: DataContract
    ): Document? {
        val obj = parseOrNull(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val identityId = obj.stringOrNull("identityId") ?: return null
        return domainDocument(
            label = label,
            normalizedLabel = normalizedLabel,
            parentDomain = Names.DEFAULT_PARENT_DOMAIN,
            ownerId = identityId,
            identityId = identityId,
            contract = contract
        )
    }

    /**
     * Map a `dpns.search` payload (JSON array) to synthetic domain
     * [Document]s. Returns null if the payload is not a JSON array
     * (caller falls back to dashj); entries missing required fields are
     * skipped defensively.
     */
    fun documentsFromSearchJson(json: String, contract: DataContract): List<Document>? {
        val array = parseOrNull(json)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = (element as? JsonObject) ?: return@mapNotNull null
            val label = obj.stringOrNull("label") ?: return@mapNotNull null
            val ownerId = obj.stringOrNull("ownerId") ?: return@mapNotNull null
            val normalizedLabel = obj.stringOrNull("normalizedLabel")
                ?: Names.normalizeString(label)
            // The DPNS v1 contract keeps the resolving identity in
            // records.identity; the FFI surfaces it as recordsIdentityId and
            // for standard registrations it equals the owner.
            val identityId = obj.stringOrNull("recordsIdentityId") ?: ownerId
            val parentDomain = obj.stringOrNull("fullName")
                ?.substringAfter('.', missingDelimiterValue = "")
                ?.takeIf { it.isNotEmpty() }
                ?: Names.DEFAULT_PARENT_DOMAIN
            domainDocument(label, normalizedLabel, parentDomain, ownerId, identityId, contract)
        }
    }

    private fun domainDocument(
        label: String,
        normalizedLabel: String,
        parentDomain: String,
        ownerId: String,
        identityId: String,
        contract: DataContract
    ): Document = Document(
        hashMapOf<String, Any?>(
            "\$id" to SYNTHETIC_DOCUMENT_ID,
            "\$type" to DOCUMENT_TYPE,
            "\$dataContractId" to contract.id.toString(),
            "\$ownerId" to ownerId,
            "\$revision" to 1L,
            "label" to label,
            "normalizedLabel" to normalizedLabel,
            "parentDomainName" to parentDomain,
            "normalizedParentDomainName" to parentDomain,
            "records" to hashMapOf<String, Any?>("identity" to identityId),
            "subdomainRules" to hashMapOf<String, Any?>("allowSubdomains" to false)
        ),
        contract
    )

    private fun parseOrNull(json: String): JsonElement? =
        try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            null
        }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private const val DOCUMENT_TYPE = "domain"

    /**
     * The SDK's DPNS projections do not include the document id and no
     * routed caller reads it, so synthetic documents share a fixed 32-byte
     * placeholder id.
     */
    private val SYNTHETIC_DOCUMENT_ID: ByteArray get() = ByteArray(32)
}

/**
 * Phase 3c facade: routes the wallet's read-only DPNS username lookups to
 * the Dash Platform Kotlin SDK behind the runtime flag
 * [DashPayConfig.USE_KOTLIN_SDK_DPNS_READS] (default OFF).
 *
 * ## Contract (load-bearing)
 *
 * - Every public method returns **null when the SDK path must not be used**
 *   — flag off, SDK bootstrap failure, native query failure, or malformed
 *   payload — and the call site then runs its existing dashj-platform code
 *   unchanged. The SDK path can therefore never cause user-visible breakage.
 * - Flag off (the default) leaves the dashj path byte-for-byte intact; the
 *   only addition is a local DataStore flag read per lookup.
 * - Flag toggles take effect on the next lookup (the flag is re-read every
 *   call; no restart needed) — including toggling OFF as an instant fallback.
 *
 * Routed call sites (the wallet's production DPNS reads):
 * - [de.schildbach.wallet.ui.dashpay.PlatformRepo.getUsername] — username
 *   resolution / existence (username availability checks, voting-completion
 *   polling) via `sdk.dpns.resolve`.
 * - [de.schildbach.wallet.service.platform.IdentityRepositoryImpl.searchUsernames]
 *   — the username search screens' prefix search via `sdk.dpns.search`, and
 *   its exact-match mode via `sdk.dpns.resolve`.
 *
 * Routed by Phase 3d under the same flag: `getVoteContenders` (contested-name
 * vote state, [SdkVotingQueries]) and profile document queries
 * ([SdkProfileQueries]). Still NOT routed (dashj-only, by design):
 * `names.getByOwnerId` (profile refresh) and `names.getList` (batch domain
 * documents by owner).
 */
@Singleton
class SdkUsernameQueries internal constructor(
    private val source: SdkDpnsSource,
    private val dashPayConfig: DashPayConfig,
    private val dpnsContractId: () -> Identifier?
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkDpnsSource(sdkService),
        dashPayConfig = dashPayConfig,
        // The dashj Platform object knows the per-network DPNS contract id
        // statically (no network I/O); evaluated lazily and only on the
        // flag-on path.
        dpnsContractId = { platformService.platform.apps["dpns"]?.contractId }
    )

    /**
     * SDK-path replacement for `platform.names.get(normalized)` wrapped in a
     * [Resource] — the exact return shape of `PlatformRepo.getUsername`.
     *
     * @return `Resource.success(document)` when the name resolved,
     *   `Resource.success(null)` when the SDK definitively reported the name
     *   as unregistered, or **null** when the caller must fall back to dashj.
     *
     * Blocking by design: the caller is a non-suspend repository method
     * already invoked on background threads, where the dashj path performs
     * blocking network I/O of its own.
     */
    fun getUsernameOrNull(username: String): Resource<Document>? = try {
        runBlocking { resolveAsResource(username) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("SDK DPNS resolve failed; falling back to dashj for getUsername", t)
        null
    }

    /**
     * SDK-path replacement for the name-document retrieval step of
     * `IdentityRepository.searchUsernames` (both prefix-search and
     * exact-match modes). The rest of that pipeline — profile fetch, contact
     * matching, contested-name filtering — is unchanged and still dashj.
     *
     * @return the (possibly empty) synthetic domain-document list, or
     *   **null** when the caller must fall back to dashj.
     */
    suspend fun searchDomainDocumentsOrNull(
        text: String,
        onlyExactUsername: Boolean,
        limit: Int
    ): List<Document>? {
        if (!isEnabled()) return null
        return try {
            val contract = contractOrNull() ?: return null
            if (onlyExactUsername) {
                exactMatchDocuments(text, contract)
            } else {
                val json = source.search(
                    Names.normalizeString(text),
                    if (limit > 0) limit else 0
                ) ?: return null
                SdkDpnsMapping.documentsFromSearchJson(json, contract).also {
                    if (it == null) log.warn("SDK DPNS search returned malformed payload; falling back to dashj")
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK DPNS search failed; falling back to dashj for searchUsernames", t)
            null
        }
    }

    private suspend fun resolveAsResource(username: String): Resource<Document>? {
        if (!isEnabled()) return null
        val contract = contractOrNull() ?: return null
        val normalized = Names.normalizeString(username)
        val json = try {
            source.resolve(normalized)
        } catch (e: Exception) {
            if (SdkDpnsMapping.isNotFound(e)) {
                // dashj parity: names.get returns null for unregistered names.
                return Resource.success(null)
            }
            throw e
        } ?: return Resource.success(null)
        val document = SdkDpnsMapping.documentFromResolveJson(json, username, normalized, contract)
        if (document == null) {
            log.warn("SDK DPNS resolve returned malformed payload; falling back to dashj")
            return null
        }
        return Resource.success(document)
    }

    /** dashj parity for `names.get(text, domain)`: one document, or none. */
    private suspend fun exactMatchDocuments(text: String, contract: DataContract): List<Document>? {
        val normalized = Names.normalizeString(text)
        val json = try {
            source.resolve(normalized)
        } catch (e: Exception) {
            if (SdkDpnsMapping.isNotFound(e)) return emptyList()
            throw e
        } ?: return emptyList()
        val document = SdkDpnsMapping.documentFromResolveJson(json, text, normalized, contract)
            ?: return null // malformed payload → dashj fallback
        return listOf(document)
    }

    /**
     * The runtime flag, re-read from DataStore on every lookup so toggling
     * (either direction) is instant. Default and error value: false.
     */
    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DPNS_READS; keeping dashj path", e)
        false
    }

    private fun contractOrNull(): DataContract? {
        val id = dpnsContractId()
        if (id == null) {
            log.warn("DPNS contract id unavailable; falling back to dashj")
            return null
        }
        return SdkDpnsMapping.minimalDpnsContract(id)
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkUsernameQueries::class.java)
    }
}
