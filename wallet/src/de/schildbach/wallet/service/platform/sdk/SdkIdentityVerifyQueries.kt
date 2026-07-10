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
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seam over the Kotlin SDK's native document search (`sdk.documents.search`
 * against the identity-verify contract's `identityVerify` type), so the
 * mapping/orchestration in [SdkIdentityVerifyQueries] is host-JVM
 * unit-testable with fake JSON — the native calls need `libdash_sdk`.
 */
interface SdkIdentityVerifySearchSource {
    /**
     * Search `identityVerify` documents on the identity-verify contract.
     * [whereJson] / [orderByJson] are rs-sdk-ffi where/order clause arrays;
     * limit 0 = server default. Returns the search payload JSON, or null if
     * the SDK returned no payload.
     */
    suspend fun search(whereJson: String, orderByJson: String?, limit: Int): String?
}

/**
 * Production [SdkIdentityVerifySearchSource]: boots the SDK on demand,
 * fetches (and caches, per SDK handle) the identity-verify
 * [org.dashfoundation.dashsdk.queries.DataContractRef], and queries
 * `sdk.documents` (mirrors [DashSdkProfileSource]).
 */
internal class DashSdkIdentityVerifySearchSource(
    private val service: DashSdkService,
    private val contractId: () -> Identifier?
) : SdkIdentityVerifySearchSource {

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
        val id = checkNotNull(contractId()) { "identity-verify contract id unavailable" }
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

    override suspend fun search(whereJson: String, orderByJson: String?, limit: Int): String? {
        val sdk = sdk()
        return sdk.documents.search(
            contract = contract(sdk),
            documentType = SdkIdentityVerifyMapping.DOCUMENT_TYPE,
            whereJson = whereJson,
            orderByJson = orderByJson,
            limit = limit
        )
    }
}

/**
 * Pure JSON helpers for the identityVerify read — query clauses mirroring
 * the legacy dashj `IdentityVerify.get(userId, normalizedLabel,
 * normalizedParentDomainName)` `DocumentQuery` (verified from
 * dash-sdk-kotlin 4.0.0-RC2 bytecode: `$ownerId ==` +
 * `normalizedParentDomainName ==` + `normalizedLabel ==`, ordered by
 * `normalizedLabel`), and url extraction from the FFI search payload
 * (dpp `to_object()` documents, bare array or `{"documents":[…]}`).
 */
object SdkIdentityVerifyQueryMapping {

    /** Where clause of the legacy per-contender lookup. Gson-built for escaping. */
    fun whereOwnerAndLabel(ownerId: Identifier, username: String): String {
        val where = JsonArray()
        where.add(clause("\$ownerId", ownerId.toString()))
        where.add(clause("normalizedParentDomainName", SdkIdentityVerifyMapping.PARENT_DOMAIN))
        where.add(clause("normalizedLabel", SdkIdentityVerifyMapping.normalizedLabel(username)))
        return where.toString()
    }

    /** Legacy query orders by `normalizedLabel` ascending. */
    const val ORDER_BY_NORMALIZED_LABEL = """[{"field":"normalizedLabel","ascending":true}]"""

    /**
     * Extract the verification `url` of the first document in the payload.
     * - `Optional.of(url)` — document found with a string `url` (root or
     *   `data`-nested);
     * - `Optional.empty()` — the SDK definitively reported no document
     *   (dashj parity: legacy `get` returns null → no link);
     * - null — malformed payload (not an array / first entry not an object
     *   / no string url on a found document): caller falls back to dashj.
     */
    fun urlFromSearchPayload(json: String): Optional<String>? {
        val array = documentsArray(parseOrNull(json)) ?: return null
        if (array.isEmpty) return Optional.empty()
        val obj = (array.first() as? JsonObject) ?: return null
        val data = obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val url = (obj.get("url") ?: data?.get("url")) as? JsonPrimitive
        return url?.takeIf { it.isString }?.let { Optional.of(it.asString) }
    }

    private fun clause(field: String, value: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("field", field)
        obj.addProperty("operator", "=")
        obj.addProperty("value", value)
        return obj
    }

    /** Accept `[…]` or `{"documents":[…]}`; anything else is malformed. */
    private fun documentsArray(element: JsonElement?): JsonArray? = when {
        element == null -> null
        element.isJsonArray -> element.asJsonArray
        element.isJsonObject ->
            element.asJsonObject.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray
        else -> null
    }

    private fun parseOrNull(json: String): JsonElement? = try {
        JsonParser.parseString(json)
    } catch (e: Exception) {
        null
    }
}

/**
 * Routes the wallet's identityVerify document READ — fetching a contender's
 * username verification link for the voting UI — to the Dash Platform
 * Kotlin SDK's generic document search, behind the SAME runtime flag as the
 * other Platform document reads, [DashPayConfig.USE_KOTLIN_SDK_DPNS_READS]
 * (default OFF). Companion of [SdkIdentityVerifyWrites] (dashpay/platform
 * #4088 evaluated: the generic API suffices, no dedicated SDK surface).
 *
 * ## Contract (identical to [SdkUsernameQueries] / [SdkProfileQueries])
 *
 * - [getVerificationUrl] returns **null when the SDK path must not be
 *   used** — flag off, SDK bootstrap failure, native query failure, or
 *   malformed payload — and the call site runs its legacy dashj-platform
 *   code unchanged.
 * - Flag off (the default) leaves the dashj path byte-for-byte intact.
 * - The flag is re-read per lookup; toggles take effect on the next call.
 *
 * Routed call sites (the voting UI's per-contender link reads):
 * - `PlatformSynchronizationService.updateUsernameRequestsWithVotes`
 * - `PlatformSynchronizationService.updateUsernameRequestWithVotes`
 * NOT routed (stay legacy, same query shape, candidates for later):
 * - `CreateIdentityService` / `RestoreIdentityWorker` own-identity link
 *   reads during identity restore.
 */
@Singleton
class SdkIdentityVerifyQueries internal constructor(
    private val source: SdkIdentityVerifySearchSource,
    private val dashPayConfig: DashPayConfig
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        platformService: PlatformService
    ) : this(
        source = DashSdkIdentityVerifySearchSource(
            sdkService,
            { platformService.platform.apps[SdkIdentityVerifyMapping.APP_NAME]?.contractId }
        ),
        dashPayConfig = dashPayConfig
    )

    /**
     * SDK-path replacement for the legacy
     * `IdentityVerify(platform).get(ownerId, username)?.url`.
     *
     * @return `Optional.of(url)` when the contender published a link,
     *   `Optional.empty()` when the SDK definitively reported none, or
     *   **null** when the caller must fall back to dashj.
     */
    suspend fun getVerificationUrl(ownerId: Identifier, username: String): Optional<String>? {
        if (!isEnabled()) return null
        return try {
            val json = source.search(
                whereJson = SdkIdentityVerifyQueryMapping.whereOwnerAndLabel(ownerId, username),
                orderByJson = SdkIdentityVerifyQueryMapping.ORDER_BY_NORMALIZED_LABEL,
                limit = 1
            ) ?: return Optional.empty()
            val url = SdkIdentityVerifyQueryMapping.urlFromSearchPayload(json)
            if (url == null) {
                log.warn("SDK identityVerify search returned malformed payload; falling back to dashj")
            }
            url
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("SDK identityVerify search failed; falling back to dashj", t)
            null
        }
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DPNS_READS; keeping dashj path", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkIdentityVerifyQueries::class.java)
    }
}
